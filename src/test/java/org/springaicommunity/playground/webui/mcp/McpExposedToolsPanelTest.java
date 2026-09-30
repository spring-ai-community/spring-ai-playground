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
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import io.modelcontextprotocol.spec.McpSchema;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpToolPoisoningScanner;
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

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Test
    void markAllForReviewUpdatesEverySelectedRowAndSurvivesLaterEdits() {
        McpServerInfo server = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "wiki", "", 0L, 0L, "{}");
        McpSchema.Tool first = McpSchema.Tool.builder().name("read").description("Reads")
                .inputSchema(Map.of("type", "object")).build();
        McpSchema.Tool second = McpSchema.Tool.builder().name("search").description("Searches")
                .inputSchema(Map.of("type", "object")).build();
        McpExposedToolService exposedTools = mock(McpExposedToolService.class);
        when(exposedTools.composableServers()).thenReturn(List.of(server));
        McpClientService client = mock(McpClientService.class);
        when(client.getToolListAsOpt(server)).thenReturn(Optional.of(List.of(first, second)));
        McpToolRiskEvaluator evaluator = mock(McpToolRiskEvaluator.class);
        when(evaluator.evaluateServer(server)).thenReturn(new McpToolRiskEvaluator.ServerRiskView(RiskLevel.L1,
                Map.of(), null));
        when(evaluator.evaluateTool(any(McpServerInfo.class), any(McpSchema.Tool.class))).thenReturn(
                new McpToolRiskEvaluator.ToolRiskView(RiskLevel.L2, RiskLevel.L2, RiskLevel.L2, null,
                        new McpToolPoisoningScanner.ScanResult(true, List.of())));
        McpCompositionService composition = mock(McpCompositionService.class);
        when(composition.getExposed()).thenReturn(Optional.empty());
        ToolSpecService toolSpec = mock(ToolSpecService.class);
        when(toolSpec.exposureMode()).thenReturn(ExposureMode.BOTH);
        McpExposedToolsPanel panel = new McpExposedToolsPanel(exposedTools, composition, client, evaluator, toolSpec,
                this.usageEventTracker);
        Component root = panel.build();
        UI.getCurrent().add(root);

        $(Details.class, root).all().forEach(details -> details.setOpened(true));
        List<Checkbox> rowSelects = $(Checkbox.class, root).all().stream()
                .filter(box -> box.getLabel() == null).toList();
        rowSelects.forEach(box -> box.setValue(true));
        List<Checkbox> hitlBoxes = $(Checkbox.class, root).all().stream()
                .filter(box -> "HITL".equals(box.getLabel())).toList();
        Checkbox markAll = $(Checkbox.class, root).all().stream()
                .filter(box -> box.getLabel() != null && box.getLabel().startsWith("Mark all")).findFirst()
                .orElseThrow();

        markAll.setValue(true);

        assertThat(hitlBoxes).hasSize(2).allMatch(Checkbox::getValue);

        hitlBoxes.getFirst().setValue(false);
        panel.apply();

        ArgumentCaptor<ExposureRequest> captor = ArgumentCaptor.forClass(ExposureRequest.class);
        verify(exposedTools).apply(captor.capture());
        assertThat(captor.getValue().members()).extracting(McpComposition.Member::toolName,
                McpComposition.Member::hitl).containsExactlyInAnyOrder(tuple("read", false), tuple("search", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void raisingTheRiskCapAgainReenablesTheRowsItHadDisabled() {
        McpServerInfo server = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "wiki", "", 0L, 0L, "{}");
        McpSchema.Tool tool = McpSchema.Tool.builder().name("read").description("Reads")
                .inputSchema(Map.of("type", "object")).build();
        McpExposedToolService exposedTools = mock(McpExposedToolService.class);
        when(exposedTools.composableServers()).thenReturn(List.of(server));
        McpClientService client = mock(McpClientService.class);
        when(client.getToolListAsOpt(server)).thenReturn(Optional.of(List.of(tool)));
        McpToolRiskEvaluator evaluator = mock(McpToolRiskEvaluator.class);
        when(evaluator.evaluateServer(server)).thenReturn(new McpToolRiskEvaluator.ServerRiskView(RiskLevel.L1,
                Map.of(), null));
        when(evaluator.evaluateTool(any(McpServerInfo.class), any(McpSchema.Tool.class))).thenReturn(
                new McpToolRiskEvaluator.ToolRiskView(RiskLevel.L2, RiskLevel.L2, RiskLevel.L2, null,
                        new McpToolPoisoningScanner.ScanResult(true, List.of())));
        McpCompositionService composition = mock(McpCompositionService.class);
        when(composition.getExposed()).thenReturn(Optional.empty());
        ToolSpecService toolSpec = mock(ToolSpecService.class);
        when(toolSpec.exposureMode()).thenReturn(ExposureMode.BOTH);
        McpExposedToolsPanel panel = new McpExposedToolsPanel(exposedTools, composition, client, evaluator, toolSpec,
                this.usageEventTracker);
        Component root = panel.build();
        UI.getCurrent().add(root);
        $(Details.class, root).all().forEach(details -> details.setOpened(true));
        Checkbox rowSelect = $(Checkbox.class, root).all().stream()
                .filter(box -> box.getLabel() == null).findFirst().orElseThrow();
        Select<RiskLevel> capField = $(Select.class, root)
                .withCondition(select -> select.getLabel().startsWith("Max risk to expose")).single();
        rowSelect.setValue(true);

        capField.setValue(RiskLevel.L1);
        assertThat(rowSelect.isEnabled()).isFalse();
        assertThat(rowSelect.getValue()).isFalse();

        capField.setValue(RiskLevel.L5);
        assertThat(rowSelect.isEnabled()).isTrue();
    }
}
