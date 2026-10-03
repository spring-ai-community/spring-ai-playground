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
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.risk.McpRiskMdcKeys;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeniedToolCallRecorderTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ToolSpecService> toolSpecServiceProvider = mock(ObjectProvider.class);
    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final List<Observation.Context> stopped = new ArrayList<>();
    private final DeniedToolCallRecorder recorder = new DeniedToolCallRecorder(observationRegistry,
            toolSpecServiceProvider, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(toolSpecServiceProvider.getIfAvailable()).thenReturn(toolSpecService);
        when(toolSpecService.toolDefinitionOf(anyString())).thenReturn(Optional.empty());
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

    private static String value(Observation.Context context, String key) {
        return context.getHighCardinalityKeyValue(key) == null ? null
                : context.getHighCardinalityKeyValue(key).getValue();
    }

    @Test
    void chatDenialIsRecordedAsAToolSpanWithItsOutcomeAndNoCaller() {
        when(toolSpecService.toolDefinitionOf("writeFile")).thenReturn(Optional.of(DefaultToolDefinition.builder()
                .name("writeFile").description("Writes a file").inputSchema("{\"type\":\"object\"}").build()));

        recorder.recordChatDenial("writeFile", "call-1", "{\"path\":\"/tmp/x\"}", "declined");

        assertThat(stopped).singleElement().isInstanceOfSatisfying(ToolCallingObservationContext.class, context -> {
            assertThat(context.getName()).isEqualTo("spring.ai.tool");
            assertThat(context.getToolDefinition().description()).isEqualTo("Writes a file");
            assertThat(context.getToolCallId()).isEqualTo("call-1");
            assertThat(context.getToolCallArguments()).isEqualTo("{\"path\":\"/tmp/x\"}");
            assertThat(value(context, DeniedToolCallRecorder.HITL_OUTCOME)).isEqualTo("declined");
            assertThat(value(context, ExternalClientToolCallObserver.CALLER_VIA)).isNull();
            assertThat(context.getError()).isNull();
        });
    }

    @Test
    void unknownToolAndBlankArgumentsStillProduceASpan() {
        when(toolSpecServiceProvider.getIfAvailable()).thenReturn(null);

        recorder.recordChatDenial("ghostTool", null, " ", "timeout");

        assertThat(stopped).singleElement().isInstanceOfSatisfying(ToolCallingObservationContext.class, context -> {
            assertThat(context.getToolDefinition().name()).isEqualTo("ghostTool");
            assertThat(context.getToolCallArguments()).isEqualTo("{}");
            assertThat(value(context, DeniedToolCallRecorder.HITL_OUTCOME)).isEqualTo("timeout");
        });
    }

    @Test
    void externalClientDenialCarriesTheClientName() {
        recorder.recordExternalClientDenial(new CallToolRequest("team_sum", Map.of("a", 1)),
                new Implementation("cursor", "1.0"), "denied");

        assertThat(stopped).singleElement().isInstanceOfSatisfying(ToolCallingObservationContext.class, context -> {
            assertThat(context.getToolDefinition().name()).isEqualTo("team_sum");
            assertThat(context.getToolCallArguments()).isEqualTo("{\"a\":1}");
            assertThat(value(context, DeniedToolCallRecorder.HITL_OUTCOME)).isEqualTo("denied");
            assertThat(value(context, ExternalClientToolCallObserver.CALLER_VIA))
                    .isEqualTo(McpRiskMdcKeys.VIA_MCP_SERVER);
            assertThat(value(context, ExternalClientToolCallObserver.CALLER_CLIENT)).isEqualTo("cursor");
        });
    }
}
