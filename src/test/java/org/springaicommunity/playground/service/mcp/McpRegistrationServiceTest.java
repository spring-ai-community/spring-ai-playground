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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpRegistrationService.CustomRegistration;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange;
import org.springaicommunity.playground.service.mcp.risk.ExposureOutcome;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpRegistrationServiceTest {

    private final McpServerInfoService serverInfoService = mock(McpServerInfoService.class);
    private final McpServerOverviewService overviewService = mock(McpServerOverviewService.class);
    private final McpClientService clientService = mock(McpClientService.class);
    private final McpRegistrationRiskPreview riskPreview = mock(McpRegistrationRiskPreview.class);
    private final McpExposureService exposureService = mock(McpExposureService.class);
    private final McpExposedToolService exposedToolService = mock(McpExposedToolService.class);
    private final McpCompositionService compositionService = mock(McpCompositionService.class);
    private final McpRegistrationService service = new McpRegistrationService(serverInfoService, overviewService,
            mock(McpCatalogService.class), clientService, riskPreview, exposureService, exposedToolService,
            compositionService);

    private static CustomRegistration custom(String name) {
        return new CustomRegistration(name, McpTransportType.STREAMABLE_HTTP, "https://mcp.example.com", "/mcp",
                Map.of(), "d", null, Set.of());
    }

    @Test
    void planningARegistrationValidatesItAndRequiresAFreeName() {
        when(riskPreview.preview(any(McpRegistrationRiskPreview.RegistrationInput.class)))
                .thenReturn(new RegistrationPreview(RiskLevel.L1, null, Map.of(), false));

        service.planCustom(custom("wiki"));

        verify(serverInfoService).validateRegistration(any());
        verify(serverInfoService).requireFreeName(any());
    }

    @Test
    void planningAConnectionTestSkipsTheNameCheck() {
        when(riskPreview.preview(any(McpRegistrationRiskPreview.RegistrationInput.class)))
                .thenReturn(new RegistrationPreview(RiskLevel.L1, null, Map.of(), false));

        service.planCustom(custom("wiki"), false);

        verify(serverInfoService).validateRegistration(any());
        verify(serverInfoService, never()).requireFreeName(any());
    }

    @Test
    void aRefusedRegistrationFailsAtPlanningTime() {
        doThrow(new IllegalArgumentException("own MCP endpoint")).when(serverInfoService)
                .validateRegistration(any());

        assertThatThrownBy(() -> service.planCustom(custom("self"))).hasMessageContaining("own MCP endpoint");
        verify(riskPreview, never()).preview(any(McpRegistrationRiskPreview.RegistrationInput.class));
    }

    @Test
    void onlyHttpAndSseCanBeRegisteredWithACustomDefinition() {
        assertThatThrownBy(() -> service.planCustom(new CustomRegistration("x", McpTransportType.STDIO,
                "https://mcp.example.com", null, Map.of(), null, null, null)))
                .hasMessageContaining("Use a catalog entry for stdio servers");
    }

    @Test
    void removingAServerAlsoRemovesItsExposedTools() {
        McpComposition.Member member = new McpComposition.Member("wiki", "read", "wiki_read", null, true, "");
        McpComposition composition = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools", "",
                List.of(member, new McpComposition.Member("other", "x", "other_x", null, true, "")), true,
                RiskLevel.L5, 0L, 0L, 0L);
        when(serverInfoService.deleteBlocker("wiki")).thenReturn(Optional.empty());
        when(compositionService.exposedManagedByConfiguration()).thenReturn(false);
        when(compositionService.getExposed()).thenReturn(Optional.of(composition));
        when(exposureService.commit(any())).thenReturn(mock(ExposureOutcome.class));

        McpRegistrationService.RemovalResult result = service.remove("wiki");

        assertThat(result.exposedToolsRemoved()).isEqualTo(1);
        verify(exposureService).commit(any(ExposureChange.class));
        verify(serverInfoService).delete("wiki");
    }

    @Test
    void removingABlockedServerChangesNothing() {
        when(serverInfoService.deleteBlocker("built-in")).thenReturn(Optional.of("Built-in MCP server cannot be deleted."));

        assertThatThrownBy(() -> service.remove("built-in")).hasMessageContaining("cannot be deleted");
        verify(serverInfoService, never()).delete(any());
        verify(exposureService, never()).commit(any());
    }

    @Test
    void theBuiltInServerCannotBeReconnected() {
        when(serverInfoService.getDefaultMcpServerInfo()).thenReturn(new McpServerInfo(
                McpTransportType.STREAMABLE_HTTP, "built-in", "", 0L, 0L, "{}"));

        assertThatThrownBy(() -> service.reconnect("built-in")).hasMessageContaining("cannot be reconnected");
        verify(clientService, never()).startMcpClient(any());
    }

}
