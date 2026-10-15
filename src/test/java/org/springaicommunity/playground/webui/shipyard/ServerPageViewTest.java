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
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarTester;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.bundle.BundleDependencyMonitor;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.Change;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.SourceChange;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ServerPageViewTest extends SpringBrowserlessTest {

    private static final String TOOL_ID = "page-t1";
    private static final String SERVER_NAME = "page-test-server";

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private ConnectionSnippetService connectionSnippetService;

    @Autowired
    private ShipyardService shipyardService;

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    @Test
    void heroCarriesOneStatusChipSetAndTheOverflowActions() {
        shipBundle(Transport.BOTH, 18400);
        ServerPageView page = openServerPage();

        assertThat(spanTexts(page)).contains("1 tool", "model-free", "v0.2.0");
        assertThat(spanTexts(page)).containsAnyOf("Docker CLI not found", "Docker not running",
                "Not deployed", "Stopped", "Running on :18400", "Running");
        assertThat(buttonTexts(page)).containsAnyOf("Run", "Connect");

        MenuBar overflow = $(MenuBar.class, page).first();
        assertThat(overflow.getItems().getFirst().getSubMenu().getItems())
                .extracting(item -> item.getElement().getText())
                .containsExactly("Stop", "Logs", "Rebuild", "Delete");
    }

    @Test
    void everyTabRendersItsOwnPanel() {
        shipBundle(Transport.BOTH, 18400);
        ServerPageView page = openServerPage();
        Tabs tabs = $(Tabs.class, page).first();

        assertThat(tabs.getChildren().map(tab -> tab.getElement().getText()).toList())
                .containsExactly("Overview", "Tools (1)", "Publish", "Files");
        assertThat($(ConnectSection.class, page).all()).isNotEmpty();

        test(tabs).select("Publish");
        roundTrip();
        assertThat(textAreaValue(page, "server.json")).contains("io.github.OWNER/" + SERVER_NAME);
        assertThat(textAreaValue(page, "Publish")).contains("mcp-publisher publish")
                .contains("docker tag saip-mcp/" + SERVER_NAME + ":0.2.0 ghcr.io/OWNER/" + SERVER_NAME + ":0.2.0");

        test(tabs).select("Files");
        roundTrip();
        assertThat(spanTexts(page)).contains(
                this.bundleRegistryService.find(SERVER_NAME).orElseThrow().dir().toString());

        test(tabs).select("Overview");
        roundTrip();
        assertThat($(ConnectSection.class, page).all()).isNotEmpty();
    }

    @Test
    void connectSectionOffersTheEndpointAndOneClickInstalls() {
        shipBundle(Transport.BOTH, 18400);
        ServerPageView page = openServerPage();
        ConnectSection connect = $(ConnectSection.class, page).first();

        assertThat(spanTexts(connect)).contains("Endpoint", "http://localhost:18400/mcp");
        assertThat(buttonTexts(connect)).contains("Test connection", "Add to this app", "Copy");

        List<Anchor> installs = $(Anchor.class, connect).all();
        assertThat(installs).extracting(Anchor::getText)
                .containsExactly("Install in Cursor", "Install in VS Code");
        assertThat(installs.getFirst().getHref())
                .startsWith("cursor://anysphere.cursor-deeplink/mcp/install?name=" + SERVER_NAME);
        assertThat(installs.getLast().getHref()).startsWith("vscode:mcp/install?%7B%22name%22%3A%22" + SERVER_NAME);
    }

    @Test
    void rebuildReopensTheShipFlowWithEveryFieldRestored() {
        shipBundle(Transport.STDIO, 18400);
        ServerPageView page = openServerPage();

        new MenuBarTester<>($(MenuBar.class, page).first()).clickItem(0, 2);
        roundTrip();

        assertThat($(ShipFlowView.class).all()).isNotEmpty();
        assertThat(field(TextField.class, "Server name").getValue()).isEqualTo(SERVER_NAME);
        assertThat(field(TextField.class, "Version").getValue()).isEqualTo("0.2.0");
        assertThat(field(IntegerField.class, "Host port").getValue()).isEqualTo(18400);
        assertThat(field(TextArea.class, "Description (optional)").getValue())
                .isEqualTo("page test bundle");
    }

    @Test
    void aServerOnARemoteTargetConnectsThroughThatHost() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        DeployTarget remote = DeployTarget.docker("build-box", null, "ssh://ops@build.example.com", null,
                DeployTarget.Origin.ADDED);

        ServerPageView page = new ServerPageView(entry, remote, new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.RUNNING, 18400), false, "latest", Optional.of("tok"),
                this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());

        assertThat(spanTexts(page)).contains("Running on build-box:18400",
                "http://build.example.com:18400/mcp", "Target · ", "build-box - ssh://ops@build.example.com");
        ConnectSection connect = $(ConnectSection.class, page).first();
        assertThat($(TextArea.class, connect).first().getValue())
                .contains("claude mcp add --transport http " + SERVER_NAME + " http://build.example.com:18400/mcp");
    }

    @Test
    void anUnreachableRemoteTargetStillLetsTheUserPickAnotherOneInTheRunDialog() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        DeployTarget remote = DeployTarget.docker("build-box", null, "tcp://10.9.9.9:2375", null,
                DeployTarget.Origin.ADDED);

        ServerPageView page = new ServerPageView(entry, remote, new DockerAvailability(true, false, null),
                new ContainerStatus(ContainerState.ABSENT, null), false, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());

        assertThat(spanTexts(page)).contains("Target build-box unreachable");
        Button run = $(Button.class, page).withCondition(button -> "Run".equals(button.getText())).first();
        assertThat(run.isEnabled()).isTrue();
    }

    @Test
    void aKnowledgeBundleGetsAKnowledgeTabListingItsSnapshot() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry shipped = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        BundleManifest manifest = shipped.manifest();
        BundleManifest.RagPipelineRef ref = new BundleManifest.RagPipelineRef("kb-pipe",
                new BundleManifest.Expose(true, false), "search_handbook", "Search the handbook", "kb-pipe", 3,
                List.of(new BundleManifest.DocumentRef("doc-1", "Handbook", 7)));
        BundleManifest withKnowledge = new BundleManifest(manifest.bundleVersion(), manifest.server(),
                new BundleManifest.Requires(manifest.requires().minRuntimeVersion(), manifest.requires().env(),
                        new BundleManifest.Models(null, new BundleManifest.ModelRef("ollama", "qwen3-embedding:0.6b")),
                        manifest.requires().upstreamServers()),
                new BundleManifest.Contents(manifest.contents().tools(), null, List.of(ref), null),
                new BundleManifest.Risk("L1", List.of(new BundleManifest.ToolRisk("search_handbook", "L1", false,
                        "KNOWLEDGE", "Search the handbook"))),
                manifest.integrity(), manifest.provenance());
        BundleEntry entry = new BundleEntry(shipped.name(), withKnowledge, shipped.dir(), shipped.buildConfig());

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.ABSENT, null), false, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());
        UI.getCurrent().add(page);
        Tabs tabs = $(Tabs.class, page).first();

        assertThat(tabs.getChildren().map(tab -> tab.getElement().getText()).toList())
                .containsExactly("Overview", "Tools (2)", "Knowledge", "Publish", "Files");
        assertThat(spanTexts(page)).contains("embedding: qwen3-embedding:0.6b");
        test(tabs).select("Knowledge");
        GridTester<Grid<?>, ?> knowledge = test($(Grid.class, page).first());
        assertThat(knowledge.size()).isEqualTo(1);
        assertThat(knowledge.getCellText(0, 0)).isEqualTo("search_handbook");
        assertThat(knowledge.getCellText(0, 1)).isEqualTo("Handbook");
        assertThat(knowledge.getCellText(0, 2)).isEqualTo("7");
        assertThat(knowledge.getCellText(0, 3)).isEqualTo("kb-pipe");

        test(tabs).select("Tools (2)");
        GridTester<Grid<?>, ?> tools = test($(Grid.class, page).first());
        assertThat(tools.getCellText(0, 0)).isEqualTo("search_handbook");
        assertThat(tools.getCellComponent(0, 1).getElement().getText()).isEqualTo("Knowledge");
    }

    @Test
    void aContainerThatIsUpButNotYetServingReadsAsStarting() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.STARTING, 18400), false, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());

        assertThat(spanTexts(page)).contains("Starting on :18400");
        Button primary = $(Button.class, page)
                .withCondition(button -> "Starting".equals(button.getText())).first();
        assertThat(primary.isEnabled()).isFalse();
        assertThat(buttonTexts(page)).doesNotContain("Run", "Connect");
    }

    @Test
    void aServerManagedByConfigurationIsMarkedAndCannotBeRebuiltOrDeleted() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.ABSENT, null), true, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());

        assertThat(spanTexts(page)).contains("Managed by configuration");
        MenuBar overflow = $(MenuBar.class, page).first();
        assertThat(overflow.getItems().getFirst().getSubMenu().getItems())
                .filteredOn(item -> List.of("Rebuild", "Delete").contains(item.getElement().getText()))
                .allMatch(item -> !item.isEnabled())
                .hasSize(2);
    }

    @Test
    void aRunningServerWithAnUnreachableDependencySaysWhichToolsAreAffected() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        BundleDependencyMonitor.Dependency upstream = new BundleDependencyMonitor.Dependency("DeepWiki",
                BundleDependencyMonitor.Kind.UPSTREAM, BundleDependencyMonitor.State.DOWN, "connection refused",
                List.of("deepwiki_read_wiki_structure"));

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.RUNNING, 18400, List.of(upstream)), false, "latest",
                Optional.empty(), this.connectionSnippetService, this.shipyardService, new Span(), List.of(), noActions());

        assertThat(spanTexts(page)).contains("Running on :18400 · 1 unavailable", "Unavailable right now",
                "DeepWiki (upstream server): connection refused · affects deepwiki_read_wiki_structure");
    }

    @Test
    void aStaleBundleOffersARebuildWithTheChangedSources() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        AtomicBoolean rebuilt = new AtomicBoolean();
        Runnable noop = () -> { };

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.ABSENT, null), false, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(),
                List.of(new SourceChange("tool:pageEchoTool", Change.CHANGED),
                        new SourceChange("upstream:DeepWiki", Change.REMOVED)),
                new ServerPageView.Actions(noop, noop, noop, () -> rebuilt.set(true), noop, noop, noop));

        assertThat(spanTexts(page)).contains("Out of date", "Sources changed since this bundle was shipped")
                .anyMatch(text -> text.startsWith("pageEchoTool changed, DeepWiki was removed."));
        $(Button.class, page).withCondition(button -> "Rebuild".equals(button.getText())).first().click();
        assertThat(rebuilt).isTrue();
    }

    @Test
    void aStaleRunningBundleAsksToStopBeforeRebuilding() {
        shipBundle(Transport.BOTH, 18400);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        Runnable noop = () -> { };

        ServerPageView page = new ServerPageView(entry, DeployTarget.local(), new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(ContainerState.RUNNING, 18400), false, "latest", Optional.empty(),
                this.connectionSnippetService, this.shipyardService, new Span(),
                List.of(new SourceChange("tool:pageEchoTool", Change.CHANGED)),
                new ServerPageView.Actions(noop, noop, noop, noop, noop, noop, noop));

        assertThat(spanTexts(page)).contains("Stop the server to rebuild");
        assertThat($(Button.class, page).withCondition(button -> "Rebuild".equals(button.getText())).first()
                .isEnabled()).isFalse();
    }

    @Test
    void theToolsTabListsKindAndDescription() {
        shipBundle(Transport.BOTH, 18400);
        ServerPageView page = openServerPage();

        test($(Tabs.class, page).first()).select(1);
        roundTrip();

        GridTester<Grid<BundleManifest.ToolRisk>, BundleManifest.ToolRisk> tools = test(toolRiskGrid(page));
        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.getCellText(0, 0)).isEqualTo("pageEchoTool");
        assertThat(tools.getCellText(0, 2)).isEqualTo("echoes");
        assertThat(tools.getCellText(0, 3)).isEqualTo("L0");
    }

    @Test
    void deleteAsksForConfirmationAndKeepsTheBundleUntilConfirmed() {
        shipBundle(Transport.BOTH, 18400);
        ServerPageView page = openServerPage();

        new MenuBarTester<>($(MenuBar.class, page).first()).clickItem(0, 3);
        roundTrip();

        Dialog dialog = $(Dialog.class).all().stream().filter(Dialog::isOpened).findFirst().orElseThrow();
        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isPresent();

        test($(Button.class, dialog).withCondition(button -> "Delete".equals(button.getText())).first())
                .click();
        roundTrip();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isEmpty();
    }

    private static ServerPageView.Actions noActions() {
        Runnable noop = () -> { };
        return new ServerPageView.Actions(noop, noop, noop, noop, noop, noop, noop);
    }

    private void shipBundle(Transport transport, int hostPort) {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "pageEchoTool", "echoes", List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));
        this.bundleExportService.exportBundle(new ShipRequest(SERVER_NAME, "page test bundle",
                "0.2.0", List.of(TOOL_ID), transport, hostPort, new BundleManifest.Models(null, null)));
    }

    private ServerPageView openServerPage() {
        navigate(ShipyardView.class);
        return $(ServerPageView.class).first();
    }

    @SuppressWarnings("unchecked")
    private Grid<BundleManifest.ToolRisk> toolRiskGrid(ServerPageView page) {
        return $(Grid.class, page).first();
    }

    private <T extends Component & HasLabel> T field(Class<T> type, String label) {
        return $(type).withCondition(field -> label.equals(field.getLabel())).first();
    }

    private String textAreaValue(Component scope, String label) {
        return $(TextArea.class, scope)
                .withCondition(area -> label.equals(area.getLabel())).first().getValue();
    }

    private List<String> spanTexts(Component scope) {
        return $(Span.class, scope).all().stream().map(Span::getText).toList();
    }

    private List<String> buttonTexts(Component scope) {
        return $(Button.class, scope).all().stream().map(Button::getText).toList();
    }

}
