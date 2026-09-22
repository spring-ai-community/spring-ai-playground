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
package org.springaicommunity.playground.service.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChatToolPreferences(boolean useBuiltinMcp, Set<String> exposedToolIds, String ragSourceId,
        Map<McpTransportType, List<String>> mcpServerNames, ReasoningEffort reasoningEffort, boolean dynamicTools) {

    public ChatToolPreferences {
        exposedToolIds = exposedToolIds == null ? Set.of() : Set.copyOf(exposedToolIds);
        mcpServerNames = mcpServerNames == null ? Map.of() : Map.copyOf(mcpServerNames);
        reasoningEffort = reasoningEffort == null ? ReasoningEffort.DEFAULT : reasoningEffort;
    }

    @JsonCreator
    static ChatToolPreferences fromJson(@JsonProperty("useBuiltinMcp") boolean useBuiltinMcp,
            @JsonProperty("exposedToolIds") Set<String> exposedToolIds,
            @JsonProperty("ragSourceId") String ragSourceId,
            @JsonProperty("ragDocInfoIds") List<String> ragDocInfoIds,
            @JsonProperty("mcpServerNames") Map<McpTransportType, List<String>> mcpServerNames,
            @JsonProperty("reasoningEffort") ReasoningEffort reasoningEffort,
            @JsonProperty("dynamicTools") boolean dynamicTools) {
        String sourceId = ragSourceId == null && ragDocInfoIds != null && !ragDocInfoIds.isEmpty()
                ? ChatService.RAG_DOCUMENT_SOURCE_PREFIX + ragDocInfoIds.getFirst() : ragSourceId;
        return new ChatToolPreferences(useBuiltinMcp, exposedToolIds, sourceId, mcpServerNames, reasoningEffort,
                dynamicTools);
    }

    public static ChatToolPreferences defaults() {
        return new ChatToolPreferences(false, Set.of(), null, Map.of(), ReasoningEffort.DEFAULT, false);
    }

    public ChatToolPreferences withDynamicTools(boolean dynamicTools) {
        return new ChatToolPreferences(useBuiltinMcp, exposedToolIds, ragSourceId, mcpServerNames,
                reasoningEffort, dynamicTools);
    }
}
