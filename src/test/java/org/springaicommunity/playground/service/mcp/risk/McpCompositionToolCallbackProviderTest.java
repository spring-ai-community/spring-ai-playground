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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpCompositionToolCallbackProviderTest {

    @Test
    void returnsEmptyWhenNoCompositionEnabled() {
        McpCompositionService composition = mock(McpCompositionService.class);
        when(composition.getEnabled()).thenReturn(List.of());

        McpCompositionToolCallbackProvider provider = newProvider(composition,
                mock(McpServerInfoService.class), mock(McpClientService.class));

        assertThat(provider.getToolCallbacks()).isEmpty();
    }

    @Test
    void skipsMemberWhenServerNotRegistered() {
        McpCompositionService composition = mock(McpCompositionService.class);
        McpComposition enabled = new McpComposition("c1", "dev", "", List.of(
                new McpComposition.Member("GitHub", "list_repos", "github__list_repos", "")),
                true, RiskLevel.L3, 1L, 1L, 1L);
        when(composition.getEnabled()).thenReturn(List.of(enabled));

        McpServerInfoService infoService = mock(McpServerInfoService.class);
        when(infoService.read()).thenReturn(List.of());

        McpCompositionToolCallbackProvider provider = newProvider(composition, infoService,
                mock(McpClientService.class));

        assertThat(provider.getToolCallbacks()).isEmpty();
    }

    @Test
    void skipsMemberWhenUpstreamNotConnected() {
        McpServerInfo info = new McpServerInfo(McpTransportType.STDIO, "GitHub", "", 0L, 0L, "{}");
        McpCompositionService composition = mock(McpCompositionService.class);
        McpComposition enabled = new McpComposition("c1", "dev", "", List.of(
                new McpComposition.Member("GitHub", "list_repos", "github__list_repos", "")),
                true, RiskLevel.L3, 1L, 1L, 1L);
        when(composition.getEnabled()).thenReturn(List.of(enabled));

        McpServerInfoService infoService = mock(McpServerInfoService.class);
        when(infoService.read()).thenReturn(List.of(info));

        McpClientService clientService = mock(McpClientService.class);
        when(clientService.isConnecting(info)).thenReturn(false);

        McpCompositionToolCallbackProvider provider = newProvider(composition, infoService, clientService);

        assertThat(provider.getToolCallbacks()).isEmpty();
    }

    private static McpCompositionToolCallbackProvider newProvider(McpCompositionService composition,
            McpServerInfoService infoService, McpClientService clientService) {
        McpCatalogService catalog = mock(McpCatalogService.class);
        when(catalog.findByServerName(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return new McpCompositionToolCallbackProvider(composition, infoService, clientService, catalog,
                mock(McpRiskInputsResolver.class), mock(McpServerRiskCalculator.class),
                mock(McpToolPublishRiskCalculator.class), mock(McpToolRiskComposer.class));
    }
}
