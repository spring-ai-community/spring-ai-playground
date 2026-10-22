/*
 * Copyright © 2025 Jemin Huh (hjm1980@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.playground.service.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.tool.ToolManifest.HumanInTheLoop;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

@Component
public class McpServerHitlToolGate {

    private static final Logger logger = LoggerFactory.getLogger(McpServerHitlToolGate.class);

    private static final Map<String, Object> CONFIRMATION_SCHEMA = Map.of("type", "object", "properties", Map.of());

    public static final String INTERACTIVE_HITL_META_KEY = "playgroundInteractiveHitl";
    public static final String APPROVAL_EXPIRED_META_KEY = "playgroundApprovalExpired";

    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final MeterRegistry meterRegistry;
    private final DeniedToolCallRecorder deniedToolCallRecorder;
    private final ApprovalWaitCountdown countdown;

    @Autowired
    public McpServerHitlToolGate(ObjectProvider<McpClientService> mcpClientServiceProvider,
            MeterRegistry meterRegistry, DeniedToolCallRecorder deniedToolCallRecorder,
            SpringAiPlaygroundOptions playgroundOptions) {
        this(mcpClientServiceProvider, meterRegistry, deniedToolCallRecorder,
                ApprovalWaitCountdown.of(playgroundOptions.hitl()));
    }

    McpServerHitlToolGate(ObjectProvider<McpClientService> mcpClientServiceProvider, MeterRegistry meterRegistry,
            DeniedToolCallRecorder deniedToolCallRecorder, ApprovalWaitCountdown countdown) {
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.meterRegistry = meterRegistry;
        this.deniedToolCallRecorder = deniedToolCallRecorder;
        this.countdown = countdown;
    }

    public static ElicitResult approvalExpired() {
        return new ElicitResult(ElicitResult.Action.CANCEL, Map.of(), Map.of(APPROVAL_EXPIRED_META_KEY, true));
    }

    public SyncToolSpecification decorate(SyncToolSpecification specification, HumanInTheLoop humanInTheLoop,
            RiskLevel riskLevel) {
        if (!requiresApproval(humanInTheLoop)) {
            return specification;
        }
        String promptTemplate = humanInTheLoop.promptTemplate();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate = specification.callHandler();
        return SyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler((exchange, request) -> gate(exchange, request, promptTemplate, riskLevel, delegate))
                .build();
    }

    public AsyncToolSpecification decorate(AsyncToolSpecification specification, HumanInTheLoop humanInTheLoop,
            RiskLevel riskLevel) {
        if (!requiresApproval(humanInTheLoop)) {
            return specification;
        }
        String promptTemplate = humanInTheLoop.promptTemplate();
        BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> delegate = specification.callHandler();
        return AsyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler((exchange, request) -> gateAsync(exchange, request, promptTemplate, riskLevel, delegate))
                .build();
    }

    private CallToolResult gate(McpSyncServerExchange exchange, CallToolRequest request, String promptTemplate,
            RiskLevel riskLevel, BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate) {
        if (isChatLoopback(exchange.getClientInfo()) && !isInteractiveCall(request)) {
            return delegate.apply(exchange, request);
        }
        if (!supportsElicitation(exchange.getClientCapabilities())) {
            logger.info("hitl.server.denied tool={} reason=no-elicitation-capability", request.name());
            return denied(request, exchange.getClientInfo(), "denied");
        }
        ElicitResult result;
        try {
            result = this.countdown.await(
                    () -> exchange.createElicitation(elicitRequest(promptTemplate, riskLevel, request)),
                    progressTicker(exchange, request));
        } catch (RuntimeException e) {
            return denied(request, exchange.getClientInfo(), elicitErrorOutcome(request.name(), e));
        }
        if (!isApproved(result)) {
            return denied(request, exchange.getClientInfo(), decisionOutcome(request.name(), result));
        }
        logger.info("hitl.server.approved tool={}", request.name());
        countDecision("approved");
        return delegate.apply(exchange, request);
    }

    private Mono<CallToolResult> gateAsync(McpAsyncServerExchange exchange, CallToolRequest request,
            String promptTemplate, RiskLevel riskLevel,
            BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> delegate) {
        if (isChatLoopback(exchange.getClientInfo()) && !isInteractiveCall(request)) {
            return delegate.apply(exchange, request);
        }
        if (!supportsElicitation(exchange.getClientCapabilities())) {
            logger.info("hitl.server.denied tool={} reason=no-elicitation-capability", request.name());
            return Mono.just(denied(request, exchange.getClientInfo(), "denied"));
        }
        return this.countdown.await(exchange.createElicitation(elicitRequest(promptTemplate, riskLevel, request)),
                        progressTicker(exchange, request))
                .flatMap(result -> {
                    if (!isApproved(result)) {
                        return Mono.just(denied(request, exchange.getClientInfo(),
                                decisionOutcome(request.name(), result)));
                    }
                    logger.info("hitl.server.approved tool={}", request.name());
                    countDecision("approved");
                    return delegate.apply(exchange, request);
                })
                .onErrorResume(error -> Mono.just(denied(request, exchange.getClientInfo(),
                        elicitErrorOutcome(request.name(), error))));
    }

    private static boolean requiresApproval(HumanInTheLoop humanInTheLoop) {
        return humanInTheLoop != null && humanInTheLoop.mode() == HumanInTheLoop.Mode.REQUIRED;
    }

    // Chat loopback is gated chat-side already, so skip server elicitation to avoid a double prompt.
    private boolean isChatLoopback(Implementation clientInfo) {
        McpClientService mcpClientService = this.mcpClientServiceProvider.getIfAvailable();
        return mcpClientService != null && mcpClientService.isSelfLoopbackClient(clientInfo);
    }

    private static boolean isInteractiveCall(CallToolRequest request) {
        Map<String, Object> meta = request == null ? null : request.meta();
        return meta != null && Boolean.TRUE.equals(meta.get(INTERACTIVE_HITL_META_KEY));
    }

    private static boolean supportsElicitation(ClientCapabilities capabilities) {
        return capabilities != null && capabilities.elicitation() != null;
    }

    private static boolean isApproved(ElicitResult result) {
        return result != null && result.action() == ElicitResult.Action.ACCEPT;
    }

    public static boolean isApprovalExpired(ElicitResult result) {
        return result != null && result.meta() != null
                && Boolean.TRUE.equals(result.meta().get(APPROVAL_EXPIRED_META_KEY));
    }

    private static String decisionOutcome(String toolName, ElicitResult result) {
        if (isApprovalExpired(result)) {
            logger.info("hitl.server.timeout tool={}", toolName);
            return "timeout";
        }
        logger.info("hitl.server.declined tool={} action={}", toolName, action(result));
        return "declined";
    }

    private static String action(ElicitResult result) {
        return result == null ? "none" : String.valueOf(result.action());
    }

    private ElicitRequest elicitRequest(String promptTemplate, RiskLevel riskLevel, CallToolRequest request) {
        return ElicitRequest.builder(
                riskPrefix(riskLevel) + renderPrompt(promptTemplate, request.name(), request.arguments())
                        + " " + this.countdown.notice(),
                CONFIRMATION_SCHEMA).build();
    }

    private static ApprovalWaitCountdown.Ticker progressTicker(McpSyncServerExchange exchange,
            CallToolRequest request) {
        Object token = request.progressToken();
        if (token == null) return ApprovalWaitCountdown.Ticker.NONE;
        return (elapsed, total, message) -> {
            try {
                exchange.progressNotification(new ProgressNotification(token, elapsed, (double) total, message));
            } catch (RuntimeException e) {
                logger.debug("hitl.server.progress-not-delivered tool={}", request.name(), e);
            }
        };
    }

    private static ApprovalWaitCountdown.Ticker progressTicker(McpAsyncServerExchange exchange,
            CallToolRequest request) {
        Object token = request.progressToken();
        if (token == null) return ApprovalWaitCountdown.Ticker.NONE;
        return (elapsed, total, message) -> exchange
                .progressNotification(new ProgressNotification(token, elapsed, (double) total, message))
                .onErrorResume(error -> {
                    logger.debug("hitl.server.progress-not-delivered tool={}", request.name(), error);
                    return Mono.empty();
                })
                .subscribe();
    }

    static String riskPrefix(RiskLevel riskLevel) {
        return riskLevel == null ? "" : "[risk " + riskLevel.name() + " " + riskLevel.label() + "] ";
    }

    static String renderPrompt(String promptTemplate, String toolName, Map<String, Object> arguments) {
        String template = (promptTemplate == null || promptTemplate.isBlank())
                ? "Approve running tool '{toolName}' with arguments {args}?" : promptTemplate;
        return template.replace("{toolName}", toolName == null ? "" : toolName)
                .replace("{args}", arguments == null ? "{}" : arguments.toString());
    }

    private CallToolResult denied(CallToolRequest request, Implementation clientInfo, String outcome) {
        countDecision(outcome);
        if (!isChatLoopback(clientInfo)) {
            this.deniedToolCallRecorder.recordExternalClientDenial(request, clientInfo, outcome);
        }
        String toolName = request.name();
        return CallToolResult.builder()
                .addTextContent("Tool '" + toolName + "' was not run: " + notRunReason(outcome) + ". "
                        + "Do not call '" + toolName + "' again for this request; ask the user to run it "
                        + "manually if it is still needed.")
                .isError(true)
                .build();
    }

    private String notRunReason(String outcome) {
        return "timeout".equals(outcome)
                ? "no approval within " + this.countdown.waitSeconds() + " s"
                : "human-in-the-loop approval was not granted";
    }

    private static String elicitErrorOutcome(String toolName, Throwable error) {
        if (isTimeout(error)) {
            logger.info("hitl.server.timeout tool={}", toolName);
            return "timeout";
        }
        logger.warn("hitl.server.elicit-failed tool={} error={}", toolName, error.getMessage());
        return "elicit-failed";
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TimeoutException) return true;
        }
        return false;
    }

    private void countDecision(String outcome) {
        this.meterRegistry.counter("mcp.hitl.decision", "outcome", outcome, "side", "server").increment();
    }
}
