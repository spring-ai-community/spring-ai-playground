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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.select.Select;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService.ExposureRequest;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springaicommunity.playground.webui.UsageEventTracker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpExposedToolsPanelTest extends SpringBrowserlessTest {

    @Autowired
    private McpExposedToolService exposedToolService;
    @Autowired
    private McpCompositionService compositionService;
    @Autowired
    private McpClientService clientService;
    @Autowired
    private McpToolRiskEvaluator riskEvaluator;
    @Autowired
    private ToolSpecService toolSpecService;
    @Autowired
    private UsageEventTracker usageEventTracker;

    @Test
    @SuppressWarnings("unchecked")
    void applyRoutesModeAndCapThroughTheExposedToolService() {
        ExposureMode previousMode = this.toolSpecService.exposureMode();
        RiskLevel previousCap = this.compositionService.getExposed().map(McpComposition::maxRiskLevel)
                .orElse(RiskLevel.L3);
        McpExposedToolsPanel panel = new McpExposedToolsPanel(this.exposedToolService, this.compositionService,
                this.clientService, this.riskEvaluator, this.toolSpecService, this.usageEventTracker);
        Component panelRoot = panel.build();
        UI.getCurrent().add(panelRoot);
        Select<ExposureMode> modeField = $(Select.class, panelRoot)
                .withCondition(select -> "What the built-in server exposes".equals(select.getLabel())).single();
        Select<RiskLevel> capField = $(Select.class, panelRoot)
                .withCondition(select -> select.getLabel().startsWith("Max risk to expose")).single();

        try {
            modeField.setValue(ExposureMode.BUILTIN_ONLY);
            capField.setValue(RiskLevel.L2);
            assertThat(panel.apply()).isTrue();

            assertThat(this.toolSpecService.exposureMode()).isEqualTo(ExposureMode.BUILTIN_ONLY);
            assertThat(this.compositionService.getExposed()).map(McpComposition::maxRiskLevel)
                    .contains(RiskLevel.L2);
        } finally {
            this.exposedToolService.apply(new ExposureRequest(previousMode, this.compositionService.getExposed()
                    .map(McpComposition::members).orElse(List.of()), previousCap));
        }
    }
}
