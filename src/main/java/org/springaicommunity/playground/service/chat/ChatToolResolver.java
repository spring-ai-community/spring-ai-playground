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

import org.springaicommunity.playground.service.SharedDataReader;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionToolCallbackProvider;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ChatToolResolver {

    private final ToolSpecService toolSpecService;
    private final McpCompositionToolCallbackProvider compositionProvider;
    private final McpClientService mcpClientService;
    private final McpServerInfoService mcpServerInfoService;
    private final SharedDataReader<List<McpServerInfo>> mcpServerInfosReader;

    public ChatToolResolver(ToolSpecService toolSpecService, McpCompositionToolCallbackProvider compositionProvider,
            McpClientService mcpClientService, McpServerInfoService mcpServerInfoService,
            SharedDataReader<List<McpServerInfo>> mcpServerInfosReader) {
        this.toolSpecService = toolSpecService;
        this.compositionProvider = compositionProvider;
        this.mcpClientService = mcpClientService;
        this.mcpServerInfoService = mcpServerInfoService;
        this.mcpServerInfosReader = mcpServerInfosReader;
    }

    public List<McpServerInfo> externalServers() {
        McpServerInfo builtin = this.mcpServerInfoService.getDefaultMcpServerInfo();
        String builtinName = builtin == null ? null : builtin.serverName();
        return this.mcpServerInfosReader.read().stream()
                .filter(info -> builtinName == null || !builtinName.equals(info.serverName()))
                .toList();
    }

    public List<McpServerInfo> selectedServers(ChatToolPreferences preferences) {
        if (preferences == null || preferences.mcpServerNames() == null || preferences.mcpServerNames().isEmpty())
            return List.of();
        return externalServers().stream()
                .filter(info -> preferences.mcpServerNames().getOrDefault(info.mcpTransportType(), List.of())
                        .contains(info.serverName()))
                .toList();
    }

    public long searchablePoolSize() {
        long authored = this.toolSpecService.getToolSpecList().stream().filter(spec -> !spec.draft()).count();
        return authored + this.compositionProvider.getToolCallbacks().length;
    }

    public List<ToolCallback> dynamicToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        this.toolSpecService.getToolSpecList().stream().filter(spec -> !spec.draft())
                .map(ToolSpec::toolCallback).filter(Objects::nonNull).forEach(callbacks::add);
        Arrays.stream(this.compositionProvider.getToolCallbacks()).forEach(callbacks::add);
        return callbacks;
    }

    public List<ToolCallback> resolve(ChatToolPreferences preferences) {
        ChatToolPreferences effective = preferences == null ? ChatToolPreferences.defaults() : preferences;
        List<ToolCallback> serverCallbacks = callbacksOf(selectedServers(effective));
        if (effective.dynamicTools()) {
            List<ToolCallback> callbacks = new ArrayList<>(dynamicToolCallbacks());
            callbacks.addAll(serverCallbacks);
            return callbacks;
        }
        List<ToolCallback> callbacks = new ArrayList<>(serverCallbacks);
        if (effective.useBuiltinMcp()) {
            McpServerInfo builtin = this.mcpServerInfoService.getDefaultMcpServerInfo();
            if (builtin != null) {
                Set<String> selectedNames = selectedToolNames(effective);
                callbacksOf(List.of(builtin)).stream()
                        .filter(callback -> callback.getToolDefinition() != null
                                && selectedNames.contains(callback.getToolDefinition().name()))
                        .forEach(callbacks::add);
            }
        }
        return callbacks;
    }

    private Set<String> selectedToolNames(ChatToolPreferences preferences) {
        Set<String> ids = preferences.exposedToolIds() == null ? Set.of() : preferences.exposedToolIds();
        Set<String> names = new LinkedHashSet<>();
        for (ToolSpec spec : this.toolSpecService.getToolSpecList())
            if (ids.contains(spec.toolId())) names.add(spec.name());
        for (ToolSpec spec : this.toolSpecService.getExternalToolSpecs())
            if (ids.contains(spec.toolId())) names.add(spec.name());
        return names;
    }

    private List<ToolCallback> callbacksOf(List<McpServerInfo> servers) {
        return servers.stream()
                .map(this.mcpClientService::buildToolCallbackProviders).flatMap(List::stream)
                .map(ToolCallbackProvider::getToolCallbacks).flatMap(Arrays::stream).toList();
    }
}
