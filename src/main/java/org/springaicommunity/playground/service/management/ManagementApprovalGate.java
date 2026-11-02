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
package org.springaicommunity.playground.service.management;

import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
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
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.service.mcp.ApprovalConfirmation;
import org.springaicommunity.playground.service.mcp.ApprovalWaitCountdown;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Component
public class ManagementApprovalGate {

    private static final Logger logger = LoggerFactory.getLogger(ManagementApprovalGate.class);
    private static final int SUMMARY_LIMIT = 300;

    private record Stop(ManagementOutcome outcome, ManagementResult result) {
    }

    private final SpringAiPlaygroundOptions options;
    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final ManagementAuditLog auditLog;
    private final ApprovalWaitCountdown countdown;
    private final MeterRegistry meterRegistry;

    @Autowired
    public ManagementApprovalGate(SpringAiPlaygroundOptions options,
            ObjectProvider<McpClientService> mcpClientServiceProvider, ManagementAuditLog auditLog,
            MeterRegistry meterRegistry) {
        this(options, mcpClientServiceProvider, auditLog, meterRegistry, ApprovalWaitCountdown.of(options.hitl()));
    }

    ManagementApprovalGate(SpringAiPlaygroundOptions options,
            ObjectProvider<McpClientService> mcpClientServiceProvider, ManagementAuditLog auditLog,
            MeterRegistry meterRegistry, ApprovalWaitCountdown countdown) {
        this.options = options;
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.auditLog = auditLog;
        this.meterRegistry = meterRegistry;
        this.countdown = countdown;
    }

    public SyncToolSpecification wrap(ManagementTool tool) {
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(ManagementSchemas.inputSchema(tool.inputType()))
                .outputSchema(ManagementSchemas.outputSchema(tool.dataType()))
                .annotations(new McpSchema.ToolAnnotations(tool.name(), tool.level() == ApprovalLevel.READ,
                        tool.level() == ApprovalLevel.DESTRUCTIVE, tool.idempotent(), tool.openWorld(), false))
                .build();
        return SyncToolSpecification.builder().tool(definition)
                .callHandler((exchange, request) -> execute(tool, exchange, request))
                .build();
    }

    CallToolResult execute(ManagementTool tool, McpSyncServerExchange exchange, CallToolRequest request) {
        long startNanos = System.nanoTime();
        ManagementCaller caller = callerOf(exchange, request);
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        ManagementApproval approval;
        try {
            approval = tool.approvalFor(arguments);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return finish(tool, caller, arguments, tool.level(), ManagementOutcome.ERROR,
                    ManagementResult.failure(ManagementSecrets.maskText(e.getMessage())), startNanos);
        }
        if (caller.selfLoopback() && !caller.interactive()) {
            return finish(tool, caller, arguments, approval.level(), ManagementOutcome.REFUSED,
                    ManagementResult.failure("Management tools are not available to this app's own chat. "
                            + "Use an external MCP client or the MCP Inspector."), startNanos);
        }
        Optional<Stop> stop = requireApproval(tool, caller, approval, exchange);
        if (stop.isPresent()) {
            return finish(tool, caller, arguments, approval.level(), stop.get().outcome(), stop.get().result(),
                    startNanos);
        }
        ManagementResult result;
        try {
            result = tool.handle(arguments, caller);
        } catch (IllegalArgumentException | IllegalStateException e) {
            result = ManagementResult.failure(ManagementSecrets.maskText(e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Management tool '{}' failed", tool.name(), e);
            result = ManagementResult.failure("Unexpected error: "
                    + ManagementSecrets.maskText(String.valueOf(e.getMessage())));
        }
        ManagementOutcome outcome = result.ok() ? ManagementOutcome.OK : ManagementOutcome.ERROR;
        return finish(tool, caller, arguments, approval.level(), outcome, result, startNanos);
    }

    private Optional<Stop> requireApproval(ManagementTool tool, ManagementCaller caller,
            ManagementApproval approval, McpSyncServerExchange exchange) {
        ApprovalMode mode = this.options.management().approval();
        if (approval.highRisk() && mode == ApprovalMode.NONE) {
            return Optional.of(new Stop(ManagementOutcome.REFUSED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: this is a high-risk action and needs a human decision, but approval is "
                    + "disabled. Ask the user to do it in the app or to enable approval. Requested: "
                    + approval.summary())));
        }
        boolean prompt = approval.highRisk() || switch (mode) {
            case NONE -> false;
            case ALL -> approval.level() != ApprovalLevel.READ;
            case DESTRUCTIVE -> approval.level() == ApprovalLevel.DESTRUCTIVE;
        };
        if (!prompt) {
            return Optional.empty();
        }
        if (!supportsElicitation(exchange.getClientCapabilities())) {
            countDecision("denied");
            return Optional.of(new Stop(ManagementOutcome.DENIED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: it needs human approval and this client cannot ask for it (no elicitation "
                    + "support). Ask the user to run it from a client that supports elicitation, or to make the "
                    + "change in the app.")));
        }
        ElicitResult decision;
        try {
            ElicitRequest elicitRequest = ElicitRequest.builder(
                    "[" + approval.level() + "] " + caller.label() + " wants to run " + tool.name() + ": "
                            + sentence(approval.summary()) + " " + this.countdown.notice(),
                    ApprovalConfirmation.SCHEMA).build();
            decision = this.countdown.await(() -> exchange.createElicitation(elicitRequest),
                    (elapsed, total, message) -> caller.progress().report(elapsed, (double) total, message));
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                logger.info("mgmt.approval.timeout tool={}", tool.name());
                return Optional.of(expired(tool));
            }
            logger.info("mgmt.approval.error tool={} error={}", tool.name(), e.getMessage());
            countDecision("elicit-failed");
            return Optional.of(new Stop(ManagementOutcome.DENIED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: the approval request failed.")));
        }
        if (McpServerHitlToolGate.isApprovalExpired(decision)) {
            logger.info("mgmt.approval.timeout tool={}", tool.name());
            return Optional.of(expired(tool));
        }
        if (!ApprovalConfirmation.isApproved(decision)) {
            countDecision("declined");
            return Optional.of(new Stop(ManagementOutcome.DECLINED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: the user declined. Do not retry it for this request.")));
        }
        countDecision("approved");
        return Optional.empty();
    }

    private static String sentence(String summary) {
        String trimmed = summary.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") ? trimmed : trimmed + ".";
    }

    private void countDecision(String outcome) {
        this.meterRegistry.counter("mcp.hitl.decision", "outcome", outcome, "side", "server").increment();
    }

    private Stop expired(ManagementTool tool) {
        countDecision("timeout");
        return new Stop(ManagementOutcome.TIMEOUT, ManagementResult.failure("Tool '" + tool.name()
                + "' was not run: no approval within " + this.countdown.waitSeconds()
                + " s. Do not retry it for this request; ask the user to run it from the app if it is still needed."));
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TimeoutException) return true;
        }
        return false;
    }

    private ManagementCaller callerOf(McpSyncServerExchange exchange, CallToolRequest request) {
        Implementation info = exchange.getClientInfo();
        McpClientService mcpClientService = this.mcpClientServiceProvider.getIfAvailable();
        boolean selfLoopback = mcpClientService != null && mcpClientService.isSelfLoopbackClient(info);
        Map<String, Object> meta = request.meta();
        boolean interactive = meta != null
                && Boolean.TRUE.equals(meta.get(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY));
        ManagementProgress progress = progressOf(exchange, request);
        return new ManagementCaller(info == null || info.name() == null ? "unknown" : info.name(),
                info == null || info.version() == null ? "" : info.version(), interactive,
                supportsElicitation(exchange.getClientCapabilities()), selfLoopback, progress,
                elicitationOf(exchange, progress));
    }

    private ManagementElicitation elicitationOf(McpSyncServerExchange exchange, ManagementProgress progress) {
        if (!supportsElicitation(exchange.getClientCapabilities())) return ManagementElicitation.UNAVAILABLE;
        return message -> {
            ElicitRequest elicitRequest = ElicitRequest.builder(message + " " + this.countdown.notice(),
                    ApprovalConfirmation.SCHEMA).build();
            try {
                return Optional.of(this.countdown.await(() -> exchange.createElicitation(elicitRequest),
                        (elapsed, total, tick) -> progress.report(elapsed, (double) total, tick)));
            } catch (RuntimeException e) {
                if (isTimeout(e)) return Optional.of(McpServerHitlToolGate.approvalExpired());
                logger.info("mgmt.elicitation.error error={}", e.getMessage());
                return Optional.empty();
            }
        };
    }

    private static ManagementProgress progressOf(McpSyncServerExchange exchange, CallToolRequest request) {
        Object token = request.progressToken();
        if (token == null) return ManagementProgress.NONE;
        return (progress, total, message) -> {
            try {
                exchange.progressNotification(new ProgressNotification(token, progress, total, message));
            } catch (RuntimeException e) {
                logger.debug("Progress notification for token {} was not delivered", token, e);
            }
        };
    }

    private CallToolResult finish(ManagementTool tool, ManagementCaller caller, Map<String, Object> arguments,
            ApprovalLevel level, ManagementOutcome outcome, ManagementResult result, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        this.auditLog.record(new ManagementAuditLog.Entry(System.currentTimeMillis(), caller.label(), tool.name(),
                level, outcome, summarize(arguments, result), durationMs));
        Map<String, Object> structured = ManagementJson.toMap(result);
        return CallToolResult.builder()
                .addTextContent(ManagementJson.write(structured))
                .structuredContent(structured)
                .isError(!result.ok())
                .build();
    }

    private static String summarize(Map<String, Object> arguments, ManagementResult result) {
        String text = "args=" + ManagementSecrets.maskArguments(arguments) + " result=" + result.message();
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT) + "...";
    }

    private static boolean supportsElicitation(ClientCapabilities capabilities) {
        return capabilities != null && capabilities.elicitation() != null;
    }

}
