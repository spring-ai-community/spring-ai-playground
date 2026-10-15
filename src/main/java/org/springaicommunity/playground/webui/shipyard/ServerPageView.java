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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import org.springaicommunity.playground.service.mcp.bundle.BundleArtifactWriter;
import org.springaicommunity.playground.service.mcp.bundle.BundleDependencyMonitor;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.Change;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.SourceChange;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.BundleFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class ServerPageView extends Div {

    public record Actions(Runnable run, Runnable stop, Runnable logs, Runnable rebuild, Runnable delete,
            Runnable addToThisApp, Runnable testConnection) {}

    private final ShipyardService shipyardService;
    private final ConnectionSnippetService connectionSnippetService;
    private final Map<Tab, Component> panelByTab = new LinkedHashMap<>();
    private final Div contentArea = new Div();
    private final Tabs tabs = new Tabs();

    public ServerPageView(BundleEntry entry, DeployTarget target, DockerAvailability docker,
            ContainerStatus status, boolean managedByConfiguration, String image, Optional<String> authToken,
            ConnectionSnippetService connectionSnippetService, ShipyardService shipyardService, Span testResult,
            List<SourceChange> changes, Actions actions) {
        this.shipyardService = shipyardService;
        this.connectionSnippetService = connectionSnippetService;
        int hostPort = status.hostPort() != null ? status.hostPort() : entry.effectiveHttpPort();

        add(hero(entry, target, docker, status, managedByConfiguration, !changes.isEmpty(), actions));
        if (!changes.isEmpty()) {
            add(outOfDateBanner(changes, managedByConfiguration,
                    status.state() == ContainerState.RUNNING || status.state() == ContainerState.STARTING, actions));
        }

        addTab("Overview", overviewPanel(entry, target, status, image, hostPort, authToken,
                connectionSnippetService, testResult, actions));
        addTab("Tools (" + entry.manifest().contents().toolCount() + ")", toolsPanel(entry));
        if (!entry.manifest().contents().ragPipelines().isEmpty()) {
            addTab("Knowledge", knowledgePanel(entry));
        }
        addTab("Publish", publishPanel(entry, target));
        addTab("Files", filesPanel(entry));
        this.tabs.addSelectedChangeListener(e -> {
            this.contentArea.removeAll();
            this.contentArea.add(this.panelByTab.get(e.getSelectedTab()));
        });
        this.contentArea.getStyle().set("padding-top", "var(--lumo-space-m)");
        this.contentArea.add(this.panelByTab.values().iterator().next());

        add(this.tabs, this.contentArea);
        getStyle().set("padding", "var(--lumo-space-m) var(--lumo-space-l)")
                .set("overflow-y", "auto").set("height", "100%");
    }

    private void addTab(String label, Component panel) {
        Tab tab = new Tab(label);
        this.tabs.add(tab);
        this.panelByTab.put(tab, panel);
    }

    private Component hero(BundleEntry entry, DeployTarget target, DockerAvailability docker,
            ContainerStatus status, boolean managedByConfiguration, boolean outOfDate, Actions actions) {
        Div avatar = ShipyardVisuals.serverAvatar(entry.name(), ShipyardVisuals.AvatarSize.HERO);

        H2 title = new H2(entry.name());
        title.getStyle().set("margin", "0");
        VerticalLayout identity = new VerticalLayout(title);
        identity.setPadding(false);
        identity.setSpacing(false);
        String description = entry.manifest().server().description();
        if (description != null && !description.isBlank()) {
            Span descriptionSpan = new Span(description);
            descriptionSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)").set("max-width", "62ch");
            identity.add(descriptionSpan);
        }
        Span statusChip = ShipyardVisuals.statusChip(target, docker, status);
        if (!status.unavailable().isEmpty()) {
            statusChip.setTitle(status.unavailable().stream()
                    .map(dependency -> dependency.name() + ": " + dependency.detail())
                    .collect(Collectors.joining("\n")));
        }
        HorizontalLayout chips = new HorizontalLayout(statusChip,
                ShipyardVisuals.badge(ShipyardVisuals.toolCount(entry.manifest().contents().toolCount())),
                ShipyardVisuals.badge(ShipyardVisuals.modelBadge(entry.manifest().requires().models())),
                ShipyardVisuals.badge("v" + entry.manifest().server().version()));
        if (outOfDate) {
            Span stale = ShipyardVisuals.badge("Out of date");
            stale.getStyle().set("background", "var(--lumo-warning-color-10pct)");
            chips.add(stale);
        }
        if (managedByConfiguration) {
            Span managed = ShipyardVisuals.badge("Managed by configuration");
            managed.setTitle("Defined under " + BundleExportService.CONFIGURED_SERVERS_PROPERTY
                    + "; edit the configuration and restart to change it");
            chips.add(managed);
        }
        chips.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap")
                .set("margin-top", "var(--lumo-space-xs)");
        identity.add(chips);

        boolean running = status.state() == ContainerState.RUNNING;
        boolean starting = status.state() == ContainerState.STARTING;
        Button primary;
        if (running) {
            primary = new Button("Connect", VaadinIcon.LINK.create(), e -> showOverviewTab());
        } else if (starting) {
            primary = new Button("Starting", VaadinIcon.HOURGLASS.create());
            primary.setTooltipText("Waiting for the container to report healthy at /actuator/health");
        } else {
            primary = new Button("Run", VaadinIcon.PLAY.create(), e -> actions.run().run());
        }
        primary.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        primary.setEnabled(running || (!starting && docker.cliFound()));
        if (!running && !starting && !docker.cliFound()) {
            primary.setTooltipText("Docker CLI not found; install Docker Desktop or run the commands "
                    + "from README.md");
        } else if (!running && !starting && !docker.daemonUp()) {
            primary.setTooltipText("Docker on target '" + target.name() + "' is not reachable; pick another "
                    + "target in the run dialog");
        }

        MenuBar overflow = new MenuBar();
        overflow.addThemeVariants(MenuBarVariant.LUMO_TERTIARY);
        MenuItem more = overflow.addItem(VaadinIcon.ELLIPSIS_DOTS_H.create());
        more.getSubMenu().addItem("Stop", e -> actions.stop().run()).setEnabled(running || starting);
        more.getSubMenu().addItem("Logs", e -> actions.logs().run())
                .setEnabled(status.state() != ContainerState.ABSENT);
        more.getSubMenu().addItem("Rebuild", e -> actions.rebuild().run()).setEnabled(!managedByConfiguration);
        more.getSubMenu().addItem("Delete", e -> actions.delete().run()).setEnabled(!managedByConfiguration);

        HorizontalLayout ctas = new HorizontalLayout(primary, overflow);
        ctas.setAlignItems(HorizontalLayout.Alignment.CENTER);
        ctas.getStyle().set("gap", "var(--lumo-space-xs)");

        HorizontalLayout row = new HorizontalLayout(avatar, identity, ctas);
        row.setWidthFull();
        row.setAlignItems(HorizontalLayout.Alignment.START);
        row.expand(identity);
        return row;
    }

    private Component outOfDateBanner(List<SourceChange> changes, boolean managedByConfiguration, boolean live,
            Actions actions) {
        Span title = new Span("Sources changed since this bundle was shipped");
        title.getStyle().set("font-weight", "600").set("display", "block");
        Span detail = new Span(changes.stream()
                .map(change -> change.label() + (change.change() == Change.REMOVED ? " was removed" : " changed"))
                .collect(Collectors.joining(", ")) + (managedByConfiguration
                        ? ". The configured server rebuilds on the next start." : ". The bundle still ships the "
                                + "versions it was built with; rebuild to ship the current ones."));
        detail.getStyle().set("font-size", "var(--lumo-font-size-s)");
        Div text = new Div(title, detail);
        HorizontalLayout banner = new HorizontalLayout(text);
        banner.setWidthFull();
        banner.setAlignItems(HorizontalLayout.Alignment.CENTER);
        banner.expand(text);
        if (!managedByConfiguration) {
            Button rebuild = new Button("Rebuild", VaadinIcon.REFRESH.create(), e -> actions.rebuild().run());
            rebuild.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
            if (live) {
                rebuild.setEnabled(false);
                Span stopFirst = new Span("Stop the server to rebuild");
                stopFirst.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                        .set("color", "var(--lumo-secondary-text-color)").set("white-space", "nowrap");
                banner.add(stopFirst);
            }
            banner.add(rebuild);
        }
        banner.getStyle().set("background", "var(--lumo-warning-color-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("margin", "var(--lumo-space-m) 0 0").set("box-sizing", "border-box");
        return banner;
    }

    private void showOverviewTab() {
        this.tabs.setSelectedIndex(0);
    }

    private Component overviewPanel(BundleEntry entry, DeployTarget target, ContainerStatus status, String image,
            int hostPort, Optional<String> authToken, ConnectionSnippetService connectionSnippetService,
            Span testResult, Actions actions) {
        ConnectSection connect = new ConnectSection(entry, target, hostPort, authToken,
                connectionSnippetService, actions.addToThisApp(), actions.testConnection(), testResult);
        connect.setWidthFull();

        Div facts = new Div();
        facts.add(factLine("Target", target.name() + " - " + target.where()),
                factLine("Image", BundleArtifactWriter.localImage(entry.manifest()) + " (on " + image + ")"),
                factLine("Bundle", entry.dir().toString()));
        facts.getStyle().set("margin-top", "var(--lumo-space-m)");

        VerticalLayout panel = new VerticalLayout();
        if (!status.unavailable().isEmpty()) {
            panel.add(unavailableSection(status.unavailable()));
        }
        panel.add(connect, facts);
        panel.setPadding(false);
        panel.setSpacing(false);
        return panel;
    }

    private Component unavailableSection(List<BundleDependencyMonitor.Dependency> unavailable) {
        Div section = new Div();
        Span heading = new Span("Unavailable right now");
        heading.getStyle().set("font-weight", "600").set("display", "block")
                .set("margin-bottom", "var(--lumo-space-xs)");
        section.add(heading);
        for (BundleDependencyMonitor.Dependency dependency : unavailable) {
            String affected = dependency.tools().isEmpty() ? "" : " · affects " + String.join(", ", dependency.tools());
            Span line = new Span(dependency.name() + " (" + dependencyKind(dependency.kind()) + "): "
                    + dependency.detail() + affected);
            line.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)");
            section.add(line);
        }
        Span retry = new Span("The server keeps serving its other tools and retries these automatically.");
        retry.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("margin-top", "var(--lumo-space-xs)");
        section.add(retry);
        section.setWidthFull();
        section.getStyle().set("background", "var(--lumo-warning-color-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("margin-bottom", "var(--lumo-space-m)").set("box-sizing", "border-box");
        return section;
    }

    private static String dependencyKind(BundleDependencyMonitor.Kind kind) {
        return switch (kind) {
            case UPSTREAM -> "upstream server";
            case CHAT_MODEL -> "chat model";
            case EMBEDDING_MODEL -> "embedding model";
        };
    }

    private Component toolsPanel(BundleEntry entry) {
        Grid<BundleManifest.ToolRisk> grid = new Grid<>();
        grid.setItems(entry.manifest().risk().summary());
        grid.addColumn(BundleManifest.ToolRisk::tool).setHeader("Tool").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(toolRisk -> ShipyardVisuals.kindBadge(toolRisk.kind())).setHeader("Kind")
                .setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(toolRisk -> Objects.requireNonNullElse(toolRisk.description(), "")).setHeader("Description");
        grid.addColumn(BundleManifest.ToolRisk::level).setHeader("Risk").setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(toolRisk -> toolRisk.hitl() ? "required" : "-").setHeader("HITL")
                .setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(true);

        VerticalLayout panel = new VerticalLayout(grid);
        panel.setPadding(false);
        if (!entry.manifest().requires().env().isEmpty()) {
            Grid<BundleManifest.EnvVar> envGrid = new Grid<>();
            envGrid.setItems(entry.manifest().requires().env());
            envGrid.addColumn(BundleManifest.EnvVar::name).setHeader("Environment variable");
            envGrid.addColumn(BundleManifest.EnvVar::purpose).setHeader("Purpose");
            envGrid.addColumn(envVar -> envVar.secret() ? "secret" : "-").setHeader("Secret")
                    .setAutoWidth(true).setFlexGrow(0);
            envGrid.setAllRowsVisible(true);
            panel.add(envGrid);
        }
        return panel;
    }

    private record KnowledgeRow(String tool, String source, String document, int chunks) {}

    private Component knowledgePanel(BundleEntry entry) {
        List<KnowledgeRow> rows = new ArrayList<>();
        for (BundleManifest.RagPipelineRef ref : entry.manifest().contents().ragPipelines()) {
            for (BundleManifest.DocumentRef document : ref.documents()) {
                rows.add(new KnowledgeRow(ref.alias(), Objects.requireNonNullElse(ref.source(), ref.id()),
                        document.title(), document.chunks()));
            }
        }
        Span lead = new Span("A snapshot of the documents each knowledge tool searches, taken when the server was "
                + "shipped. Re-ship to pick up changes; the Out of date banner tells you when.");
        lead.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        Grid<KnowledgeRow> grid = new Grid<>();
        grid.setItems(rows);
        grid.addColumn(KnowledgeRow::tool).setHeader("Tool").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(KnowledgeRow::document).setHeader("Document");
        grid.addColumn(KnowledgeRow::chunks).setHeader("Chunks").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(KnowledgeRow::source).setHeader("Source").setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(true);
        VerticalLayout panel = new VerticalLayout(lead, grid);
        panel.setPadding(false);
        return panel;
    }

    private Component publishPanel(BundleEntry entry, DeployTarget target) {
        Span lead = new Span("Push the image you ran on " + target.name() + " under your registry name, then publish the starter "
                + "server.json to the official MCP Registry. Replace OWNER with your GitHub username.");
        lead.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)").set("display", "block");

        TextArea steps = new TextArea("Publish");
        steps.setValue(this.connectionSnippetService.publishSteps(entry, target));
        steps.setReadOnly(true);
        steps.setWidthFull();
        steps.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");

        TextArea serverJson = new TextArea("server.json");
        serverJson.setValue(this.shipyardService.serverJson(entry.name()));
        serverJson.setReadOnly(true);
        serverJson.setWidthFull();
        serverJson.setHeight("260px");
        serverJson.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");

        VerticalLayout panel = new VerticalLayout(lead, steps, serverJson);
        panel.setPadding(false);
        return panel;
    }

    private Component filesPanel(BundleEntry entry) {
        Grid<BundleFile> grid = new Grid<>();
        grid.setItems(this.shipyardService.bundleFiles(entry.name()));
        grid.addColumn(BundleFile::path).setHeader("File");
        grid.addColumn(file -> sizeLabel(file.bytes())).setHeader("Size").setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(true);

        Span folder = new Span(entry.dir().toString());
        folder.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block");

        VerticalLayout panel = new VerticalLayout(folder, grid);
        panel.setPadding(false);
        return panel;
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 0) return "-";
        return bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB";
    }

    private Component factLine(String label, String value) {
        Span line = new Span();
        Span labelSpan = new Span(label + " · ");
        labelSpan.getStyle().set("font-weight", "600");
        line.add(labelSpan, new Span(value));
        line.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block");
        return line;
    }

}
