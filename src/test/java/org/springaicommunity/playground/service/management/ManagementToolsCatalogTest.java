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
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springframework.beans.factory.ObjectProvider;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementToolsCatalogTest {

    private static final int MAX_DESCRIPTION_LENGTH = 700;

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
                        mock(SystemMetricsSnapshot.class), auditLog, new SimpleMeterRegistry()));
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
        assertThat(tools).hasSize(21);
    }

    @Test
    void everyToolHasAConciseDescriptionAndACategory() {
        Set<String> categories = new HashSet<>();
        for (ManagementTool tool : allTools()) {
            assertThat(tool.description()).as(tool.name()).isNotBlank().hasSizeLessThan(MAX_DESCRIPTION_LENGTH);
            categories.add(tool.category());
        }
        assertThat(categories).containsExactlyInAnyOrder("mcp", "mcp-register", "expose", "monitor");
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
    void readOnlyCategoriesContainNoWriteTools() {
        assertThat(allTools().stream().filter(tool -> tool.category().equals("monitor")
                || tool.category().equals("mcp"))).allMatch(tool -> tool.level() == ApprovalLevel.READ);
    }

}
