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

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerSummary;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.webui.vectorstore.VectorStoreView;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.webui.SpringAiPlaygroundAppLayout;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.ContentWorkspaceView;

import java.util.List;

@AnonymousAllowed
@PageTitle("Shipyard")
@Route(value = "shipyard", layout = SpringAiPlaygroundAppLayout.class)
public class ShipyardView extends ContentWorkspaceView {

    private static final String FLOW_PRESELECTION_KEY = "shipyard-flow-preselection";

    private final BundleExportService bundleExportService;
    private final DeployTargetService deployTargetService;
    private final ConnectionSnippetService connectionSnippetService;
    private final ShipyardService shipyardService;
    private final RunPlanner runPlanner;
    private final ToolCategoryCatalog categoryCatalog;
    private final KnowledgeSearchService knowledgeSearchService;
    private final KnowledgeSuggestService knowledgeSuggestService;

    private final VerticalLayout serverList = new VerticalLayout();
    private final Button shipButton;
    private String selectedName;

    public ShipyardView(BundleExportService bundleExportService, DeployTargetService deployTargetService,
            ConnectionSnippetService connectionSnippetService, ShipyardService shipyardService,
            RunPlanner runPlanner, ToolCategoryCatalog categoryCatalog, KnowledgeSearchService knowledgeSearchService,
            KnowledgeSuggestService knowledgeSuggestService) {
        this.bundleExportService = bundleExportService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.knowledgeSuggestService = knowledgeSuggestService;
        this.deployTargetService = deployTargetService;
        this.connectionSnippetService = connectionSnippetService;
        this.shipyardService = shipyardService;
        this.runPlanner = runPlanner;
        this.categoryCatalog = categoryCatalog;

        this.serverList.setPadding(false);
        this.serverList.setSpacing(false);
        configureSidebar(this.serverList, "Servers");
        setSidebarSplitterPosition(20);
        setHeaderLabel("Shipyard");
        this.shipButton = new Button("Ship New Server", VaadinIcon.PLUS.create(),
                e -> openShipFlow(null));
        this.shipButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        Button targetsButton = new Button("Targets", VaadinIcon.SERVER.create(),
                e -> new TargetsDialog(this.deployTargetService, this::refreshList).open());
        targetsButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        targetsButton.setTooltipText("Where Shipyard servers run: this machine, remote Docker hosts");
        addHeaderAction(targetsButton);
        addHeaderAction(this.shipButton);
        refreshList();
    }

    public static void requestShipFlowWith(List<String> toolIds) {
        requestShipFlowWith(new ShipRequest(null, null, null, toolIds, null, null, null));
    }

    public static void requestShipFlowWith(ShipRequest prefill) {
        requestShipFlowWith(ShipFlowPrefill.of(prefill));
    }

    public static void requestShipFlowWith(ShipFlowPrefill prefill) {
        UI ui = UI.getCurrent();
        ComponentUtil.setData(ui, FLOW_PRESELECTION_KEY, prefill);
        ui.navigate(ShipyardView.class);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        Object preselection = ComponentUtil.getData(attachEvent.getUI(), FLOW_PRESELECTION_KEY);
        if (preselection instanceof ShipFlowPrefill prefill) {
            ComponentUtil.setData(attachEvent.getUI(), FLOW_PRESELECTION_KEY, null);
            openShipFlow(prefill);
        }
    }

    private void openShipFlow(ShipFlowPrefill prefill) {
        this.shipButton.setEnabled(false);
        setContent(new ShipFlowView(this.shipyardService, this.bundleExportService, this.categoryCatalog,
                this.knowledgeSearchService, this.knowledgeSuggestService, prefill, this::showSuccess,
                this::closeShipFlow));
    }

    private void closeShipFlow() {
        this.shipButton.setEnabled(true);
        refreshList();
    }

    private void showSuccess(String serverName) {
        this.shipButton.setEnabled(true);
        this.selectedName = serverName;
        refreshSidebar();
        BundleEntry entry = this.shipyardService.entry(serverName);
        setContent(ShipFlowView.successPanel(serverName, entry.dir(), entry.effectiveHttpPort(),
                () -> openRunPlan(entry), () -> refreshAndSelect(serverName)));
    }

    private void refreshAndSelect(String name) {
        this.selectedName = name;
        refreshList();
    }

    private void refreshList() {
        List<BundleEntry> entries = refreshSidebar();
        if (entries.isEmpty()) {
            this.selectedName = null;
            setContent(emptyState());
            return;
        }
        entries.stream().filter(entry -> entry.name().equals(this.selectedName)).findFirst()
                .ifPresent(entry -> setContent(serverPage(entry)));
    }

    private List<BundleEntry> refreshSidebar() {
        List<ServerSummary> servers = this.shipyardService.servers();
        List<BundleEntry> entries = servers.stream().flatMap(server -> server.entry().stream()).toList();
        this.serverList.removeAll();
        if (!entries.isEmpty()
                && entries.stream().noneMatch(entry -> entry.name().equals(this.selectedName))) {
            this.selectedName = entries.getFirst().name();
        }
        for (ServerSummary server : servers) {
            this.serverList.add(server.entry()
                    .map(entry -> listCard(entry, server.outOfDate(), server.problem()))
                    .orElseGet(() -> declaredCard(server.name(), server.problem())));
        }
        return entries;
    }

    private Div declaredCard(String name, String problem) {
        Span title = new Span(name);
        title.getStyle().set("font-weight", "600").set("display", "block")
                .set("font-size", "var(--lumo-font-size-s)");
        Span summary = new Span("Declared in configuration, " + problem);
        summary.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-error-text-color)").set("display", "block");
        Div text = new Div(title, summary);
        HorizontalLayout row = new HorizontalLayout(VaadinIcon.EXCLAMATION_CIRCLE_O.create(), text);
        row.setAlignItems(HorizontalLayout.Alignment.CENTER);
        row.setWidthFull();
        row.getStyle().set("gap", "var(--lumo-space-s)");
        Div card = new Div(row);
        card.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("width", "100%").set("box-sizing", "border-box");
        return card;
    }

    private Div listCard(BundleEntry entry, boolean outOfDate, String problem) {
        Div avatar = ShipyardVisuals.serverAvatar(entry.name(),
                ShipyardVisuals.AvatarSize.SIDEBAR);

        Span name = new Span(entry.name());
        name.getStyle().set("font-weight", "600").set("display", "block")
                .set("font-size", "var(--lumo-font-size-s)");
        Span summary = new Span(ShipyardVisuals.toolCount(entry.manifest().contents().toolCount()) + " · v"
                + entry.manifest().server().version()
                + (outOfDate ? " · out of date" : ""));
        summary.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block");
        Div text = new Div(name, summary);
        if (problem != null) {
            Span warning = new Span(problem);
            warning.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                    .set("color", "var(--lumo-error-text-color)").set("display", "block");
            text.add(warning);
        }

        HorizontalLayout row = new HorizontalLayout(avatar, text);
        row.setAlignItems(HorizontalLayout.Alignment.CENTER);
        row.setWidthFull();
        row.getStyle().set("gap", "var(--lumo-space-s)");

        Div card = new Div(row);
        card.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("cursor", "pointer").set("width", "100%").set("box-sizing", "border-box");
        if (entry.name().equals(this.selectedName)) {
            card.getStyle().set("background", "var(--lumo-primary-color-10pct)");
        }
        card.addClickListener(e -> refreshAndSelect(entry.name()));
        return card;
    }

    private Div emptyState() {
        VerticalLayout layout = new VerticalLayout();
        layout.setAlignItems(VerticalLayout.Alignment.CENTER);
        layout.add(new H3("Anything as an MCP server"));
        Span copy = new Span("Compose your Local Pass tools, proxied external tools and the knowledge you "
                + "embedded in Vector Database into a standalone MCP server, run it with Docker and hand out the "
                + "connection.");
        copy.getStyle().set("max-width", "480px").set("text-align", "center");
        Button shipNew = new Button("Ship New Server", VaadinIcon.PLUS.create(),
                e -> openShipFlow(null));
        shipNew.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button openVectorDatabase = new Button("Open Vector Database", VaadinIcon.DATABASE.create(),
                e -> UI.getCurrent().navigate(VectorStoreView.class));
        openVectorDatabase.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        openVectorDatabase.setTooltipText("Embed documents first to ship them as search tools");
        layout.add(copy, new HorizontalLayout(shipNew, openVectorDatabase));
        Div wrapper = new Div(layout);
        wrapper.setSizeFull();
        return wrapper;
    }

    private ServerPageView serverPage(BundleEntry entry) {
        ShipyardService.ServerState state = this.shipyardService.state(entry.name());
        Span testResult = new Span();
        testResult.getStyle().set("font-size", "var(--lumo-font-size-s)");

        ServerPageView.Actions actions = new ServerPageView.Actions(
                () -> openRunPlan(entry),
                () -> stopContainer(entry),
                () -> showLogs(entry),
                () -> rebuildBundle(entry),
                () -> deleteBundle(entry),
                () -> addToThisApp(entry),
                () -> testConnection(entry, testResult));
        return new ServerPageView(state.entry(), state.target(), state.docker(), state.status(),
                state.managedByConfiguration(), this.bundleExportService.image(), state.authToken(),
                this.connectionSnippetService, this.shipyardService, testResult, state.changes(), actions);
    }

    private void openRunPlan(BundleEntry entry) {
        new RunPlanDialog(entry, this.bundleExportService.image(), this.runPlanner,
                () -> awaitContainerReady(entry.name())).open();
    }

    private void awaitContainerReady(String serverName) {
        refreshList();
        UI ui = UI.getCurrent();
        this.deployTargetService.awaitSettled(serverName, status -> ui.access(this::refreshList));
    }

    private void stopContainer(BundleEntry entry) {
        try {
            this.shipyardService.stop(entry.name());
            refreshList();
        } catch (RuntimeException e) {
            VaadinUtils.showErrorNotification(e.getMessage());
        }
    }

    private void rebuildBundle(BundleEntry entry) {
        try {
            openShipFlow(ShipFlowPrefill.of(this.shipyardService.rebuildRequest(entry.name())));
        } catch (IllegalStateException e) {
            VaadinUtils.showErrorNotification(e.getMessage());
        }
    }

    private void deleteBundle(BundleEntry entry) {
        try {
            this.shipyardService.requireMutable(entry.name(), "deleting");
        } catch (IllegalStateException e) {
            VaadinUtils.showErrorNotification(e.getMessage());
            return;
        }
        Dialog dialog = VaadinUtils.headerDialog("Delete Server: " + entry.name());
        dialog.setModality(ModalityMode.STRICT);
        dialog.add("Delete the bundle at " + entry.dir() + " permanently? Images you already built or "
                + "pushed are not affected.");
        Button deleteButton = new Button("Delete", e -> {
            dialog.close();
            try {
                this.shipyardService.delete(entry.name());
                this.selectedName = null;
            } catch (RuntimeException ex) {
                VaadinUtils.showErrorNotification(ex.getMessage());
            }
            refreshList();
        });
        deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        dialog.getFooter().add(deleteButton);
        dialog.open();
        deleteButton.focus();
    }

    private void showLogs(BundleEntry entry) {
        TextArea logsArea = new TextArea();
        logsArea.setValue(this.shipyardService.logsTail(entry.name(), 200));
        logsArea.setReadOnly(true);
        logsArea.setSizeFull();
        Dialog dialog = new Dialog(logsArea);
        dialog.setHeaderTitle("Logs: " + DockerCliService.containerName(entry.name()) + " on "
                + this.deployTargetService.targetOf(entry.name()).name());
        dialog.setWidth("720px");
        dialog.setHeight("520px");
        dialog.open();
    }

    private void addToThisApp(BundleEntry entry) {
        try {
            this.shipyardService.addToThisApp(entry.name());
            VaadinUtils.showInfoNotification("Added '" + entry.name()
                    + "' to this app; open MCP Server to connect it");
        } catch (RuntimeException e) {
            VaadinUtils.showErrorNotification("Could not add the server: " + e.getMessage());
        }
    }

    private void testConnection(BundleEntry entry, Span testResult) {
        boolean ok;
        String message;
        try {
            McpClientService.TestConnectionResult result = this.shipyardService.testConnection(entry.name());
            ok = result.ok();
            message = ok ? "OK - " + result.toolCount() + " tool(s)" : "Failed: " + result.error();
        } catch (RuntimeException e) {
            ok = false;
            message = "Failed: " + e.getMessage();
        }
        testResult.setText(message);
        testResult.getStyle().set("color", ok
                ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
    }

}
