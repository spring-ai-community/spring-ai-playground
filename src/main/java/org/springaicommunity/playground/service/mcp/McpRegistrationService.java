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

import org.springaicommunity.playground.service.mcp.McpServerInfoService.McpServerRegistration;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ServerOverview;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogEntry;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.TestConnectionResult;
import org.springaicommunity.playground.service.mcp.client.McpConnectionSpec;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationInput;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.mcp.risk.ServerSelection;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class McpRegistrationService {

    private final McpServerInfoService serverInfoService;
    private final McpServerOverviewService overviewService;
    private final McpCatalogService catalogService;
    private final McpClientService clientService;
    private final McpRegistrationRiskPreview riskPreview;
    private final McpExposureService exposureService;
    private final McpExposedToolService exposedToolService;
    private final McpCompositionService compositionService;

    public McpRegistrationService(McpServerInfoService serverInfoService, McpServerOverviewService overviewService,
            McpCatalogService catalogService, McpClientService clientService,
            McpRegistrationRiskPreview riskPreview, McpExposureService exposureService,
            McpExposedToolService exposedToolService, McpCompositionService compositionService) {
        this.serverInfoService = serverInfoService;
        this.overviewService = overviewService;
        this.catalogService = catalogService;
        this.clientService = clientService;
        this.riskPreview = riskPreview;
        this.exposureService = exposureService;
        this.exposedToolService = exposedToolService;
        this.compositionService = compositionService;
    }

    public record CatalogRegistration(String catalogId, McpTransportType transport, Map<String, String> values) {

        public CatalogRegistration {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    public record CustomRegistration(String name, McpTransportType transport, String url, String endpoint,
                                     Map<String, String> headers, String description, String category,
                                     Set<String> tags) {

        public CustomRegistration {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    public record RegistrationPlan(McpServerInfo info, RegistrationPreview risk, String commandLine) {
    }

    public record RegistrationResult(ServerOverview server, int toolCount, RegistrationPreview risk) {
    }

    public record RemovalResult(String server, int exposedToolsRemoved) {
    }

    public RegistrationPlan planCatalog(CatalogRegistration registration) {
        return planCatalog(registration, true);
    }

    public RegistrationPlan planCatalog(CatalogRegistration registration, boolean registering) {
        McpCatalogEntry entry = this.catalogService.findById(registration.catalogId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown catalog entry '"
                        + registration.catalogId() + "'. Search the catalog for a valid id."));
        McpCatalogEntry.TransportSpec transport = entry.transports().stream()
                .filter(spec -> registration.transport() == null || spec.type() == registration.transport())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Catalog entry '" + entry.id()
                        + "' has no " + registration.transport() + " transport."));
        McpServerInfo info = this.catalogService.instantiate(entry, transport, registration.values());
        return planOf(info, registering);
    }

    public RegistrationPlan planCustom(CustomRegistration registration) {
        return planCustom(registration, true);
    }

    public RegistrationPlan planCustom(CustomRegistration registration, boolean registering) {
        if (registration.transport() != McpTransportType.STREAMABLE_HTTP
                && registration.transport() != McpTransportType.SSE) {
            throw new IllegalArgumentException("Only STREAMABLE_HTTP and SSE servers can be registered with a "
                    + "custom definition. Use a catalog entry for stdio servers.");
        }
        McpServerInfoService.serverNameError(registration.name()).ifPresent(error -> {
            throw new IllegalArgumentException(error);
        });
        if (registration.url() == null || registration.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        McpConnectionSpec spec = McpConnectionSpec.http(registration.transport(), registration.url().trim(),
                registration.endpoint() == null ? "" : registration.endpoint().trim(), registration.headers(), null);
        long now = System.currentTimeMillis();
        McpServerInfo info = new McpServerInfo(registration.transport(), registration.name(),
                registration.description() == null ? "" : registration.description(), now, now, spec.toJson(),
                registration.category(), registration.tags());
        return planOf(info, registering);
    }

    public RegistrationResult register(RegistrationPlan plan) {
        McpServerInfo info = plan.info();
        McpServerInfo saved = this.serverInfoService.saveAndConnect(new McpServerRegistration(null, null,
                info.mcpTransportType(), info.serverName(), info.description(), info.category(), info.tags(),
                info.connectionAsJson()));
        ServerOverview overview = this.overviewService.server(saved.serverName()).orElseThrow();
        int toolCount = this.clientService.getToolListAsOpt(saved).map(List::size).orElse(0);
        return new RegistrationResult(overview, toolCount, plan.risk());
    }

    public TestConnectionResult test(RegistrationPlan plan) {
        return this.clientService.testConnection(plan.info());
    }

    public void requireRemovable(String serverName) {
        this.serverInfoService.deleteBlocker(serverName).ifPresent(blocker -> {
            throw new IllegalStateException(blocker);
        });
    }

    public RemovalResult remove(String serverName) {
        requireRemovable(serverName);
        int removed = 0;
        if (!this.compositionService.exposedManagedByConfiguration()) {
            removed = (int) this.compositionService.getExposed()
                    .map(composition -> composition.members().stream()
                            .filter(member -> member.serverId().equals(serverName)).count()).orElse(0L).longValue();
            if (removed > 0) {
                this.exposureService.commit(ExposureChange.of(ExposureChange.Operation.REMOVE,
                        List.of(ServerSelection.allTools(serverName, null))));
            }
        }
        this.serverInfoService.delete(serverName);
        return new RemovalResult(serverName, removed);
    }

    public ServerOverview reconnect(String serverName) {
        McpServerInfo builtIn = this.serverInfoService.getDefaultMcpServerInfo();
        if (builtIn != null && builtIn.serverName().equals(serverName)) {
            throw new IllegalStateException("The built-in server cannot be reconnected.");
        }
        McpServerInfo info = this.serverInfoService.findByServerName(serverName)
                .orElseThrow(() -> new IllegalStateException("MCP server not found: " + serverName));
        try {
            this.clientService.startMcpClient(info);
        } catch (RuntimeException e) {
            return this.overviewService.server(serverName).orElseThrow();
        }
        this.exposedToolService.sync();
        return this.overviewService.server(serverName).orElseThrow();
    }

    private RegistrationPlan planOf(McpServerInfo info, boolean registering) {
        McpServerRegistration registration = new McpServerRegistration(null, null, info.mcpTransportType(),
                info.serverName(), info.description(), info.category(), info.tags(), info.connectionAsJson());
        this.serverInfoService.validateRegistration(registration);
        if (registering) {
            this.serverInfoService.requireFreeName(registration);
        }
        McpConnectionSpec spec = McpConnectionSpec.from(info.mcpTransportType(), info.connectionAsJson());
        RegistrationPreview risk = this.riskPreview.preview(new RegistrationInput(info.serverName(),
                info.mcpTransportType(), spec.url(), spec.headers(), spec.oauth() != null));
        String commandLine = info.mcpTransportType() == McpTransportType.STDIO
                ? (spec.command() + " " + String.join(" ", spec.args())).trim() : null;
        return new RegistrationPlan(info, risk, commandLine);
    }

}
