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
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShipFlowViewTest extends SpringBrowserlessTest {

    private static final String TOOL_ID = "flow-t1";
    private static final String SERVER_NAME = "flow-test-server";

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private McpCompositionService compositionService;

    @Autowired
    private RagPipelineService ragPipelineService;

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    @Test
    void shipStaysBlockedUntilANameAndAToolAreChosen() {
        publishTool();
        ShipFlowView flow = openShipFlow();

        assertThat(shipButton().isEnabled()).isFalse();
        assertThat(issueTexts()).contains("Server name is required", "Select at least one tool");

        nameField().setValue(SERVER_NAME);
        selectTheOnlyCustomTool(flow);

        assertThat(shipButton().isEnabled()).isTrue();
        assertThat(issueTexts()).contains("Ready to ship")
                .doesNotContain("Server name is required", "Select at least one tool");
    }

    @Test
    void modelDependentServerBlocksUntilAModelIsChosen() {
        publishTool();
        ShipFlowView flow = openShipFlow();
        nameField().setValue(SERVER_NAME);
        selectTheOnlyCustomTool(flow);

        test(modelModeGroup()).selectItem("Requires a model");
        roundTrip();

        assertThat(shipButton().isEnabled()).isFalse();
        assertThat(issueTexts())
                .anyMatch(text -> text.startsWith("Model-dependent server: check at least one"));

        test(modelModeGroup()).selectItem("Model-free (recommended)");
        roundTrip();

        assertThat(shipButton().isEnabled()).isTrue();
    }

    @Test
    void stdioTransportDisablesTheHostPort() {
        publishTool();
        openShipFlow();

        assertThat(portField().isEnabled()).isTrue();

        test(transportGroup()).selectItem("stdio");
        roundTrip();

        assertThat(portField().isEnabled()).isFalse();
        assertThat(advanced().getSummaryText()).contains("stdio");

        test(transportGroup()).selectItem("Streamable HTTP");
        roundTrip();

        assertThat(portField().isEnabled()).isTrue();
        assertThat(advanced().getSummaryText()).contains("Streamable HTTP", "port 18282", "model-free");
    }

    @Test
    void pathPreviewFollowsTheServerName() {
        publishTool();
        openShipFlow();

        assertThat(spanTexts()).contains("mcp/bundles/<name>/");

        nameField().setValue(SERVER_NAME);
        roundTrip();

        assertThat(spanTexts()).contains("mcp/bundles/" + SERVER_NAME + "/");
    }

    @Test
    void everyKindIsAddedFromItsOwnPicker() {
        publishTool();
        openShipFlow();

        assertThat($(Dialog.class).all().stream().anyMatch(Dialog::isOpened)).isFalse();
        assertThat(spanTexts()).anyMatch(text -> text.startsWith("Nothing added yet"));

        for (String kind : List.of("Your tools", "Built-in", "Proxied", "Knowledge")) {
            Dialog dialog = openPicker(kind);
            assertThat(dialog.getHeaderTitle()).isEqualTo(kind.equals("Your tools") ? "Add your tools"
                    : kind.equals("Knowledge") ? "Add knowledge"
                            : "Add " + kind.toLowerCase(Locale.ROOT) + " tools");
            test($(Button.class, dialog).withCondition(button -> "Done".equals(button.getText())).first()).click();
            roundTrip();
        }
    }

    @Test
    void theToolTableShowsWhatShipsAndRemovesARow() {
        publishTool();
        ShipFlowView flow = openShipFlow();
        selectTheOnlyCustomTool(flow);

        assertThat(spanTexts()).contains("flowEchoTool", "Tool", "echoes", "1 tool");

        test($(Button.class, flow).withCondition(button -> "Remove flowEchoTool".equals(button.getAriaLabel()
                .orElse(null))).first()).click();
        roundTrip();

        assertThat(spanTexts()).doesNotContain("flowEchoTool").contains("0 tools")
                .anyMatch(text -> text.startsWith("Nothing added yet"));
    }

    @Test
    void shippingLandsOnTheSuccessPanel() {
        publishTool();
        ShipFlowView flow = openShipFlow();
        nameField().setValue(SERVER_NAME);
        selectTheOnlyCustomTool(flow);

        test(shipButton()).click();
        roundTrip();

        assertThat($(ShipFlowView.class).all()).isEmpty();
        assertThat($(H2.class).all().stream().map(H2::getText).toList())
                .contains(SERVER_NAME + " is ready");
        assertThat(buttonTexts()).contains("Run", "Open server page");
        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isPresent();
    }

    @Test
    void proxiedToolsFromTheExposedCompositionShipWithTheServer() {
        publishTool();
        McpComposition.Member upstreamTool = new McpComposition.Member("flow-upstream", "search", "flow_search",
                null, false, "");
        Optional<McpComposition> original = this.compositionService.getExposed();
        this.mcpServerInfoService.loadAll(() -> this.mcpServerInfoService.updateMcpServerInfo(
                McpTransportType.STREAMABLE_HTTP, "flow-upstream", new McpServerInfo(McpTransportType.STREAMABLE_HTTP,
                        "flow-upstream", "upstream", 1L, 1L, "{\"url\":\"https://mcp.example.com\"}")));
        this.compositionService.upsertExposed(List.of(upstreamTool), RiskLevel.L3);
        try {
            openShipFlow();
            Dialog picker = openPicker("Proxied");
            CheckboxGroup<?> proxied = $(CheckboxGroup.class, picker)
                    .withCondition(group -> "Proxied tools".equals(group.getLabel())).first();
            test(proxied).selectAll();
            roundTrip();
            nameField().setValue(SERVER_NAME);

            assertThat(spanTexts()).contains("1 tool", "flow_search", "Proxied");
            test(shipButton()).click();
            roundTrip();

            assertThat(this.bundleRegistryService.find(SERVER_NAME).orElseThrow().manifest().contents()
                    .composedMembers()).extracting(McpComposition.Member::exposedAlias).containsExactly("flow_search");
        } finally {
            this.compositionService.upsertExposed(original.map(McpComposition::members).orElse(List.of()),
                    original.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5));
            this.mcpServerInfoService.deleteMcpServerInfo(McpTransportType.STREAMABLE_HTTP, "flow-upstream");
        }
    }

    @Test
    void knowledgeShipsAsASearchToolWithDerivedModelAndEditableName() {
        RagPipeline pipeline = this.ragPipelineService.create("Product FAQ", "answers product questions",
                List.of("docInfoId-flow-missing"), RagPipeline.PreRetrievalConfig.defaults(),
                RagPipeline.RetrievalConfig.defaults(), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults());
        try {
            ShipFlowView flow = openShipFlow();
            Dialog picker = openPicker("Knowledge");
            test($(CheckboxGroup.class, picker).first()).selectItem("Product FAQ · pipeline · stages: 3 · no extra "
                    + "LLM calls");
            roundTrip();
            picker.close();

            assertThat(spanTexts()).contains("search_product_faq", "Knowledge", "1 tool", "Snapshot")
                    .anyMatch(text -> text.startsWith("Search the Product FAQ knowledge base"))
                    .contains("Embedding model")
                    .anyMatch(text -> text.contains("references documents that no longer exist"));

            test($(Button.class, flow).withCondition(button -> "Edit search_product_faq".equals(
                    button.getAriaLabel().orElse(null))).first()).click();
            roundTrip();
            assertThat($(Button.class, flow).withCondition(button -> "Try search_product_faq".equals(
                    button.getAriaLabel().orElse(null))).all()).hasSize(1);
            test($(TextField.class, flow).withCondition(field -> "Tool name".equals(field.getLabel())).first())
                    .setValue("search_faq");
            roundTrip();

            assertThat(spanTexts()).contains("search_faq").doesNotContain("search_product_faq");
            assertThat($(TextField.class, flow).withCondition(field -> "Try a search".equals(field.getLabel()))
                    .all()).hasSize(1);

            test($(Button.class, flow).withCondition(button -> "Remove search_faq".equals(
                    button.getAriaLabel().orElse(null))).first()).click();
            roundTrip();
            assertThat(spanTexts()).doesNotContain("search_faq").contains("0 tools");
        } finally {
            this.ragPipelineService.deleteById(pipeline.id());
        }
    }

    private void publishTool() {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "flowEchoTool", "echoes", List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));
    }

    private ShipFlowView openShipFlow() {
        ShipyardView view = navigate(ShipyardView.class);
        test($(Button.class, view)
                .withCondition(button -> "Ship New Server".equals(button.getText())).first()).click();
        roundTrip();
        return $(ShipFlowView.class).first();
    }

    private Dialog openPicker(String kind) {
        test($(Button.class).withCondition(button -> ("+ " + kind).equals(button.getText())).first()).click();
        roundTrip();
        return $(Dialog.class).all().stream().filter(Dialog::isOpened).findFirst().orElseThrow();
    }

    private void selectTheOnlyCustomTool(ShipFlowView flow) {
        Dialog picker = openPicker("Your tools");
        BundleToolSelectionList customList = $(BundleToolSelectionList.class, picker).first();
        test($(CheckboxGroup.class, customList).first()).selectAll();
        roundTrip();
        picker.close();
    }

    private Button shipButton() {
        return $(Button.class).withCondition(button -> "Ship".equals(button.getText())).first();
    }

    private TextField nameField() {
        return $(TextField.class).withCondition(field -> "Server name".equals(field.getLabel())).first();
    }

    private IntegerField portField() {
        return $(IntegerField.class).withCondition(field -> "Host port".equals(field.getLabel())).first();
    }

    @SuppressWarnings("unchecked")
    private RadioButtonGroup<String> modelModeGroup() {
        return $(RadioButtonGroup.class)
                .withCondition(group -> "Model dependency".equals(group.getLabel())).first();
    }

    @SuppressWarnings("unchecked")
    private RadioButtonGroup<Object> transportGroup() {
        return $(RadioButtonGroup.class)
                .withCondition(group -> "Transport".equals(group.getLabel())).first();
    }

    private Details advanced() {
        return $(Details.class)
                .withCondition(details -> details.getSummaryText().startsWith("Advanced")).first();
    }

    private List<String> issueTexts() {
        roundTrip();
        return spanTexts();
    }

    private List<String> spanTexts() {
        return $(Span.class).all().stream().map(Span::getText).toList();
    }

    private List<String> buttonTexts() {
        return $(Button.class).all().stream().map(Button::getText).toList();
    }

}
