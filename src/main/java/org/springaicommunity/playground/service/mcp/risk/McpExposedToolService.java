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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpExposedToolService {

    private static final Logger logger = LoggerFactory.getLogger(McpExposedToolService.class);

    private final McpCompositionService compositionService;
    private final McpCompositionToolCallbackProvider compositionProvider;
    private final ToolSpecService toolSpecService;

    public McpExposedToolService(McpCompositionService compositionService,
            McpCompositionToolCallbackProvider compositionProvider, ToolSpecService toolSpecService) {
        this.compositionService = compositionService;
        this.compositionProvider = compositionProvider;
        this.toolSpecService = toolSpecService;
    }

    public synchronized McpComposition apply(List<McpComposition.Member> members, RiskLevel maxRiskLevel) {
        McpComposition exposed = this.compositionService.upsertExposed(members, maxRiskLevel);
        sync();
        return exposed;
    }

    // Offline servers' tools don't resolve to a callback, so they register later (re-apply or restart).
    public synchronized void sync() {
        if (!this.toolSpecService.exposureMode().includesComposed()) {
            Set<String> current = this.toolSpecService.getExternalMcpToolNames();
            current.forEach(this.toolSpecService::removeExternalMcpTool);
            if (!current.isEmpty()) this.toolSpecService.refreshDefaultMcpTool();
            logger.info("Exposed-tool sync skipped (composed tools off by exposure mode); removed {} external tool(s)",
                    current.size());
            return;
        }
        ToolCallback[] wrapped = this.compositionService.getExposed()
                .map(this.compositionProvider::getToolCallbacksFor)
                .orElseGet(() -> new ToolCallback[0]);
        Set<String> desired = Arrays.stream(wrapped)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        Set<String> current = this.toolSpecService.getExternalMcpToolNames();
        current.stream().filter(name -> !desired.contains(name)).forEach(this.toolSpecService::removeExternalMcpTool);
        Arrays.stream(wrapped)
                .filter(callback -> !current.contains(callback.getToolDefinition().name()))
                .forEach(this.toolSpecService::addExternalMcpTool);
        this.toolSpecService.refreshDefaultMcpTool();
        logger.info("Exposed-tool sync complete: exposedToolCount={}", desired.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        sync();
    }
}
