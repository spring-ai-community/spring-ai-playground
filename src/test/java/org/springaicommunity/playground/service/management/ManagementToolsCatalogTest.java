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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService;
import org.springaicommunity.playground.service.vectorstore.ConfiguredKnowledgeLoader;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.mcp.bundle.ConfiguredBundleExporter;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.observability.ObservabilityProperties;
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.logs.LogQueryService;
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springframework.beans.factory.ObjectProvider;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementToolsCatalogTest {

    private static final int MAX_DESCRIPTION_LENGTH = 700;
    private static final int MAX_LISTED_TOOL_CHARS = 10_000;
    private static final int MAX_LISTED_CATALOG_CHARS = 128_000;

    private final ManagementAuditLog auditLog = ManagementTestSupport.auditLog(
            ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), new McpRiskEventRingBuffer());

    static List<ManagementToolContribution> contributions(ManagementAuditLog auditLog) {
        return List.of(
                new McpManagementContribution(mock(McpServerOverviewService.class),
                        mock(McpRegistrationService.class)),
                new McpRegisterManagementContribution(mock(McpRegistrationService.class)),
                new ExposeManagementContribution(mock(McpExposureService.class)),
                new MonitorManagementContribution(mock(ObservabilityQueryService.class),
                        new McpRiskEventRingBuffer(), mock(McpToolHashLedger.class),
                        mock(SystemMetricsSnapshot.class), auditLog, new SimpleMeterRegistry()),
                new ShipyardManagementContribution(mock(ShipyardService.class), mock(BundleExportService.class),
                        mock(ConfiguredBundleExporter.class), mock(RunPlanner.class),
                        mock(DeployTargetService.class), mock(ConnectionSnippetService.class)),
                new KnowledgeManagementContribution(mock(KnowledgeIngestService.class),
                        mock(RagPipelineService.class), mock(KnowledgeSearchService.class),
                        mock(ConfiguredKnowledgeLoader.class), mock(OfflineEtlPipelineService.class),
                        mock(ShipyardService.class), mock(BundleFreshnessService.class),
                        mock(KnowledgeSuggestService.class)),
                new MonitorInsightsManagementContribution(mock(ObservabilityQueryService.class),
                        new ObservabilityRingBuffer(new ObservabilityProperties()), mock(SystemMetricsSnapshot.class),
                        mock(ObjectProvider.class), mock(LogQueryService.class)),
                new ToolsManagementContribution(mock(ToolSpecService.class), mock(ToolCategoryCatalog.class),
                        ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE)));
    }

    private List<ManagementTool> allTools() {
        return contributions(auditLog).stream().flatMap(contribution -> contribution.tools().stream()).toList();
    }

    @Test
    void namesAreUniqueReservedAndWithinTheProviderLimits() {
        List<ManagementTool> tools = allTools();

        assertThat(tools).extracting(ManagementTool::name).doesNotHaveDuplicates()
                .allMatch(name -> name.matches("^saip_[a-z0-9_]+$") && name.length() <= 64
                        && ManagementToolNames.isReserved(name));
        assertThat(tools).hasSize(54);
    }

    @Test
    void everyToolHasAConciseDescriptionAndACategory() {
        Set<String> categories = new HashSet<>();
        for (ManagementTool tool : allTools()) {
            assertThat(tool.description()).as(tool.name()).isNotBlank().hasSizeLessThan(MAX_DESCRIPTION_LENGTH);
            categories.add(tool.category());
        }
        assertThat(categories).containsExactlyInAnyOrder("mcp", "mcp-register", "expose", "monitor", "shipyard",
                "knowledge", "tools");
    }

    @Test
    void theWholeCatalogStaysWithinTheContextBudget() throws JsonProcessingException {
        ManagementApprovalGate gate = new ManagementApprovalGate(
                ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), mock(ObjectProvider.class), auditLog);
        ObjectMapper mapper = new ObjectMapper();

        long total = 0;
        for (ManagementTool tool : allTools()) {
            int listed = mapper.writeValueAsString(gate.wrap(tool).tool()).length();
            assertThat(listed).as(tool.name()).isLessThanOrEqualTo(MAX_LISTED_TOOL_CHARS);
            total += listed;
        }
        assertThat(total).isLessThanOrEqualTo(MAX_LISTED_CATALOG_CHARS);
    }

    @Test
    void everyToolIsAcceptedByARealMcpServer() {
        McpServerTransportProvider transportProvider = mock(McpServerTransportProvider.class);
        when(transportProvider.notifyClients(any(), any())).thenReturn(Mono.empty());
        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(ServerCapabilities.builder().tools(true).build()).build();
        ManagementApprovalGate gate = new ManagementApprovalGate(
                ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), mock(ObjectProvider.class), auditLog);

        for (ManagementTool tool : allTools()) {
            SyncToolSpecification specification = gate.wrap(tool);
            server.addTool(specification);
        }

        assertThat(server.listTools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrderElementsOf(allTools().stream().map(ManagementTool::name).toList());
    }

    @Test
    void annotationsFollowTheApprovalLevel() {
        ManagementApprovalGate gate = new ManagementApprovalGate(
                ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), mock(ObjectProvider.class), auditLog);

        for (ManagementTool tool : allTools()) {
            McpSchema.ToolAnnotations annotations = gate.wrap(tool).tool().annotations();
            assertThat(annotations.readOnlyHint()).as(tool.name()).isEqualTo(tool.level() == ApprovalLevel.READ);
            assertThat(annotations.destructiveHint()).as(tool.name())
                    .isEqualTo(tool.level() == ApprovalLevel.DESTRUCTIVE);
        }
    }

    @Test
    void toolStudioToolsFollowTheDocumentedApprovalLevels() {
        Map<String, ApprovalLevel> levels = allTools().stream().filter(tool -> tool.category().equals("tools"))
                .collect(Collectors.toMap(ManagementTool::name, ManagementTool::level));

        assertThat(levels).containsOnly(
                Map.entry("saip_tools_list", ApprovalLevel.READ), Map.entry("saip_tools_get", ApprovalLevel.READ),
                Map.entry("saip_tools_check_risk", ApprovalLevel.READ),
                Map.entry("saip_tools_get_exposure", ApprovalLevel.READ),
                Map.entry("saip_tools_save_draft", ApprovalLevel.WRITE),
                Map.entry("saip_tools_test", ApprovalLevel.WRITE),
                Map.entry("saip_tools_publish", ApprovalLevel.DESTRUCTIVE),
                Map.entry("saip_tools_delete", ApprovalLevel.DESTRUCTIVE),
                Map.entry("saip_tools_set_exposure", ApprovalLevel.DESTRUCTIVE));
    }

    @Test
    void readOnlyCategoriesContainNoWriteTools() {
        assertThat(allTools().stream().filter(tool -> tool.category().equals("monitor")
                || tool.category().equals("mcp"))).allMatch(tool -> tool.level() == ApprovalLevel.READ);
    }

}
