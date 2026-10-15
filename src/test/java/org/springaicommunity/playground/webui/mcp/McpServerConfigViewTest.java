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
package org.springaicommunity.playground.webui.mcp;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.catalog.McpCategoryService;
import org.springaicommunity.playground.service.mcp.catalog.McpTagSuggestionService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationInput;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerConfigViewTest {

    private static final String SERVER_NAME = "agent-helpers";
    private static final String URL = "http://127.0.0.1:18382";

    private final McpServerInfoService infoService = mock(McpServerInfoService.class);
    private final McpClientService clientService = mock(McpClientService.class);
    private final McpCategoryService categoryService = mock(McpCategoryService.class);
    private final McpTagSuggestionService tagService = mock(McpTagSuggestionService.class);
    private final McpRegistrationRiskPreview riskPreview = mock(McpRegistrationRiskPreview.class);

    @Test
    void aRegisteredServerThatNeverConnectedCanBeConnectedWithoutEditing() {
        McpServerConfigView view = viewForSavedServer(null);

        assertThat(saveAndConnectButton(view).isEnabled()).isTrue();
    }

    @Test
    void aServerThatAlreadyConnectedNeedsAChangeBeforeSaving() {
        McpServerConfigView view = viewForSavedServer(1_000L);

        assertThat(saveAndConnectButton(view).isEnabled()).isFalse();
    }

    @Test
    void theRiskPreviewOnlySeesTheFullyPopulatedForm() {
        viewForSavedServer(null);

        ArgumentCaptor<RegistrationInput> inputs = ArgumentCaptor.forClass(RegistrationInput.class);
        verify(riskPreview, atLeastOnce()).preview(inputs.capture());
        assertThat(inputs.getAllValues()).isNotEmpty()
                .allSatisfy(input -> assertThat(input.url()).isEqualTo(URL));
    }

    private McpServerConfigView viewForSavedServer(Long lastUsedAtEpochMs) {
        McpServerInfo saved = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, SERVER_NAME, "shipped by Shipyard",
                1L, 1L, "{\"url\":\"" + URL + "\",\"endpoint\":\"/mcp\"}", McpServerInfo.DEFAULT_CATEGORY, Set.of(),
                lastUsedAtEpochMs);
        when(infoService.getMcpServerInfos()).thenReturn(Map.of(McpTransportType.STREAMABLE_HTTP, List.of(saved)));
        when(infoService.findByServerName(SERVER_NAME)).thenReturn(Optional.of(saved));
        when(riskPreview.preview(any(RegistrationInput.class)))
                .thenReturn(new RegistrationPreview(RiskLevel.L5, null, Map.of(), false));
        return new McpServerConfigView(saved, infoService, clientService, categoryService, tagService, riskPreview,
                new PropertyChangeSupport(this));
    }

    private static Button saveAndConnectButton(Component root) {
        return descendants(root)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Save & Connect".equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component),
                component.getChildren().flatMap(McpServerConfigViewTest::descendants));
    }

}
