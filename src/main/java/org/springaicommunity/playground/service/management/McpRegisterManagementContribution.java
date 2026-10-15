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
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CatalogRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CustomRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationPlan;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationResult;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RemovalResult;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ServerOverview;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpClientService.TestConnectionResult;
import org.springaicommunity.playground.service.mcp.client.McpConnectionSpec;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpRegisterManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "mcp-register";

    private final McpRegistrationService registrationService;

    public McpRegisterManagementContribution(McpRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    public record CatalogInput(
            @JsonPropertyDescription("Catalog entry id from saip_mcp_catalog_search.") String catalogId,
            @Nullable @JsonPropertyDescription("Transport to use when the entry offers several.") McpTransportType transport,
            @Nullable @JsonPropertyDescription("Values for URL placeholders such as a tenant name. Never secrets: secrets come from environment variables on the machine that runs this app.") Map<String, String> values) {
    }

    public record CustomInput(
            @JsonPropertyDescription("Unique server name: letters, digits, '.', '_' and '-'.") String name,
            @JsonPropertyDescription("STREAMABLE_HTTP or SSE.") McpTransportType transport,
            @JsonPropertyDescription("Base URL of the server.") String url,
            @Nullable @JsonPropertyDescription("Endpoint path, for example /mcp.") String endpoint,
            @Nullable @JsonPropertyDescription("Request headers. Secret values must be ${ENV_NAME} references, for example Authorization: Bearer ${MY_TOKEN}.") Map<String, String> headers,
            @Nullable String description, @Nullable String category, @Nullable Set<String> tags) {
    }

    public record TestInput(
            @Nullable @JsonPropertyDescription("Catalog entry id. Use this or name and url.") String catalogId,
            @Nullable McpTransportType transport, @Nullable Map<String, String> values,
            @Nullable String name, @Nullable String url, @Nullable String endpoint,
            @Nullable Map<String, String> headers) {
    }

    public record RemoveInput(@JsonPropertyDescription("Registered MCP server name.") String server) {
    }

    public record ReconnectInput(@JsonPropertyDescription("Registered MCP server name.") String server) {
    }

    public record RegistrationData(ServerOverview server, int toolCount, String riskLevel, String riskFloorTrigger,
                                   String nextStep) {
    }

    public record TestData(boolean connected, int toolCount, String error) {
    }

    public record RemovalData(String server, int exposedToolsRemoved) {
    }

    public record ReconnectData(ServerOverview server, String nextStep) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(testConnection(), addFromCatalog(), addCustom(), removeServer(), reconnect());
    }

    private ManagementTool testConnection() {
        return ManagementTool.named("saip_mcp_test_connection", CATEGORY, TestInput.class).data(TestData.class)
                .description("Connect once to a catalog server or a custom URL to check that it works and count "
                        + "its tools. Nothing is registered. It contacts the server, and for a stdio catalog "
                        + "entry it starts the command.")
                .openWorld()
                .approval(ApprovalLevel.WRITE, input -> {
                    RegistrationPlan plan = testPlanOf(input);
                    return approvalFor("Test connection to " + describe(plan), plan, ApprovalLevel.WRITE);
                })
                .handle((input, caller) -> {
                    TestConnectionResult result = this.registrationService.test(testPlanOf(input));
                    String message = result.ok() ? "Connected, " + result.toolCount() + " tool(s)."
                            : "Connection failed: " + ManagementSecrets.maskText(result.error());
                    return new ManagementResult(true, message, false, false, List.of(),
                            new TestData(result.ok(), result.toolCount(),
                                    ManagementSecrets.maskText(result.error())));
                });
    }

    private ManagementTool addFromCatalog() {
        return ManagementTool.named("saip_mcp_add_from_catalog", CATEGORY, CatalogInput.class)
                .data(RegistrationData.class)
                .description("Register a catalog MCP server and connect to it. The connection status tells what "
                        + "is left to do: missing environment variables must be set by a person on the machine "
                        + "that runs this app, OAuth servers need a person to open the authorization URL.")
                .openWorld()
                .approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    RegistrationPlan plan = catalogPlanOf(input);
                    return approvalFor("Register MCP server '" + plan.info().serverName() + "' from the catalog: "
                            + describe(plan), plan, ApprovalLevel.DESTRUCTIVE);
                })
                .handle((input, caller) -> registered(this.registrationService.register(catalogPlanOf(input))));
    }

    private ManagementTool addCustom() {
        return ManagementTool.named("saip_mcp_add_custom", CATEGORY, CustomInput.class)
                .data(RegistrationData.class)
                .description("Register an HTTP or SSE MCP server from its URL and connect to it. Secret header "
                        + "values must be ${ENV_NAME} references to environment variables; literal secrets are "
                        + "refused. stdio servers cannot be registered this way.")
                .openWorld()
                .approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    RegistrationPlan plan = customPlanOf(input);
                    return approvalFor("Register MCP server '" + plan.info().serverName() + "': "
                            + describe(plan), plan, ApprovalLevel.DESTRUCTIVE);
                })
                .handle((input, caller) -> registered(this.registrationService.register(customPlanOf(input))));
    }

    private ManagementTool removeServer() {
        return ManagementTool.named("saip_mcp_remove_server", CATEGORY, RemoveInput.class)
                .data(RemovalData.class)
                .description("Disconnect and delete a registered MCP server. Tools it had exposed on this app's "
                        + "MCP server are removed too.")
                .destructive(input -> {
                    this.registrationService.requireRemovable(input.server());
                    return "Remove MCP server '" + input.server() + "' and any tools it exposes";
                })
                .handle((input, caller) -> {
                    RemovalResult result = this.registrationService.remove(input.server());
                    return ManagementResult.persisted("Removed '" + result.server() + "'"
                            + (result.exposedToolsRemoved() > 0
                                    ? " and " + result.exposedToolsRemoved() + " exposed tool(s)." : "."),
                            new RemovalData(result.server(), result.exposedToolsRemoved()));
                });
    }

    private ManagementTool reconnect() {
        return ManagementTool.named("saip_mcp_reconnect", CATEGORY, ReconnectInput.class)
                .data(ReconnectData.class)
                .description("Try to connect a registered MCP server again, for example after an environment "
                        + "variable was set or an OAuth authorization was completed.")
                .openWorld()
                .write(input -> "Reconnect MCP server '" + input.server() + "'")
                .handle((input, caller) -> {
                    ServerOverview server = this.registrationService.reconnect(input.server());
                    return ManagementResult.ok("'" + server.name() + "' is " + server.status() + ".",
                            new ReconnectData(server, nextStep(server)));
                });
    }

    private RegistrationPlan catalogPlanOf(CatalogInput input) {
        ManagementRegistrationInputs.requireReferences(null, input.values());
        return this.registrationService.planCatalog(new CatalogRegistration(input.catalogId(), input.transport(),
                input.values()));
    }

    private RegistrationPlan customPlanOf(CustomInput input) {
        ManagementRegistrationInputs.requireReferences(input.headers(), null);
        return this.registrationService.planCustom(new CustomRegistration(input.name(), input.transport(),
                input.url(), input.endpoint(), input.headers(), input.description(), input.category(),
                input.tags()));
    }

    private RegistrationPlan testPlanOf(TestInput input) {
        ManagementRegistrationInputs.requireReferences(input.headers(), input.values());
        if (input.catalogId() != null && !input.catalogId().isBlank()) {
            return this.registrationService.planCatalog(new CatalogRegistration(input.catalogId(),
                    input.transport(), input.values()), false);
        }
        return this.registrationService.planCustom(new CustomRegistration(
                input.name() == null || input.name().isBlank() ? "connection-test" : input.name(),
                input.transport() == null ? McpTransportType.STREAMABLE_HTTP : input.transport(), input.url(),
                input.endpoint(), input.headers(), null, null, null), false);
    }

    private static ManagementApproval approvalFor(String summary, RegistrationPlan plan, ApprovalLevel level) {
        String withRisk = summary + " (risk " + plan.risk().level() + ")";
        ManagementApproval approval = plan.commandLine() != null
                ? new ManagementApproval(ApprovalLevel.DESTRUCTIVE, withRisk + " Runs the command: "
                        + plan.commandLine(), false)
                : new ManagementApproval(level, withRisk, false);
        return plan.risk().requiresConfirmation() ? approval.markHighRisk() : approval;
    }

    private static String describe(RegistrationPlan plan) {
        if (plan.commandLine() != null) {
            return "command " + plan.commandLine();
        }
        McpConnectionSpec spec = McpConnectionSpec.from(plan.info().mcpTransportType(),
                plan.info().connectionAsJson());
        return plan.info().mcpTransportType() + " " + spec.url() + (spec.endpoint() == null ? "" : spec.endpoint());
    }

    private ManagementResult registered(RegistrationResult result) {
        ServerOverview server = result.server();
        String nextStep = nextStep(server);
        String message = "Registered '" + server.name() + "': " + server.status()
                + (server.status() == ServerStatus.OK ? ", " + result.toolCount() + " tool(s)." : ".");
        return ManagementResult.persisted(message, new RegistrationData(server, result.toolCount(),
                result.risk().level().name(), result.risk().floorTrigger(), nextStep));
    }

    private static String nextStep(ServerOverview server) {
        return switch (server.status()) {
            case OK -> "Call saip_mcp_server_tools to see its tools, then expose the ones you need.";
            case MISSING_CONFIG -> "Ask a person to set these environment variables on the machine that runs "
                    + "this app: " + String.join(", ", server.missingEnv()) + ". Then call saip_mcp_reconnect.";
            case AWAITING_AUTHORIZATION -> "Ask a person to open " + server.authorizationUrl() + " to authorize "
                    + "access, then call saip_mcp_reconnect.";
            case ERROR -> "The connection failed: " + ManagementSecrets.maskText(server.error());
            case OFFLINE -> "The server is not connected. Call saip_mcp_reconnect.";
        };
    }

}
