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
package org.springaicommunity.playground.webui.shipyard;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService;
import org.springaicommunity.playground.service.mcp.bundle.ConfiguredBundleExporter;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolMcpServerSetting;
import org.springaicommunity.playground.webui.mcp.McpServerView;
import org.springaicommunity.playground.webui.tool.ToolStudioView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShipyardViewTest extends SpringBrowserlessTest {

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private McpCompositionService compositionService;

    @Autowired
    private ConfiguredBundleExporter configuredBundleExporter;

    @AfterEach
    void cleanup() {
        bundleRegistryService.delete("ui-test-server");
        toolSpecService.deleteToolSpec("ui-b1");
        toolSpecService.deleteToolSpec("ui-b2");
        toolSpecService.deleteToolSpec("ui-b3");
    }

    @Test
    void builtBundleAppearsInSidebarWithDetail() {
        toolSpecService.update(spec("ui-b1", "uiBundleTool", "ui test tool", false));
        bundleExportService.exportBundle(new ShipRequest("ui-test-server", "ui test bundle",
                "0.1.0", List.of("ui-b1"), Transport.BOTH, null, null));

        ShipyardView view = navigate(ShipyardView.class);

        assertThat($(Span.class, view)
                .withCondition(span -> "ui-test-server".equals(span.getText())).all()).isNotEmpty();
        assertThat($(H2.class, view)
                .withCondition(h2 -> "ui-test-server".equals(h2.getText())).all()).isNotEmpty();
    }

    @Test
    void aDeclaredServerThatCouldNotBeExportedShowsItsProblemInTheSidebar() {
        ShipyardServer declared = new ShipyardServer("cfg-missing", null, null, List.of("cfgMissingTool"), null,
                null, null, null, null, null, null, null, null);
        this.configuredBundleExporter.reconcile(declared);
        try {
            ShipyardView view = navigate(ShipyardView.class);

            assertThat($(Span.class, view).all().stream().map(Span::getText).toList())
                    .contains("cfg-missing", "Declared in configuration, not exported: unknown tool name(s) "
                            + "[cfgMissingTool]");
        } finally {
            toolSpecService.update(spec("ui-cfg", "cfgMissingTool", "declared tool", false));
            this.configuredBundleExporter.reconcile(declared);
            bundleRegistryService.delete("cfg-missing");
            toolSpecService.deleteToolSpec("ui-cfg");
        }
        assertThat(this.configuredBundleExporter.problems()).doesNotContainKey("cfg-missing");
    }

    @Test
    void shipNewServerOpensShipFlow() {
        ShipyardView view = navigate(ShipyardView.class);

        Button shipButton = $(Button.class, view)
                .withCondition(button -> "Ship New Server".equals(button.getText()))
                .first();
        test(shipButton).click();
        roundTrip();

        assertThat($(ShipFlowView.class).all()).isNotEmpty();
        assertThat($(H2.class).all().stream().map(H2::getText).toList())
                .contains("Ship a new server");
    }

    @Test
    void shipFlowListsOnlyLocalPassTools() {
        toolSpecService.update(spec("ui-b1", "uiActiveTool", "active", false));
        toolSpecService.update(spec("ui-b2", "uiDraftTool", "draft", true));
        toolSpecService.update(new ToolSpec("ui-b3", "uiEnvMissingTool", "missing env",
                List.of(Map.entry("apiKey", "${UI_TEST_SURELY_UNSET_ENV_VAR}")), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));

        ShipyardView view = navigate(ShipyardView.class);
        Button shipButton = $(Button.class, view)
                .withCondition(button -> "Ship New Server".equals(button.getText()))
                .first();
        test(shipButton).click();
        roundTrip();

        test($(Button.class).withCondition(button -> "+ Your tools".equals(button.getText())).first()).click();
        roundTrip();
        Dialog picker = $(Dialog.class).all().stream().filter(Dialog::isOpened).findFirst().orElseThrow();
        BundleToolSelectionList selectionList = $(BundleToolSelectionList.class, picker).first();
        List<String> renderedNames = $(Span.class, selectionList).all().stream()
                .map(Span::getText).toList();
        assertThat(renderedNames).contains("uiActiveTool")
                .doesNotContain("uiDraftTool", "uiEnvMissingTool")
                .doesNotContain("evalExpression");
        assertThat(renderedNames)
                .anyMatch(text -> text.contains("Excluded from bundling")
                        && text.contains("draft") && text.contains("missing required environment"));
        assertThat($(Button.class).withCondition(button -> "+ Built-in".equals(button.getText())).all())
                .isNotEmpty();
    }

    @Test
    void headerShipButtonIsDisabledWhileTheFlowIsOpenAndCancelBringsItBack() {
        ShipyardView view = navigate(ShipyardView.class);
        Button header = headerShipButton(view);

        test(header).click();
        roundTrip();
        assertThat(header.isEnabled()).isFalse();

        test($(Button.class).withCondition(button -> "Cancel".equals(button.getText())).first()).click();
        roundTrip();

        assertThat($(ShipFlowView.class).all()).isEmpty();
        assertThat(header.isEnabled()).isTrue();
    }

    @Test
    void toolStudioHandoffOpensTheShipFlowWithTheToolPreselected() {
        toolSpecService.update(spec("ui-b1", "uiHandoffTool", "handoff", false));

        navigate(ToolStudioView.class);
        ShipyardView.requestShipFlowWith(List.of("ui-b1"));
        roundTrip();

        ShipFlowView flow = $(ShipFlowView.class).first();
        assertThat($(Span.class, flow).all().stream().map(Span::getText).toList())
                .contains("uiHandoffTool", "handoff", "1 tool");
    }

    @Test
    void mcpServerShortcutOpensTheShipFlowWithTheExposedToolsPreselected() {
        toolSpecService.update(spec("ui-b1", "uiExposedTool", "exposed", false));
        ToolMcpServerSetting original = toolSpecService.getToolMcpServerSetting();
        Optional<McpComposition> originalExposed = compositionService.getExposed();
        McpComposition.Member upstreamTool = new McpComposition.Member("ui-upstream", "search", "ui_search",
                null, false, "");
        toolSpecService.updateToolMcpServerSetting(new ToolMcpServerSetting(false, Set.of("ui-b1")));
        compositionService.upsertExposed(List.of(upstreamTool), RiskLevel.L3);
        try {
            navigate(McpServerView.class);
            Button shortcut = $(Button.class).withCondition(button -> button.getTooltip() != null
                    && "Ship MCP Server".equals(button.getTooltip().getText())).first();

            test(shortcut).click();
            roundTrip();

            ShipFlowView flow = $(ShipFlowView.class).first();
            assertThat($(Span.class, flow).all().stream().map(Span::getText).toList())
                    .contains("uiExposedTool", "ui_search", "2 tools");
        } finally {
            toolSpecService.updateToolMcpServerSetting(original);
            compositionService.upsertExposed(originalExposed.map(McpComposition::members).orElse(List.of()),
                    originalExposed.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5));
        }
    }

    @Test
    void emptyRegistryShowsTheInvitationToShipTheFirstServer() {
        bundleRegistryService.list().forEach(entry -> bundleRegistryService.delete(entry.name()));

        ShipyardView view = navigate(ShipyardView.class);

        assertThat($(H3.class, view).all().stream().map(H3::getText).toList())
                .contains("Anything as an MCP server");
        assertThat($(Button.class, view).all().stream().map(Button::getText).toList())
                .contains("Ship New Server", "Open Vector Database");
    }

    private Button headerShipButton(ShipyardView view) {
        return $(Button.class, view)
                .withCondition(button -> "Ship New Server".equals(button.getText())).first();
    }

    private static ToolSpec spec(String toolId, String name, String description, boolean draft) {
        return new ToolSpec(toolId, name, description, List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(draft);
    }

}
