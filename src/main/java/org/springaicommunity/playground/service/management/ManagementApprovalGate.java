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

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

@Component
public class ManagementApprovalGate {

    private static final Logger logger = LoggerFactory.getLogger(ManagementApprovalGate.class);
    private static final JsonMapper RESULT_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Map<String, Object> CONFIRMATION_SCHEMA = Map.of("type", "object", "properties", Map.of());
    private static final int SUMMARY_LIMIT = 300;

    private record Stop(ManagementOutcome outcome, ManagementResult result) {
    }

    private final SpringAiPlaygroundOptions options;
    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final ManagementAuditLog auditLog;

    public ManagementApprovalGate(SpringAiPlaygroundOptions options,
            ObjectProvider<McpClientService> mcpClientServiceProvider, ManagementAuditLog auditLog) {
        this.options = options;
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.auditLog = auditLog;
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
            return Optional.of(new Stop(ManagementOutcome.DENIED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: it needs human approval and this client cannot ask for it (no elicitation "
                    + "support). Ask the user to run it from a client that supports elicitation, or to make the "
                    + "change in the app.")));
        }
        ElicitResult decision;
        try {
            decision = exchange.createElicitation(ElicitRequest.builder(
                    "[" + approval.level() + "] " + caller.label() + " wants to run " + tool.name() + ": "
                            + approval.summary(), CONFIRMATION_SCHEMA).build());
        } catch (RuntimeException e) {
            logger.info("mgmt.approval.error tool={} error={}", tool.name(), e.getMessage());
            return Optional.of(new Stop(ManagementOutcome.DENIED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: the approval request failed or timed out.")));
        }
        if (decision == null || decision.action() != ElicitResult.Action.ACCEPT) {
            return Optional.of(new Stop(ManagementOutcome.DECLINED, ManagementResult.failure("Tool '" + tool.name()
                    + "' was not run: the user declined. Do not retry it for this request.")));
        }
        return Optional.empty();
    }

    private ManagementCaller callerOf(McpSyncServerExchange exchange, CallToolRequest request) {
        Implementation info = exchange.getClientInfo();
        McpClientService mcpClientService = this.mcpClientServiceProvider.getIfAvailable();
        boolean selfLoopback = mcpClientService != null && mcpClientService.isSelfLoopbackClient(info);
        Map<String, Object> meta = request.meta();
        boolean interactive = meta != null
                && Boolean.TRUE.equals(meta.get(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY));
        return new ManagementCaller(info == null || info.name() == null ? "unknown" : info.name(),
                info == null || info.version() == null ? "" : info.version(), interactive,
                supportsElicitation(exchange.getClientCapabilities()), selfLoopback, progressOf(exchange, request));
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
        Map<String, Object> structured = RESULT_MAPPER.convertValue(result, MAP_TYPE);
        return CallToolResult.builder()
                .addTextContent(RESULT_MAPPER.writeValueAsString(structured))
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
