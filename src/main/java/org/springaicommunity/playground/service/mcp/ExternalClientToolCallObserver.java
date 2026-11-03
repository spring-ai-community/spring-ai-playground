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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.McpRiskMdcKeys;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@Component
public class ExternalClientToolCallObserver {

    public static final String CALLER_VIA = McpRiskMdcKeys.VIA;
    public static final String CALLER_CLIENT = "saip.mcp.client";

    static final String ERROR_RESULT_MESSAGE = "Tool call returned an error result";

    private static final ToolCallingObservationConvention CONVENTION = new DefaultToolCallingObservationConvention();

    private final ObservationRegistry observationRegistry;
    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final ObjectMapper objectMapper;

    public ExternalClientToolCallObserver(ObservationRegistry observationRegistry,
            ObjectProvider<McpClientService> mcpClientServiceProvider, ObjectMapper objectMapper) {
        this.observationRegistry = observationRegistry;
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.objectMapper = objectMapper;
    }

    public SyncToolSpecification observe(SyncToolSpecification specification, ToolCallback callback) {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate = specification.callHandler();
        return SyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler((exchange, request) -> isChatLoopback(exchange.getClientInfo())
                        ? delegate.apply(exchange, request)
                        : observed(callback, request, exchange.getClientInfo(),
                                () -> delegate.apply(exchange, request)))
                .build();
    }

    public AsyncToolSpecification observe(AsyncToolSpecification specification, ToolCallback callback) {
        BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> delegate =
                specification.callHandler();
        return AsyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler((exchange, request) -> isChatLoopback(exchange.getClientInfo())
                        ? delegate.apply(exchange, request)
                        : Mono.defer(() -> {
                            Observation observation = start(callback, request, exchange.getClientInfo());
                            return delegate.apply(exchange, request)
                                    .doOnNext(result -> markErrorResult(observation, result))
                                    .doOnError(observation::error)
                                    .doFinally(signal -> observation.stop());
                        }))
                .build();
    }

    private CallToolResult observed(ToolCallback callback, CallToolRequest request, Implementation clientInfo,
            Supplier<CallToolResult> call) {
        Observation observation = start(callback, request, clientInfo);
        try (Observation.Scope scope = observation.openScope()) {
            CallToolResult result = call.get();
            markErrorResult(observation, result);
            return result;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private Observation start(ToolCallback callback, CallToolRequest request, Implementation clientInfo) {
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
                .toolDefinition(callback.getToolDefinition())
                .toolMetadata(callback.getToolMetadata())
                .toolCallArguments(argumentsJson(request))
                .build();
        return tagCaller(ToolCallingObservationDocumentation.TOOL_CALL
                .observation(null, CONVENTION, () -> context, this.observationRegistry), clientInfo).start();
    }

    static Observation tagCaller(Observation observation, Implementation clientInfo) {
        observation.highCardinalityKeyValue(CALLER_VIA, McpRiskMdcKeys.VIA_MCP_SERVER);
        if (clientInfo != null && clientInfo.name() != null && !clientInfo.name().isBlank()) {
            observation.highCardinalityKeyValue(CALLER_CLIENT, clientInfo.name());
        }
        return observation;
    }

    private String argumentsJson(CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            return this.objectMapper.writeValueAsString(arguments);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    private static void markErrorResult(Observation observation, CallToolResult result) {
        if (result != null && Boolean.TRUE.equals(result.isError())) {
            observation.error(new IllegalStateException(ERROR_RESULT_MESSAGE));
        }
    }

    private boolean isChatLoopback(Implementation clientInfo) {
        McpClientService mcpClientService = this.mcpClientServiceProvider.getIfAvailable();
        return mcpClientService != null && mcpClientService.isSelfLoopbackClient(clientInfo);
    }
}
