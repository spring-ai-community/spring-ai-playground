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

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogEntry;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpClientService.StatusEntry;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger;
import org.springaicommunity.playground.service.mcp.risk.McpToolPoisoningScanner;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator.ToolRiskView;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpServerOverviewService {

    private final McpServerInfoService serverInfoService;
    private final McpClientService clientService;
    private final McpToolRiskEvaluator riskEvaluator;
    private final McpCatalogService catalogService;
    private final McpCompositionService compositionService;
    private final McpToolHashLedger hashLedger;

    public McpServerOverviewService(McpServerInfoService serverInfoService, McpClientService clientService,
            McpToolRiskEvaluator riskEvaluator, McpCatalogService catalogService,
            McpCompositionService compositionService, McpToolHashLedger hashLedger) {
        this.serverInfoService = serverInfoService;
        this.clientService = clientService;
        this.riskEvaluator = riskEvaluator;
        this.catalogService = catalogService;
        this.compositionService = compositionService;
        this.hashLedger = hashLedger;
    }

    public record ServerOverview(String name, McpTransportType transport, ServerStatus status, String error,
                                 String authorizationUrl, Set<String> missingEnv, RiskLevel risk, String category,
                                 Set<String> tags, String description, boolean builtIn, boolean connected) {
    }

    public record CatalogOverview(String id, String displayName, String description, String category, int tier,
                                  boolean official, Set<String> trustSignals, List<TransportOverview> transports,
                                  boolean installed) {
    }

    public record TransportOverview(McpTransportType type, List<String> requiredEnv, List<String> placeholders) {
    }

    public record ToolOverview(String name, String description, List<String> parameters, List<String> required,
                               RiskLevel finalRisk, RiskLevel inherentRisk, String floorTrigger,
                               boolean poisoningClean, List<String> poisoningPatterns, String fingerprint,
                               String exposedAs, Boolean exposedWithHitl) {
    }

    public List<ServerOverview> servers() {
        McpServerInfo builtIn = this.serverInfoService.getDefaultMcpServerInfo();
        return this.serverInfoService.read().stream().map(info -> overviewOf(info, builtIn)).toList();
    }

    public Optional<ServerOverview> server(String serverName) {
        McpServerInfo builtIn = this.serverInfoService.getDefaultMcpServerInfo();
        return this.serverInfoService.findByServerName(serverName).map(info -> overviewOf(info, builtIn));
    }

    public List<CatalogOverview> catalog(String query, String category, McpTransportType transport) {
        Set<String> installed = this.serverInfoService.read().stream().map(McpServerInfo::serverName)
                .collect(Collectors.toSet());
        return this.catalogService.search(query, category, transport).stream()
                .map(entry -> catalogOverviewOf(entry, installed)).toList();
    }

    public Optional<List<ToolOverview>> tools(String serverName) {
        Optional<McpServerInfo> server = this.serverInfoService.findByServerName(serverName);
        if (server.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<McpSchema.Tool>> tools = this.clientService.getToolListAsOpt(server.get());
        if (tools.isEmpty()) {
            return Optional.of(List.of());
        }
        Map<String, McpComposition.Member> exposed = this.compositionService.getExposed()
                .map(composition -> composition.members().stream()
                        .filter(member -> member.serverId().equals(serverName))
                        .collect(Collectors.toMap(McpComposition.Member::toolName,
                                member -> member, (first, second) -> first)))
                .orElse(Map.of());
        return Optional.of(tools.get().stream().map(tool -> toolOverviewOf(server.get(), tool, exposed.get(tool.name())))
                .toList());
    }

    private ServerOverview overviewOf(McpServerInfo info, McpServerInfo builtIn) {
        boolean isBuiltIn = builtIn != null && builtIn.serverName().equals(info.serverName());
        StatusEntry status = this.clientService.getStatus(info);
        return new ServerOverview(info.serverName(), info.mcpTransportType(), status.status(), status.error(),
                status.authorizationUrl(), this.clientService.findMissingEnv(info),
                this.riskEvaluator.evaluateServer(info).level(), info.category(), info.tags(), info.description(),
                isBuiltIn, this.clientService.isConnecting(info));
    }

    private CatalogOverview catalogOverviewOf(McpCatalogEntry entry, Set<String> installed) {
        List<TransportOverview> transports = entry.transports().stream()
                .map(spec -> new TransportOverview(spec.type(),
                        spec.requiredEnv() == null ? List.of() : spec.requiredEnv(),
                        spec.urlPlaceholders() == null ? List.of() : spec.urlPlaceholders()))
                .toList();
        return new CatalogOverview(entry.id(), entry.displayName(), entry.description(), entry.category(),
                entry.tier(), entry.vendorOfficial(), entry.trustSignals(), transports,
                installed.contains(McpCatalogService.stripOsSuffix(entry.id())));
    }

    private ToolOverview toolOverviewOf(McpServerInfo server, McpSchema.Tool tool, McpComposition.Member exposed) {
        ToolRiskView risk = this.riskEvaluator.evaluateTool(server, tool);
        McpToolPoisoningScanner.ScanResult scan = risk.poisoningResult();
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        List<String> parameters = schema.get("properties") instanceof Map<?, ?> properties
                ? properties.keySet().stream().map(String::valueOf).sorted().toList() : List.of();
        List<String> required = schema.get("required") instanceof List<?> names
                ? names.stream().map(String::valueOf).toList() : List.of();
        String fingerprint = this.hashLedger.get(server.serverName(), tool.name())
                .map(found -> found.status().name()).orElse("NEW");
        return new ToolOverview(tool.name(), tool.description(), parameters, required, risk.finalLevel(),
                risk.inherentLevel(), risk.floorTrigger(), scan == null || scan.clean(),
                scan == null ? List.of() : scan.matches().stream().map(match -> match.pattern().name()).toList(),
                fingerprint, exposed == null ? null : exposed.exposedAlias(),
                exposed == null ? null : exposed.hitl());
    }

}
