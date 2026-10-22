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
package org.springaicommunity.playground.service.management;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CatalogRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CustomRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationPlan;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.CatalogOverview;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ServerOverview;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ToolOverview;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "mcp";
    public static final String SERVERS_STATUS_URI = "saip://status/servers";

    private static final int CATALOG_LIMIT_DEFAULT = 20;
    private static final int CATALOG_LIMIT_MAX = 100;

    private final McpServerOverviewService overviewService;
    private final McpRegistrationService registrationService;

    public McpManagementContribution(McpServerOverviewService overviewService,
            McpRegistrationService registrationService) {
        this.overviewService = overviewService;
        this.registrationService = registrationService;
    }

    public record NoInput() {
    }

    public record ServersData(List<ServerOverview> servers) {
    }

    public record CatalogSearchInput(
            @Nullable @JsonPropertyDescription("Words to look for in the catalog entry id, name, description, vendor or tags.") String query,
            @Nullable @JsonPropertyDescription("Catalog category, for example DEVELOPER_TOOLS.") String category,
            @Nullable @JsonPropertyDescription("Only entries that offer this transport.") McpTransportType transport,
            @Nullable @JsonPropertyDescription("Maximum entries to return, default 20, at most 100.") Integer limit) {
    }

    public record CatalogData(List<CatalogOverview> entries, boolean truncated, int total) {
    }

    public record PreviewRiskInput(
            @Nullable @JsonPropertyDescription("Catalog entry id. Use this or url.") String catalogId,
            @Nullable @JsonPropertyDescription("Transport of the catalog entry or of the custom server.") McpTransportType transport,
            @Nullable @JsonPropertyDescription("Server URL for a custom server.") String url,
            @Nullable @JsonPropertyDescription("Server name for a custom server.") String name,
            @Nullable @JsonPropertyDescription("Request headers; secret values must be ${ENV_NAME} references.") Map<String, String> headers,
            @Nullable @JsonPropertyDescription("Catalog placeholder values such as a tenant name. Never secrets.") Map<String, String> values) {
    }

    public record RiskData(String server, RiskLevel level, String floorTrigger, Map<String, String> rationale,
                           boolean requiresConfirmation, String commandLine) {
    }

    public record ServerToolsInput(@JsonPropertyDescription("Registered MCP server name.") String server) {
    }

    public record ServerToolsData(String server, List<ToolOverview> tools) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<SyncResourceSpecification> resources() {
        return List.of(ManagementJson.statusResource(SERVERS_STATUS_URI, "servers-status",
                "Registered MCP servers and their connection status",
                "The same view as saip_mcp_list_servers, as JSON, read fresh on every read: status, error, "
                        + "missing environment variables, OAuth authorization URL and risk level per server.",
                () -> new ServersData(this.overviewService.servers())));
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(listServers(), catalogSearch(), previewRisk(), serverTools());
    }

    private ManagementTool listServers() {
        return ManagementTool.named("saip_mcp_list_servers", CATEGORY, NoInput.class).data(ServersData.class)
                .description("List the MCP servers registered in this app with connection status, error, missing "
                        + "environment variables, OAuth authorization URL and server risk level. Servers are "
                        + "registered by people in the app; pick one here, then call saip_mcp_server_tools.")
                .idempotent()
                .handle((input, caller) -> {
                    List<ServerOverview> servers = this.overviewService.servers();
                    return ManagementResult.ok(servers.size() + " server(s).", new ServersData(servers));
                });
    }

    private ManagementTool catalogSearch() {
        return ManagementTool.named("saip_mcp_catalog_search", CATEGORY, CatalogSearchInput.class)
                .data(CatalogData.class)
                .description("Search the built-in catalog of well-known MCP servers. Each entry lists its "
                        + "transports, the environment variables it needs and whether it is already registered.")
                .idempotent()
                .handle((input, caller) -> {
                    List<CatalogOverview> all = this.overviewService.catalog(input.query(), input.category(),
                            input.transport());
                    int limit = input.limit() == null ? CATALOG_LIMIT_DEFAULT
                            : Math.max(1, Math.min(input.limit(), CATALOG_LIMIT_MAX));
                    List<CatalogOverview> page = all.stream().limit(limit).toList();
                    return ManagementResult.ok(all.size() + " catalog entr" + (all.size() == 1 ? "y." : "ies."),
                            new CatalogData(page, all.size() > page.size(), all.size()));
                });
    }

    private ManagementTool previewRisk() {
        return ManagementTool.named("saip_mcp_preview_risk", CATEGORY, PreviewRiskInput.class)
                .data(RiskData.class)
                .description("Compute the risk level a server would get if it were registered, with the reasons. "
                        + "Nothing is saved or connected. Levels L4 and above need extra care.")
                .idempotent()
                .handle((input, caller) -> {
                    RegistrationPlan plan = planOf(input);
                    return ManagementResult.ok("Risk " + plan.risk().level() + ".", new RiskData(
                            plan.info().serverName(), plan.risk().level(), plan.risk().floorTrigger(),
                            plan.risk().rationale(), plan.risk().requiresConfirmation(), plan.commandLine()));
                });
    }

    private ManagementTool serverTools() {
        return ManagementTool.named("saip_mcp_server_tools", CATEGORY, ServerToolsInput.class)
                .data(ServerToolsData.class)
                .description("List the tools a registered MCP server offers with per-tool risk (final and "
                        + "inherent), description poisoning scan result, definition fingerprint status and whether "
                        + "the tool is already exposed on this app's MCP server. Use it before saip_expose_*.")
                .idempotent()
                .handle((input, caller) -> this.overviewService.tools(input.server())
                        .map(tools -> ManagementResult.ok(tools.size() + " tool(s) on '" + input.server() + "'.",
                                (Object) new ServerToolsData(input.server(), tools)))
                        .orElseGet(() -> ManagementResult.failure("MCP server not found: " + input.server()
                                + ". Call saip_mcp_list_servers for the registered names.")));
    }

    private RegistrationPlan planOf(PreviewRiskInput input) {
        if (input.catalogId() != null && !input.catalogId().isBlank()) {
            ManagementRegistrationInputs.requireReferences(input.headers(), input.values());
            return this.registrationService.planCatalog(new CatalogRegistration(input.catalogId(),
                    input.transport(), input.values()), false);
        }
        ManagementRegistrationInputs.requireReferences(input.headers(), input.values());
        return this.registrationService.planCustom(new CustomRegistration(
                input.name() == null || input.name().isBlank() ? "preview" : input.name(),
                input.transport() == null ? McpTransportType.STREAMABLE_HTTP : input.transport(), input.url(), null,
                input.headers(), null, null, null), false);
    }

}
