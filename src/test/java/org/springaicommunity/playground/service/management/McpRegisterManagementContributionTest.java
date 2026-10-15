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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CatalogRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CustomRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationPlan;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationResult;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ServerOverview;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpRegisterManagementContributionTest {

    private final McpRegistrationService registrationService = mock(McpRegistrationService.class);
    private final McpRegisterManagementContribution contribution =
            new McpRegisterManagementContribution(registrationService);

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static RegistrationPlan httpPlan(RiskLevel level) {
        McpServerInfo info = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "wiki", "d", 0L, 0L,
                "{\"url\":\"https://mcp.example.com\",\"endpoint\":\"/mcp\"}");
        return new RegistrationPlan(info, new RegistrationPreview(level, null, Map.of(), false), null);
    }

    private static ServerOverview overview(ServerStatus status, Set<String> missing, String authorizationUrl,
            String error) {
        return new ServerOverview("wiki", McpTransportType.STREAMABLE_HTTP, status, error, authorizationUrl,
                missing, RiskLevel.L1, "CUSTOM", Set.of(), "d", false, status == ServerStatus.OK);
    }

    @Test
    void literalSecretsInHeadersAreRefusedBeforeAnythingHappens() {
        Map<String, Object> arguments = Map.of("name", "wiki", "transport", "STREAMABLE_HTTP",
                "url", "https://mcp.example.com", "headers", Map.of("Authorization", "Bearer abc123456789"));

        assertThatThrownBy(() -> tool("saip_mcp_add_custom").approvalFor(arguments))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("${ENV_NAME}");
        verify(registrationService, never()).planCustom(any());
        verify(registrationService, never()).register(any());
    }

    @Test
    void aRefusedRegistrationNeverReachesTheApprovalPrompt() {
        when(registrationService.planCustom(any())).thenThrow(new IllegalArgumentException("own MCP endpoint"));

        assertThatThrownBy(() -> tool("saip_mcp_add_custom").approvalFor(Map.of("name", "self",
                "transport", "STREAMABLE_HTTP", "url", "http://127.0.0.1:58311")))
                .hasMessageContaining("own MCP endpoint");
    }

    @Test
    void environmentReferencesInHeadersAreAccepted() {
        when(registrationService.planCustom(any())).thenReturn(httpPlan(RiskLevel.L2));
        Map<String, Object> arguments = Map.of("name", "wiki", "transport", "STREAMABLE_HTTP",
                "url", "https://mcp.example.com", "headers", Map.of("Authorization", "Bearer ${WIKI_TOKEN}"));

        ManagementApproval approval = tool("saip_mcp_add_custom").approvalFor(arguments);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.highRisk()).isFalse();
        assertThat(approval.summary()).contains("Register MCP server 'wiki'", "https://mcp.example.com/mcp",
                "risk L2");
    }

    @Test
    void highRiskServersMarkTheApprovalAsHighRisk() {
        McpServerInfo info = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "wiki", "d", 0L, 0L, "{}");
        when(registrationService.planCustom(any())).thenReturn(new RegistrationPlan(info,
                new RegistrationPreview(RiskLevel.L4, "unknown host", Map.of(), false), null));

        ManagementApproval approval = tool("saip_mcp_add_custom").approvalFor(Map.of("name", "wiki",
                "transport", "SSE", "url", "https://mcp.example.com"));

        assertThat(approval.highRisk()).isTrue();
    }

    @Test
    void catalogStdioEntriesShowTheCommandAndNeedFullApproval() {
        McpServerInfo info = new McpServerInfo(McpTransportType.STDIO, "everything", "d", 0L, 0L,
                "{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-everything\"]}");
        when(registrationService.planCatalog(any(CatalogRegistration.class), anyBoolean())).thenReturn(new RegistrationPlan(info,
                new RegistrationPreview(RiskLevel.L2, null, Map.of(), false),
                "npx -y @modelcontextprotocol/server-everything"));

        ManagementApproval approval = tool("saip_mcp_test_connection").approvalFor(Map.of("catalogId", "everything"));

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.summary()).contains("Runs the command: npx -y @modelcontextprotocol/server-everything");
    }

    @Test
    void connectionTestOfAnHttpServerNeedsOnlyWriteApproval() {
        when(registrationService.planCustom(any(CustomRegistration.class), anyBoolean())).thenReturn(httpPlan(RiskLevel.L1));

        ManagementApproval approval = tool("saip_mcp_test_connection").approvalFor(Map.of("name", "wiki",
                "url", "https://mcp.example.com"));

        assertThat(approval.level()).isEqualTo(ApprovalLevel.WRITE);
    }

    @Test
    void registrationResultsTellWhatIsLeftToDoPerStatus() {
        RegistrationPlan plan = httpPlan(RiskLevel.L1);
        when(registrationService.planCatalog(any(CatalogRegistration.class))).thenReturn(plan);
        Map<String, Object> arguments = Map.of("catalogId", "wiki");

        when(registrationService.register(plan)).thenReturn(new RegistrationResult(
                overview(ServerStatus.OK, Set.of(), null, null), 3, plan.risk()));
        ManagementResult connected = tool("saip_mcp_add_from_catalog").handle(arguments, ManagementCaller.ANONYMOUS);
        assertThat(connected.message()).contains("3 tool(s)");
        assertThat(((McpRegisterManagementContribution.RegistrationData) connected.data()).nextStep())
                .contains("saip_mcp_server_tools");
        assertThat(connected.persisted()).isTrue();

        when(registrationService.register(plan)).thenReturn(new RegistrationResult(
                overview(ServerStatus.MISSING_CONFIG, Set.of("WIKI_KEY"), null, null), 0, plan.risk()));
        ManagementResult missing = tool("saip_mcp_add_from_catalog").handle(arguments, ManagementCaller.ANONYMOUS);
        assertThat(((McpRegisterManagementContribution.RegistrationData) missing.data()).nextStep())
                .contains("WIKI_KEY").contains("saip_mcp_reconnect");

        when(registrationService.register(plan)).thenReturn(new RegistrationResult(
                overview(ServerStatus.AWAITING_AUTHORIZATION, Set.of(), "http://localhost/oauth", null), 0,
                plan.risk()));
        ManagementResult authorization = tool("saip_mcp_add_from_catalog").handle(arguments,
                ManagementCaller.ANONYMOUS);
        assertThat(((McpRegisterManagementContribution.RegistrationData) authorization.data()).nextStep())
                .contains("http://localhost/oauth");
    }

    @Test
    void errorsInReconnectResultsAreMasked() {
        when(registrationService.reconnect("wiki")).thenReturn(overview(ServerStatus.ERROR, Set.of(), null,
                "401 Authorization: Bearer abcdefghijklmnop"));

        ManagementResult result = tool("saip_mcp_reconnect").handle(Map.of("server", "wiki"),
                ManagementCaller.ANONYMOUS);

        assertThat(((McpRegisterManagementContribution.ReconnectData) result.data()).nextStep())
                .doesNotContain("abcdefghijklmnop");
    }

    @Test
    void removingABlockedServerIsRefusedBeforeAnyPrompt() {
        doThrow(new IllegalStateException("Built-in MCP server cannot be deleted.")).when(registrationService)
                .requireRemovable("built-in");

        assertThatThrownBy(() -> tool("saip_mcp_remove_server").approvalFor(Map.of("server", "built-in")))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void removalReportsHowManyExposedToolsWentAway() {
        when(registrationService.remove("wiki")).thenReturn(new McpRegistrationService.RemovalResult("wiki", 2));

        ManagementResult result = tool("saip_mcp_remove_server").handle(Map.of("server", "wiki"),
                ManagementCaller.ANONYMOUS);

        assertThat(result.message()).contains("2 exposed tool(s)");
        assertThat(result.persisted()).isTrue();
    }

}
