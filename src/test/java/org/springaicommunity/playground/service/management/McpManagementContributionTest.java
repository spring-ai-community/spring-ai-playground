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
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.management.McpManagementContribution.CatalogData;
import org.springaicommunity.playground.service.management.McpManagementContribution.RiskData;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CatalogRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CustomRegistration;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.RegistrationPlan;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.CatalogOverview;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.TransportOverview;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpManagementContributionTest {

    private final McpServerOverviewService overviewService = mock(McpServerOverviewService.class);
    private final McpRegistrationService registrationService = mock(McpRegistrationService.class);
    private final McpManagementContribution contribution =
            new McpManagementContribution(overviewService, registrationService);

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static CatalogOverview entry(String id, boolean installed) {
        return new CatalogOverview(id, id, "d", "DEVELOPER_TOOLS", 1, true, Set.of(),
                List.of(new TransportOverview(McpTransportType.STREAMABLE_HTTP, List.of(), List.of())), installed);
    }

    @Test
    void catalogSearchForwardsTheFiltersAndClampsTheLimit() {
        List<CatalogOverview> all = IntStream.range(0, 150).mapToObj(index -> entry("entry-" + index, false)).toList();
        when(overviewService.catalog("git", "DEVELOPER_TOOLS", McpTransportType.STDIO)).thenReturn(all);
        Map<String, Object> filters = Map.of("query", "git", "category", "DEVELOPER_TOOLS", "transport", "STDIO");

        ManagementResult byDefault = tool("saip_mcp_catalog_search").handle(filters, ManagementCaller.ANONYMOUS);
        ManagementResult capped = tool("saip_mcp_catalog_search").handle(Map.of("query", "git",
                "category", "DEVELOPER_TOOLS", "transport", "STDIO", "limit", 500), ManagementCaller.ANONYMOUS);
        ManagementResult atLeastOne = tool("saip_mcp_catalog_search").handle(Map.of("query", "git",
                "category", "DEVELOPER_TOOLS", "transport", "STDIO", "limit", 0), ManagementCaller.ANONYMOUS);

        assertThat(byDefault.message()).isEqualTo("150 catalog entries.");
        CatalogData page = (CatalogData) byDefault.data();
        assertThat(page.entries()).hasSize(20);
        assertThat(page.truncated()).isTrue();
        assertThat(page.total()).isEqualTo(150);
        assertThat(((CatalogData) capped.data()).entries()).hasSize(100);
        assertThat(((CatalogData) atLeastOne.data()).entries()).hasSize(1);
    }

    @Test
    void catalogSearchWithoutFiltersReturnsEverythingWithInstalledFlags() {
        when(overviewService.catalog(null, null, null)).thenReturn(List.of(entry("github", true)));

        ManagementResult result = tool("saip_mcp_catalog_search").handle(Map.of(), ManagementCaller.ANONYMOUS);

        assertThat(result.message()).isEqualTo("1 catalog entry.");
        CatalogData data = (CatalogData) result.data();
        assertThat(data.truncated()).isFalse();
        assertThat(data.entries()).singleElement().satisfies(found -> {
            assertThat(found.id()).isEqualTo("github");
            assertThat(found.installed()).isTrue();
        });
    }

    @Test
    void previewRiskForACatalogEntryPlansWithoutRegistering() {
        McpServerInfo info = new McpServerInfo(McpTransportType.STDIO, "everything", "d", 0L, 0L,
                "{\"command\":\"npx\"}");
        when(registrationService.planCatalog(any(CatalogRegistration.class), eq(false))).thenReturn(
                new RegistrationPlan(info, new RegistrationPreview(RiskLevel.L2, null, Map.of("transport", "stdio"),
                        false), "npx -y @modelcontextprotocol/server-everything"));

        ManagementResult result = tool("saip_mcp_preview_risk").handle(Map.of("catalogId", "everything",
                "transport", "STDIO", "values", Map.of("tenant", "acme")), ManagementCaller.ANONYMOUS);

        assertThat(result.message()).isEqualTo("Risk L2.");
        RiskData data = (RiskData) result.data();
        assertThat(data.server()).isEqualTo("everything");
        assertThat(data.level()).isEqualTo(RiskLevel.L2);
        assertThat(data.rationale()).containsEntry("transport", "stdio");
        assertThat(data.commandLine()).startsWith("npx -y");
        ArgumentCaptor<CatalogRegistration> registration = ArgumentCaptor.forClass(CatalogRegistration.class);
        verify(registrationService).planCatalog(registration.capture(), eq(false));
        assertThat(registration.getValue().catalogId()).isEqualTo("everything");
        assertThat(registration.getValue().transport()).isEqualTo(McpTransportType.STDIO);
        assertThat(registration.getValue().values()).containsEntry("tenant", "acme");
        verify(registrationService, never()).register(any());
    }

    @Test
    void previewRiskForACustomServerDefaultsTheNameAndTransport() {
        McpServerInfo info = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "preview", "d", 0L, 0L, "{}");
        when(registrationService.planCustom(any(CustomRegistration.class), eq(false))).thenReturn(
                new RegistrationPlan(info, new RegistrationPreview(RiskLevel.L4, "unknown host", Map.of(), false),
                        null));

        ManagementResult result = tool("saip_mcp_preview_risk").handle(Map.of("url", "https://mcp.example.com",
                "headers", Map.of("Authorization", "Bearer ${WIKI_TOKEN}")), ManagementCaller.ANONYMOUS);

        RiskData data = (RiskData) result.data();
        assertThat(data.level()).isEqualTo(RiskLevel.L4);
        assertThat(data.floorTrigger()).isEqualTo("unknown host");
        assertThat(data.commandLine()).isNull();
        ArgumentCaptor<CustomRegistration> registration = ArgumentCaptor.forClass(CustomRegistration.class);
        verify(registrationService).planCustom(registration.capture(), eq(false));
        assertThat(registration.getValue().name()).isEqualTo("preview");
        assertThat(registration.getValue().transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
        assertThat(registration.getValue().headers()).containsEntry("Authorization", "Bearer ${WIKI_TOKEN}");
        verify(registrationService, never()).register(any());
    }

    @Test
    void previewRiskRefusesLiteralSecretsBeforePlanning() {
        assertThatThrownBy(() -> tool("saip_mcp_preview_risk").handle(Map.of("url", "https://mcp.example.com",
                "headers", Map.of("Authorization", "Bearer abc123456789")), ManagementCaller.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("${ENV_NAME}");
        verify(registrationService, never()).planCustom(any(CustomRegistration.class), anyBoolean());
        verify(registrationService, never()).planCatalog(any(CatalogRegistration.class), anyBoolean());
    }

}
