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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.SharedDataReader;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionToolCallbackProvider;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ChatToolResolverTest {

    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final McpCompositionToolCallbackProvider compositionProvider =
            mock(McpCompositionToolCallbackProvider.class);
    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final McpServerInfoService mcpServerInfoService = mock(McpServerInfoService.class);
    private final SharedDataReader<List<McpServerInfo>> reader = mock(SharedDataReader.class);
    private final McpServerInfo builtin = server("spring-ai-playground");
    private final McpServerInfo remote = server("everything");
    private final ToolCallback composed = callback("composedTool");
    private final ToolCallback remoteEcho = callback("echo");
    private final ToolCallback builtinWeather = callback("getWeather");
    private final ToolCallback builtinTime = callback("getCurrentTime");
    private ChatToolResolver resolver;

    private static McpServerInfo server(String name) {
        return new McpServerInfo(McpTransportType.SSE, name, "", 1L, 1L, null);
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static ToolSpec spec(String id, String name, ToolCallback callback, boolean draft) {
        return new ToolSpec(id, name, "", List.of(), List.of(), "return 1;", ToolSpec.CodeType.Javascript, callback)
                .withDraft(draft);
    }

    @BeforeEach
    void wire() {
        when(reader.read()).thenReturn(List.of(builtin, remote));
        when(mcpServerInfoService.getDefaultMcpServerInfo()).thenReturn(builtin);
        when(compositionProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{composed});
        List<ToolSpec> specs = List.of(
                spec("id-weather", "getWeather", builtinWeather, false),
                spec("id-time", "getCurrentTime", builtinTime, false),
                spec("id-draft", "draftTool", callback("draftTool"), true));
        when(toolSpecService.getToolSpecList()).thenReturn(specs);
        when(toolSpecService.getExternalToolSpecs()).thenReturn(List.of());
        ToolCallbackProvider builtinProvider = mock(ToolCallbackProvider.class);
        when(builtinProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{builtinWeather, builtinTime});
        ToolCallbackProvider remoteProvider = mock(ToolCallbackProvider.class);
        when(remoteProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{remoteEcho});
        when(mcpClientService.buildToolCallbackProviders(any(McpServerInfo[].class))).thenAnswer(invocation -> {
            Object first = invocation.getArgument(0);
            McpServerInfo info = first instanceof McpServerInfo[] array ? array[0] : (McpServerInfo) first;
            return List.of(info == builtin ? builtinProvider : remoteProvider);
        });
        this.resolver = new ChatToolResolver(toolSpecService, compositionProvider, mcpClientService,
                mcpServerInfoService, reader);
    }

    @Test
    void externalServersLeaveOutTheBuiltInOne() {
        assertThat(resolver.externalServers()).containsExactly(remote);
        assertThat(resolver.selectedServers(ChatToolPreferences.defaults())).isEmpty();
        assertThat(resolver.selectedServers(new ChatToolPreferences(true, Set.of(), null,
                Map.of(McpTransportType.SSE, List.of("everything")), ReasoningEffort.DEFAULT, false)))
                .containsExactly(remote);
        assertThat(resolver.searchablePoolSize()).isEqualTo(3);
    }

    @Test
    void dynamicPreferencesReachEveryPublishedToolPlusTheSelectedServers() {
        ChatToolPreferences preferences = new ChatToolPreferences(false, Set.of(), null,
                Map.of(McpTransportType.SSE, List.of("everything")), ReasoningEffort.DEFAULT, true);

        assertThat(resolver.resolve(preferences)).containsExactly(builtinWeather, builtinTime, composed, remoteEcho);
    }

    @Test
    void manualPreferencesKeepOnlyTheTickedBuiltInsNextToTheSelectedServers() {
        ChatToolPreferences preferences = new ChatToolPreferences(true, Set.of("id-time"), null,
                Map.of(McpTransportType.SSE, List.of("everything")), ReasoningEffort.DEFAULT, false);

        assertThat(resolver.resolve(preferences)).containsExactly(remoteEcho, builtinTime);
        assertThat(resolver.resolve(new ChatToolPreferences(false, Set.of("id-time"), null, Map.of(),
                ReasoningEffort.DEFAULT, false))).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
