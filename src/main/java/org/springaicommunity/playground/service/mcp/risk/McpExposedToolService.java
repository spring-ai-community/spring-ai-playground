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

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class McpExposedToolService {

    public static final String COMPOSED_TOOLS_PROPERTY = "spring.ai.playground.mcp-server.composed-tools";

    private static final Logger logger = LoggerFactory.getLogger(McpExposedToolService.class);

    private final McpCompositionService compositionService;
    private final McpCompositionToolCallbackProvider compositionProvider;
    private final ToolSpecService toolSpecService;
    private final McpToolHashLedger hashLedger;
    private final McpServerInfoService serverInfoService;
    private final McpClientService clientService;
    private final McpToolRiskEvaluator riskEvaluator;

    public McpExposedToolService(McpCompositionService compositionService,
            McpCompositionToolCallbackProvider compositionProvider, ToolSpecService toolSpecService,
            McpToolHashLedger hashLedger, McpServerInfoService serverInfoService, McpClientService clientService,
            McpToolRiskEvaluator riskEvaluator) {
        this.compositionService = compositionService;
        this.compositionProvider = compositionProvider;
        this.toolSpecService = toolSpecService;
        this.hashLedger = hashLedger;
        this.serverInfoService = serverInfoService;
        this.clientService = clientService;
        this.riskEvaluator = riskEvaluator;
    }

    public record ExposureRequest(ExposureMode mode, List<McpComposition.Member> members, RiskLevel maxRisk,
            Set<String> acknowledgedChanges) {

        public ExposureRequest {
            if (mode == null) throw new IllegalArgumentException("exposure mode must not be null");
            members = members == null ? List.of() : List.copyOf(members);
            if (maxRisk == null) maxRisk = RiskLevel.L5;
            acknowledgedChanges = acknowledgedChanges == null ? Set.of() : Set.copyOf(acknowledgedChanges);
        }

        public ExposureRequest(ExposureMode mode, List<McpComposition.Member> members, RiskLevel maxRisk) {
            this(mode, members, maxRisk, Set.of());
        }
    }

    public synchronized McpComposition apply(ExposureRequest request) {
        boolean managed = this.compositionService.exposedManagedByConfiguration();
        if (!managed) assertMembersWithinCap(request.members(), request.maxRisk());
        this.toolSpecService.setExposureMode(request.mode());
        if (managed) {
            sync();
            return this.compositionService.getExposed().orElse(null);
        }
        return apply(request.members(), request.maxRisk(), request.acknowledgedChanges());
    }

    public boolean awaitsRereview(String serverId, String toolName) {
        return this.hashLedger.get(serverId, toolName)
                .map(McpToolHashLedger.Fingerprint::status)
                .filter(status -> status != McpToolHashLedger.Fingerprint.LifecycleStatus.ACTIVE)
                .isPresent();
    }

    public List<McpServerInfo> composableServers() {
        McpServerInfo builtin = this.serverInfoService.getDefaultMcpServerInfo();
        return this.serverInfoService.read().stream()
                .filter(this.clientService::isConnecting)
                .filter(info -> builtin == null || !info.serverName().equals(builtin.serverName()))
                .toList();
    }

    private void assertMembersWithinCap(List<McpComposition.Member> members, RiskLevel cap) {
        Map<String, McpServerInfo> serversByName = this.serverInfoService.read().stream()
                .collect(Collectors.toMap(McpServerInfo::serverName, Function.identity(),
                        (first, second) -> first));
        Map<String, Optional<List<McpSchema.Tool>>> toolsByServer = new HashMap<>();
        for (McpComposition.Member member : members) {
            McpServerInfo server = serversByName.get(member.serverId());
            if (server == null || !this.clientService.isConnecting(server)) continue;
            Optional<McpSchema.Tool> tool = toolsByServer
                    .computeIfAbsent(member.serverId(), serverName -> this.clientService.getToolListAsOpt(server))
                    .stream().flatMap(List::stream)
                    .filter(candidate -> member.toolName().equals(candidate.name()))
                    .findFirst();
            if (tool.isEmpty()) continue;
            RiskLevel inherent = this.riskEvaluator.evaluateTool(server, tool.get()).inherentLevel();
            if (inherent.ordinal() > cap.ordinal()) {
                throw new IllegalArgumentException("Tool '" + member.toolName() + "' on '" + member.serverId()
                        + "' has inherent risk " + inherent + " above the cap " + cap
                        + " (HITL does not lift the cap)");
            }
        }
    }

    public synchronized McpComposition apply(List<McpComposition.Member> members, RiskLevel maxRiskLevel) {
        return apply(members, maxRiskLevel, null);
    }

    public synchronized McpComposition apply(List<McpComposition.Member> members, RiskLevel maxRiskLevel,
            Set<String> acknowledgedKeys) {
        if (this.compositionService.exposedManagedByConfiguration()) {
            throw new IllegalStateException("Composed tools are managed by configuration ("
                    + COMPOSED_TOOLS_PROPERTY + "); edit the configuration and restart to change them");
        }
        members.forEach(member -> ManagementToolNames.requireNotReserved("Alias", member.exposedAlias()));
        McpComposition exposed = this.compositionService.upsertExposed(members, maxRiskLevel);
        exposed.members().stream()
                .filter(member -> acknowledgedKeys == null || acknowledgedKeys.contains(
                        McpToolHashLedger.Fingerprint.keyOf(member.serverId(), member.toolName())))
                .forEach(member -> this.hashLedger.approveRereview(member.serverId(), member.toolName()));
        sync();
        return exposed;
    }

    public synchronized void sync() {
        if (!this.toolSpecService.exposureMode().includesComposed()) {
            Set<String> current = this.toolSpecService.getExternalMcpToolNames();
            current.forEach(this.toolSpecService::removeExternalMcpTool);
            if (!current.isEmpty()) this.toolSpecService.refreshDefaultMcpTool();
            logger.info("Exposed-tool sync skipped (composed tools off by exposure mode); removed {} external tool(s)",
                    current.size());
            return;
        }
        Optional<McpComposition> exposed = this.compositionService.getExposed();
        ToolCallback[] wrapped = exposed.map(this.compositionProvider::getToolCallbacksFor)
                .orElseGet(() -> new ToolCallback[0]);
        Map<String, Boolean> hitlByName = exposed
                .map(composition -> composition.members().stream().collect(Collectors.toMap(
                        McpComposition.Member::exposedAlias, McpComposition.Member::hitl, (a, b) -> a || b)))
                .orElseGet(Map::of);
        Set<String> desired = Arrays.stream(wrapped)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        this.toolSpecService.getExternalMcpToolNames().stream()
                .filter(name -> needsRemoval(name, desired, hitlByName))
                .forEach(this.toolSpecService::removeExternalMcpTool);
        Set<String> current = this.toolSpecService.getExternalMcpToolNames();
        Arrays.stream(wrapped)
                .filter(callback -> !current.contains(callback.getToolDefinition().name()))
                .forEach(callback -> this.toolSpecService.addExternalMcpTool(callback,
                        hitlByName.getOrDefault(callback.getToolDefinition().name(), false)));
        this.toolSpecService.refreshDefaultMcpTool();
        logger.info("Exposed-tool sync complete: exposedToolCount={}", desired.size());
    }

    private boolean needsRemoval(String name, Set<String> desired, Map<String, Boolean> hitlByName) {
        if (!desired.contains(name)) return true;
        boolean registeredHitl = this.toolSpecService.humanInTheLoopFor(name) != null;
        return registeredHitl != hitlByName.getOrDefault(name, false);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    public void onApplicationReady() {
        sync();
    }
}
