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
package org.springaicommunity.playground.service.mcp.risk;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExposedToolServiceTest {

    private ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    @Test
    void syncAddsMissingAndRemovesStaleExternalTools() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);

        McpComposition exposed = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools",
                "", List.of(), true, RiskLevel.L5, 0L, 0L, 0L);
        ToolCallback freshTool = callback("x");
        ToolCallback alreadyExposed = callback("y");
        when(compositionService.getExposed()).thenReturn(Optional.of(exposed));
        when(provider.getToolCallbacksFor(exposed)).thenReturn(new ToolCallback[]{freshTool, alreadyExposed});
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("y", "z"));

        new McpExposedToolService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("z");
        verify(toolSpecService, never()).removeExternalMcpTool("y");
        verify(toolSpecService).addExternalMcpTool(freshTool);
        verify(toolSpecService, never()).addExternalMcpTool(alreadyExposed);
    }

    @Test
    void syncWithNoExposedSetRemovesAllExternalTools() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);
        when(compositionService.getExposed()).thenReturn(Optional.empty());
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("a", "b"));

        new McpExposedToolService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("a");
        verify(toolSpecService).removeExternalMcpTool("b");
    }
}
