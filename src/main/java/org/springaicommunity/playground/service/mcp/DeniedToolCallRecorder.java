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
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@Component
public class DeniedToolCallRecorder {

    public static final String HITL_OUTCOME = "saip.hitl.outcome";

    private static final String EMPTY_ARGUMENTS = "{}";
    private static final ToolCallingObservationConvention CONVENTION = new DefaultToolCallingObservationConvention();

    private final ObservationRegistry observationRegistry;
    private final ObjectProvider<ToolSpecService> toolSpecServiceProvider;
    private final ObjectMapper objectMapper;

    public DeniedToolCallRecorder(ObservationRegistry observationRegistry,
            ObjectProvider<ToolSpecService> toolSpecServiceProvider, ObjectMapper objectMapper) {
        this.observationRegistry = observationRegistry;
        this.toolSpecServiceProvider = toolSpecServiceProvider;
        this.objectMapper = objectMapper;
    }

    public void recordChatDenial(String toolName, String toolCallId, String argumentsJson, String outcome) {
        ToolCallingObservationContext.Builder context = contextOf(toolName,
                argumentsJson == null || argumentsJson.isBlank() ? EMPTY_ARGUMENTS : argumentsJson);
        if (toolCallId != null && !toolCallId.isBlank()) context.toolCallId(toolCallId);
        observation(context.build(), outcome).start().stop();
    }

    public void recordExternalClientDenial(CallToolRequest request, Implementation clientInfo, String outcome) {
        Observation observation = observation(contextOf(request.name(), argumentsJson(request)).build(), outcome);
        ExternalClientToolCallObserver.tagCaller(observation, clientInfo).start().stop();
    }

    private Observation observation(ToolCallingObservationContext context, String outcome) {
        return ToolCallingObservationDocumentation.TOOL_CALL
                .observation(null, CONVENTION, () -> context, this.observationRegistry)
                .highCardinalityKeyValue(HITL_OUTCOME, outcome);
    }

    private ToolCallingObservationContext.Builder contextOf(String toolName, String argumentsJson) {
        return ToolCallingObservationContext.builder()
                .toolDefinition(definitionOf(toolName))
                .toolMetadata(ToolMetadata.builder().build())
                .toolCallArguments(argumentsJson);
    }

    private ToolDefinition definitionOf(String toolName) {
        ToolSpecService toolSpecService = this.toolSpecServiceProvider.getIfAvailable();
        Optional<ToolDefinition> known = toolSpecService == null ? Optional.empty()
                : toolSpecService.toolDefinitionOf(toolName);
        return known.orElseGet(() -> DefaultToolDefinition.builder().name(toolName).description(toolName)
                .inputSchema(EMPTY_ARGUMENTS).build());
    }

    private String argumentsJson(CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            return this.objectMapper.writeValueAsString(arguments);
        } catch (JacksonException e) {
            return EMPTY_ARGUMENTS;
        }
    }
}
