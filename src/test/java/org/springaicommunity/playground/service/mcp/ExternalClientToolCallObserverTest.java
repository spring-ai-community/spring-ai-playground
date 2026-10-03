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
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.McpRiskMdcKeys;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalClientToolCallObserverTest {

    private static final String LOOPBACK_NAME = "spring-ai-playground-built-in";
    private static final Implementation EXTERNAL_CLIENT = new Implementation("curl", "1.0");
    private static final Implementation CHAT_CLIENT = new Implementation("client - " + LOOPBACK_NAME, "1.0");

    @SuppressWarnings("unchecked")
    private final ObjectProvider<McpClientService> mcpClientServiceProvider = mock(ObjectProvider.class);
    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final List<Observation.Context> stopped = new ArrayList<>();
    private final ExternalClientToolCallObserver observer = new ExternalClientToolCallObserver(observationRegistry,
            mcpClientServiceProvider, new ObjectMapper());
    private final ToolCallback callback = mock(ToolCallback.class);
    private final CallToolRequest request = new CallToolRequest("team_echo", Map.of("message", "hi"));
    private final CallToolResult okResult = CallToolResult.builder().addTextContent("Echo: hi").isError(false).build();
    private final CallToolResult errorResult = CallToolResult.builder().addTextContent("boom").isError(true).build();

    @BeforeEach
    void setUp() {
        when(mcpClientServiceProvider.getIfAvailable()).thenReturn(mcpClientService);
        when(mcpClientService.isSelfLoopbackClient(any())).thenAnswer(invocation -> {
            Implementation info = invocation.getArgument(0);
            return info != null && info.name().endsWith(LOOPBACK_NAME);
        });
        when(callback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder().name("team_echo")
                .description("Echo").inputSchema("{\"type\":\"object\"}").build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
    }

    private SyncToolSpecification syncSpecification(CallToolResult result) {
        return SyncToolSpecification.builder().tool(Tool.builder().name("team_echo").build())
                .callHandler((exchange, callRequest) -> result).build();
    }

    private static McpSyncServerExchange syncExchange(Implementation clientInfo) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(clientInfo);
        return exchange;
    }

    @Test
    void externalClientCallIsRecordedAsAToolSpanNamedByTheExposedAlias() {
        CallToolResult result = observer.observe(syncSpecification(okResult), callback).callHandler()
                .apply(syncExchange(EXTERNAL_CLIENT), request);

        assertThat(result).isSameAs(okResult);
        assertThat(stopped).singleElement().isInstanceOfSatisfying(ToolCallingObservationContext.class, context -> {
            assertThat(context.getName()).isEqualTo("spring.ai.tool");
            assertThat(context.getToolDefinition().name()).isEqualTo("team_echo");
            assertThat(context.getToolCallArguments()).isEqualTo("{\"message\":\"hi\"}");
            assertThat(context.getError()).isNull();
            assertThat(context.getHighCardinalityKeyValue(ExternalClientToolCallObserver.CALLER_VIA).getValue())
                    .isEqualTo(McpRiskMdcKeys.VIA_MCP_SERVER);
            assertThat(context.getHighCardinalityKeyValue(ExternalClientToolCallObserver.CALLER_CLIENT).getValue())
                    .isEqualTo("curl");
        });
    }

    @Test
    void clientWithoutANameIsRecordedAsAnExternalCallWithoutAClientName() {
        observer.observe(syncSpecification(okResult), callback).callHandler().apply(syncExchange(null), request);

        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getHighCardinalityKeyValue(ExternalClientToolCallObserver.CALLER_VIA).getValue())
                    .isEqualTo(McpRiskMdcKeys.VIA_MCP_SERVER);
            assertThat(context.getHighCardinalityKeyValue(ExternalClientToolCallObserver.CALLER_CLIENT)).isNull();
        });
    }

    @Test
    void chatLoopbackCallIsNotRecordedAgain() {
        CallToolResult result = observer.observe(syncSpecification(okResult), callback).callHandler()
                .apply(syncExchange(CHAT_CLIENT), request);

        assertThat(result).isSameAs(okResult);
        assertThat(stopped).isEmpty();
    }

    @Test
    void errorResultMarksTheSpanAsFailed() {
        observer.observe(syncSpecification(errorResult), callback).callHandler()
                .apply(syncExchange(EXTERNAL_CLIENT), request);

        assertThat(stopped).singleElement().satisfies(context -> assertThat(context.getError())
                .hasMessage(ExternalClientToolCallObserver.ERROR_RESULT_MESSAGE));
    }

    @Test
    void thrownFailureMarksTheSpanAndPropagates() {
        SyncToolSpecification failing = SyncToolSpecification.builder()
                .tool(Tool.builder().name("team_echo").build())
                .callHandler((exchange, callRequest) -> {
                    throw new IllegalStateException("upstream down");
                }).build();

        assertThatThrownBy(() -> observer.observe(failing, callback).callHandler()
                .apply(syncExchange(EXTERNAL_CLIENT), request)).hasMessage("upstream down");
        assertThat(stopped).singleElement().satisfies(context -> assertThat(context.getError())
                .hasMessage("upstream down"));
    }

    @Test
    void missingArgumentsAreRecordedAsAnEmptyObject() {
        observer.observe(syncSpecification(okResult), callback).callHandler()
                .apply(syncExchange(EXTERNAL_CLIENT), new CallToolRequest("team_echo", null));

        assertThat(stopped).singleElement().isInstanceOfSatisfying(ToolCallingObservationContext.class,
                context -> assertThat(context.getToolCallArguments()).isEqualTo("{}"));
    }

    @Test
    void asyncExternalClientCallIsRecordedAndLoopbackIsNot() {
        AsyncToolSpecification specification = AsyncToolSpecification.builder()
                .tool(Tool.builder().name("team_echo").build())
                .callHandler((exchange, callRequest) -> Mono.just(errorResult)).build();
        McpAsyncServerExchange external = mock(McpAsyncServerExchange.class);
        when(external.getClientInfo()).thenReturn(EXTERNAL_CLIENT);
        McpAsyncServerExchange loopback = mock(McpAsyncServerExchange.class);
        when(loopback.getClientInfo()).thenReturn(CHAT_CLIENT);
        AsyncToolSpecification observed = observer.observe(specification, callback);

        assertThat(observed.callHandler().apply(loopback, request).block()).isSameAs(errorResult);
        assertThat(stopped).isEmpty();

        assertThat(observed.callHandler().apply(external, request).block()).isSameAs(errorResult);
        assertThat(stopped).singleElement().satisfies(context -> assertThat(context.getError())
                .hasMessage(ExternalClientToolCallObserver.ERROR_RESULT_MESSAGE));
    }
}
