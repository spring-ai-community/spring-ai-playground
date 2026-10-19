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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.springaicommunity.playground.service.chat.ChatService.RagSource;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ExportResult;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ValidationReport;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeContribution;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.FlowDraft;
import org.springaicommunity.playground.service.mcp.bundle.ShippedTool;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.webui.vectorstore.VectorStoreView;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ShipFlowView extends Div {

    static final int TRY_EXCERPT_CHARS = 400;

    private static final String MODEL_FREE = "Model-free (recommended)";
    private static final String MODEL_DEPENDENT = "Requires a model";

    private final ShipyardService shipyardService;
    private final BundleExportService bundleExportService;
    private final FlowDraft draft;
    private final Consumer<String> onShipped;
    private final Runnable onCancel;

    private final BundleToolSelectionList customToolList;
    private final BundleToolSelectionList builtInToolList;
    private final CheckboxGroup<McpComposition.Member> proxiedToolGroup = new CheckboxGroup<>("Proxied tools");
    private final Dialog customDialog = pickerDialog("Add your tools");
    private final Dialog builtInDialog = pickerDialog("Add built-in tools");
    private final Dialog proxiedDialog = pickerDialog("Add proxied tools");
    private final CheckboxGroup<RagSource> knowledgeGroup = new CheckboxGroup<>("Knowledge");
    private final Dialog knowledgeDialog = pickerDialog("Add knowledge");
    private final Map<String, ShipRequest.Knowledge> knowledgeEdits = new LinkedHashMap<>();
    private final Div shippedToolRows = new Div();
    private final KnowledgeSearchService knowledgeSearchService;
    private final Set<String> expandedKnowledge = new LinkedHashSet<>();
    private final Map<String, String> tryQueries = new HashMap<>();
    private final Map<String, List<String>> tryResults = new HashMap<>();
    private final RiskLevel proxiedMaxRisk;

    private final TextField nameField = new TextField("Server name");
    private final TextArea descriptionField = new TextArea("Description (optional)");
    private final TextField versionField = new TextField("Version");
    private final RadioButtonGroup<Transport> transportGroup = new RadioButtonGroup<>();
    private final IntegerField portField = new IntegerField("Host port");
    private final RadioButtonGroup<String> modelModeGroup = new RadioButtonGroup<>();
    private final Checkbox chatModelCheck = new Checkbox("Chat model required");
    private final ComboBox<String> chatModelCombo = new ComboBox<>("Chat model");
    private final Checkbox embeddingModelCheck = new Checkbox("Embedding model required");
    private final TextField embeddingModelField = new TextField("Embedding model");

    private final Button shipButton = new Button("Ship", VaadinIcon.ANCHOR.create());
    private final Span pathPreview = new Span();
    private final Span toolCount = new Span();
    private final Details advanced = new Details();
    private final Div previewSlot = new Div();
    private final Div issueSlot = new Div();

    public ShipFlowView(ShipyardService shipyardService, BundleExportService bundleExportService,
            ToolCategoryCatalog categoryCatalog, KnowledgeSearchService knowledgeSearchService,
            ShipFlowPrefill prefill, Consumer<String> onShipped, Runnable onCancel) {
        this.shipyardService = shipyardService;
        this.bundleExportService = bundleExportService;
        this.knowledgeSearchService = knowledgeSearchService;
        ShipRequest request = prefill == null ? null : prefill.request();
        if (prefill != null) {
            this.tryQueries.putAll(prefill.tryQueries());
            this.expandedKnowledge.addAll(prefill.tryQueries().keySet());
        }
        this.draft = shipyardService.draft(request);
        this.onShipped = onShipped;
        this.onCancel = onCancel;

        this.customToolList = new BundleToolSelectionList(() -> shipyardService.toolChoices(false), categoryCatalog,
                new BundleToolSelectionList.Config(
                        "Only Local Pass tools - tested, published and with their required environment "
                                + "variables present on this machine - can ship in an MCP server.",
                        "No custom Local Pass tools yet. Build a tool in Tool Studio and "
                                + "Test & Publish it first.",
                        false));
        this.builtInToolList = new BundleToolSelectionList(() -> shipyardService.toolChoices(true), categoryCatalog,
                new BundleToolSelectionList.Config(
                        "Built-in tools ship by id only; the serving image provides their implementation.",
                        "No built-in tools are available.",
                        true));
        this.proxiedMaxRisk = this.draft.proxiedMaxRisk();
        configureProxiedTools(this.draft.proxiedChoices(), this.draft.preselectedProxiedKeys());
        configureKnowledge(this.draft.knowledgeSources(), this.draft.knowledgePrefill());
        configureFields();
        applyPrefill(request);
        this.customToolList.reset(this.draft.customToolIds());
        this.builtInToolList.reset(this.draft.builtInToolIds());

        add(buildBody());
        getStyle().set("padding", "var(--lumo-space-m) var(--lumo-space-l)")
                .set("overflow-y", "auto").set("height", "100%");
        refresh();
    }

    private void configureProxiedTools(List<McpComposition.Member> choices, Set<String> preselectedKeys) {
        this.proxiedToolGroup.setItems(choices);
        this.proxiedToolGroup.setItemLabelGenerator(member -> member.exposedAlias() + " · via " + member.serverId()
                + (member.hitl() ? " · HITL" : ""));
        this.proxiedToolGroup.setHelperText(choices.isEmpty()
                ? "No proxied tools yet. Compose external tools in MCP Server's Built-in MCP Server drawer first."
                : "External tools the built-in server exposes. Only Streamable HTTP upstreams without OAuth can "
                        + "ship.");
        this.proxiedToolGroup.setValue(choices.stream()
                .filter(member -> preselectedKeys.contains(ShipyardService.memberKey(member)))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        this.proxiedToolGroup.addValueChangeListener(e -> refresh());
    }

    private void configureKnowledge(List<RagSource> sources, List<ShipRequest.Knowledge> prefill) {
        prefill.forEach(knowledge -> this.knowledgeEdits.put(knowledge.sourceId(), knowledge));
        this.knowledgeGroup.setItems(sources);
        this.knowledgeGroup.setItemLabelGenerator(source -> source.name() + " · " + source.hint());
        this.knowledgeGroup.setHelperText(sources.isEmpty()
                ? "No knowledge yet. Embed documents in Vector Database first; pipelines and documents both ship."
                : "The same sources Agentic Chat can pick. Each one becomes a retrieval-only search tool; the "
                        + "server needs the embedding model that indexed it.");
        Set<String> preselected = prefill.stream().map(ShipRequest.Knowledge::sourceId)
                .collect(Collectors.toSet());
        this.knowledgeGroup.setValue(sources.stream().filter(source -> preselected.contains(source.sourceId()))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        this.knowledgeGroup.addValueChangeListener(e -> refresh());
    }

    private List<ShipRequest.Knowledge> selectedKnowledge() {
        Set<RagSource> selected = this.knowledgeGroup.getValue();
        return this.knowledgeGroup.getListDataView().getItems().filter(selected::contains)
                .map(source -> this.knowledgeEdits.getOrDefault(source.sourceId(),
                        new ShipRequest.Knowledge(source.sourceId(), null, null, null)))
                .toList();
    }

    private List<McpComposition.Member> selectedProxiedTools() {
        Set<McpComposition.Member> selected = this.proxiedToolGroup.getValue();
        return this.proxiedToolGroup.getListDataView().getItems().filter(selected::contains).toList();
    }

    private void configureFields() {
        this.customToolList.setOnSelectionChange(this::refresh);
        this.builtInToolList.setOnSelectionChange(this::refresh);
        this.customDialog.add(this.customToolList);
        this.builtInDialog.add(this.builtInToolList);
        this.proxiedDialog.add(this.proxiedToolGroup);
        Button openVectorDatabase = new Button("Open Vector Database", VaadinIcon.DATABASE.create(),
                e -> UI.getCurrent().navigate(VectorStoreView.class));
        openVectorDatabase.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        openVectorDatabase.setTooltipText("Embed documents and build pipelines there; they show up here");
        this.knowledgeDialog.add(this.knowledgeGroup, openVectorDatabase);

        this.nameField.setPlaceholder("my-tools-server");
        this.nameField.setWidthFull();
        this.nameField.setValueChangeMode(ValueChangeMode.LAZY);
        this.nameField.addValueChangeListener(e -> refresh());
        this.descriptionField.setPlaceholder("What MCP clients see as the server description");
        this.descriptionField.setWidthFull();
        this.descriptionField.setValueChangeMode(ValueChangeMode.LAZY);
        this.descriptionField.addValueChangeListener(e -> refresh());
        this.versionField.setValue(BundleExportService.DEFAULT_VERSION);
        this.versionField.setWidth("10em");
        this.versionField.setValueChangeMode(ValueChangeMode.LAZY);
        this.versionField.addValueChangeListener(e -> refresh());

        this.transportGroup.setLabel("Transport");
        this.transportGroup.setItems(Transport.HTTP, Transport.STDIO, Transport.BOTH);
        this.transportGroup.setValue(Transport.BOTH);
        this.transportGroup.setItemLabelGenerator(transport -> switch (transport) {
            case HTTP -> "Streamable HTTP";
            case STDIO -> "stdio";
            case BOTH -> "Both";
        });
        this.transportGroup.addValueChangeListener(e -> {
            this.portField.setEnabled(e.getValue() != Transport.STDIO);
            refresh();
        });
        this.portField.setMin(1);
        this.portField.setMax(65535);
        this.portField.setValue(BundleExportService.DEFAULT_HTTP_PORT);
        this.portField.setValueChangeMode(ValueChangeMode.LAZY);
        this.portField.addValueChangeListener(e -> refresh());

        this.modelModeGroup.setLabel("Model dependency");
        this.modelModeGroup.setItems(MODEL_FREE, MODEL_DEPENDENT);
        this.modelModeGroup.setValue(MODEL_FREE);
        this.modelModeGroup.addValueChangeListener(e -> {
            boolean dependent = MODEL_DEPENDENT.equals(e.getValue());
            this.chatModelCheck.setEnabled(dependent);
            this.chatModelCombo.setEnabled(dependent && Boolean.TRUE.equals(this.chatModelCheck.getValue()));
            this.embeddingModelCheck.setEnabled(dependent);
            if (!dependent) {
                this.chatModelCheck.setValue(false);
                this.embeddingModelCheck.setValue(false);
            }
            refresh();
        });
        this.chatModelCheck.setEnabled(false);
        this.chatModelCheck.addValueChangeListener(e -> {
            this.chatModelCombo.setEnabled(Boolean.TRUE.equals(e.getValue()));
            refresh();
        });
        this.chatModelCombo.setItems(this.draft.chatModels());
        this.chatModelCombo.setEnabled(false);
        this.chatModelCombo.addValueChangeListener(e -> refresh());
        this.embeddingModelCheck.setEnabled(false);
        this.embeddingModelCheck.addValueChangeListener(e -> refresh());
        this.embeddingModelField.setReadOnly(true);
        this.embeddingModelField.setValue(this.draft.embeddingModel());
        this.embeddingModelField.setWidthFull();

        this.shipButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        this.shipButton.addClickListener(e -> ship());
        this.advanced.setWidthFull();
        this.advanced.add(advancedBody());
        this.pathPreview.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin-bottom", "var(--lumo-space-s)");
        this.toolCount.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
    }

    private void applyPrefill(ShipRequest prefill) {
        if (prefill == null) {
            return;
        }
        if (prefill.serverName() != null) {
            this.nameField.setValue(prefill.serverName());
        }
        if (prefill.description() != null) {
            this.descriptionField.setValue(prefill.description());
        }
        if (prefill.version() != null && !prefill.version().isBlank()) {
            this.versionField.setValue(prefill.version());
        }
        this.transportGroup.setValue(prefill.transport());
        this.portField.setValue(prefill.effectiveHttpPort());
        BundleManifest.ModelRef chat = prefill.models().chat();
        BundleManifest.ModelRef embedding = prefill.models().embedding();
        if (chat == null && embedding == null) {
            return;
        }
        this.modelModeGroup.setValue(MODEL_DEPENDENT);
        if (chat != null) {
            this.chatModelCheck.setValue(true);
            this.chatModelCombo.setValue(chat.model());
        }
        this.embeddingModelCheck.setValue(embedding != null);
    }

    private Component buildBody() {
        Button cancel = new Button("Cancel", e -> this.onCancel.run());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        H2 heading = new H2("Ship a new server");
        heading.getStyle().set("margin", "0");
        HorizontalLayout topBar = new HorizontalLayout(heading, cancel, this.shipButton);
        topBar.setWidthFull();
        topBar.setAlignItems(HorizontalLayout.Alignment.CENTER);
        topBar.expand(heading);
        topBar.getStyle().set("margin-bottom", "var(--lumo-space-m)");

        Span whatGoesInLead = new Span("Everything this server exposes, exactly as MCP clients will see it.");
        whatGoesInLead.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin-bottom", "var(--lumo-space-s)");
        HorizontalLayout addButtons = new HorizontalLayout(addButton("Your tools", this.customDialog),
                addButton("Built-in", this.builtInDialog), addButton("Proxied", this.proxiedDialog),
                addButton("Knowledge", this.knowledgeDialog));
        addButtons.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap")
                .set("margin-bottom", "var(--lumo-space-s)");
        this.shippedToolRows.setWidthFull();
        this.shippedToolRows.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)").set("overflow", "hidden");
        VerticalLayout form = new VerticalLayout(
                sectionHeader("What goes in"), whatGoesInLead, addButtons, this.shippedToolRows, this.toolCount,
                sectionHeader("Name it"), this.nameField, this.pathPreview, this.versionField,
                this.descriptionField, this.advanced);
        form.setPadding(false);
        form.setSpacing(false);
        form.setMaxWidth("640px");

        VerticalLayout rail = new VerticalLayout(this.previewSlot, this.issueSlot);
        rail.setPadding(false);
        rail.setSpacing(false);
        rail.setWidth("360px");
        rail.getStyle().set("flex", "none");

        HorizontalLayout columns = new HorizontalLayout(form, rail);
        columns.setWidthFull();
        columns.getStyle().set("gap", "var(--lumo-space-xl)").set("align-items", "flex-start");
        columns.setFlexGrow(1, form);

        VerticalLayout body = new VerticalLayout(topBar, columns);
        body.setPadding(false);
        body.setSpacing(false);
        return body;
    }

    private Component sectionHeader(String label) {
        Span span = new Span(label);
        span.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "700")
                .set("text-transform", "uppercase").set("letter-spacing", "0.05em")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin", "var(--lumo-space-m) 0 var(--lumo-space-xs)").set("display", "block");
        return span;
    }

    private Component advancedBody() {
        VerticalLayout layout = new VerticalLayout(this.transportGroup, this.portField,
                this.modelModeGroup, this.chatModelCheck, this.chatModelCombo,
                this.embeddingModelCheck, this.embeddingModelField);
        layout.setPadding(false);
        return layout;
    }

    private void refresh() {
        ShipRequest request = this.bundleExportService.normalize(currentRequest());
        ValidationReport report = this.bundleExportService.validate(request);
        List<String> blockers = new ArrayList<>(modelBlockers());
        blockers.addAll(report.blockers());

        renderShippedTools(report.tools());
        this.toolCount.setText(ShipyardVisuals.toolCount(report.tools().size()));
        this.pathPreview.setText(BundleRegistryService.BUNDLES_DIR + "/"
                + (request.serverName() == null || request.serverName().isBlank()
                        ? "<name>" : request.serverName()) + "/");
        this.advanced.setSummaryText(advancedSummary(request));
        this.shipButton.setEnabled(blockers.isEmpty());

        this.previewSlot.removeAll();
        this.previewSlot.add(previewCard(request, report));
        this.issueSlot.removeAll();
        blockers.forEach(blocker -> this.issueSlot.add(issueLine(blocker, true)));
        report.warnings().forEach(warning -> this.issueSlot.add(issueLine(warning, false)));
        if (blockers.isEmpty()) {
            Span ready = new Span("Ready to ship");
            ready.getStyle().set("color", "var(--lumo-success-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)").set("display", "block")
                    .set("margin-top", "var(--lumo-space-s)");
            this.issueSlot.add(ready);
        }
    }

    private String advancedSummary(ShipRequest request) {
        String transport = switch (request.transport()) {
            case HTTP -> "Streamable HTTP";
            case STDIO -> "stdio";
            case BOTH -> "Streamable HTTP + stdio";
        };
        return "Advanced - " + transport + " · port " + request.effectiveHttpPort() + " · "
                + ShipyardVisuals.modelBadge(request.models()) + " · image "
                + this.bundleExportService.imageTag();
    }

    private static Dialog pickerDialog(String title) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("720px");
        dialog.setMaxHeight("80vh");
        Button doneButton = new Button("Done", e -> dialog.close());
        doneButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(doneButton);
        return dialog;
    }

    private static Button addButton(String label, Dialog dialog) {
        Button button = new Button("+ " + label, e -> dialog.open());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }

    private void renderShippedTools(List<ShippedTool> tools) {
        this.shippedToolRows.removeAll();
        if (tools.isEmpty()) {
            Span empty = new Span("Nothing added yet. Add your Local Pass tools, built-in tools, proxied "
                    + "external tools or knowledge.");
            empty.getStyle().set("display", "block").set("padding", "var(--lumo-space-m)")
                    .set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
            this.shippedToolRows.add(empty);
            return;
        }
        tools.forEach(tool -> this.shippedToolRows.add(shippedToolRow(tool)));
    }

    private Component shippedToolRow(ShippedTool tool) {
        Span kind = ShipyardVisuals.kindBadge(tool.kind().name());
        kind.setWidth("5.5em");
        Span name = new Span(tool.name());
        name.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)").set("flex", "none");
        Span description = new Span(Objects.requireNonNullElse(tool.description(), ""));
        description.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)").set("overflow", "hidden")
                .set("text-overflow", "ellipsis").set("white-space", "nowrap").set("min-width", "0");
        Span risk = new Span(tool.riskLabel());
        risk.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("flex", "none");
        Button remove = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> remove(tool));
        remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        remove.setAriaLabel("Remove " + tool.name());
        remove.setTooltipText("Remove " + tool.name());
        HorizontalLayout row = new HorizontalLayout(kind, name, description, risk);
        Optional<ShipRequest.Knowledge> knowledge = tool.kind() == ShippedTool.Kind.KNOWLEDGE
                ? knowledgeByToolName(tool.name()) : Optional.empty();
        boolean expanded = knowledge.map(entry -> this.expandedKnowledge.contains(entry.sourceId())).orElse(false);
        knowledge.ifPresent(entry -> {
            Button toggle = new Button((expanded ? VaadinIcon.ANGLE_UP : VaadinIcon.ANGLE_DOWN).create(), e -> {
                if (!this.expandedKnowledge.remove(entry.sourceId())) this.expandedKnowledge.add(entry.sourceId());
                refresh();
            });
            toggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            toggle.setAriaLabel("Edit " + tool.name());
            toggle.setTooltipText("Edit tool name, description and top_k, and try a search");
            row.add(toggle);
        });
        row.add(remove);
        row.setWidthFull();
        row.setAlignItems(HorizontalLayout.Alignment.CENTER);
        row.expand(description);
        row.getStyle().set("gap", "var(--lumo-space-s)").set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("box-sizing", "border-box");
        Div block = new Div(row);
        if (expanded) block.add(knowledgePanel(knowledge.get(), tool.name()));
        block.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-5pct)");
        return block;
    }

    private Component knowledgePanel(ShipRequest.Knowledge current, String toolName) {
        TextField nameField = new TextField("Tool name");
        nameField.setValue(Objects.requireNonNullElse(current.toolName(), ""));
        nameField.setHelperText("Lowercase letters, digits and '_' (max 64); what MCP clients call");
        nameField.addValueChangeListener(e -> saveKnowledge(current, e.getValue(), current.description(),
                current.topK()));
        TextArea descriptionField = new TextArea("Description");
        descriptionField.setValue(Objects.requireNonNullElse(current.description(), ""));
        descriptionField.setHelperText("What clients read to decide when to search here");
        descriptionField.setWidthFull();
        descriptionField.addValueChangeListener(e -> saveKnowledge(current, current.toolName(), e.getValue(),
                current.topK()));
        IntegerField topKField = new IntegerField("Default top_k");
        topKField.setValue(current.topK());
        topKField.setMin(1);
        topKField.setMax(KnowledgeContribution.MAX_TOP_K);
        topKField.addValueChangeListener(e -> saveKnowledge(current, current.toolName(), current.description(),
                e.getValue()));
        HorizontalLayout fields = new HorizontalLayout(nameField, topKField);
        fields.setWidthFull();
        fields.expand(nameField);

        TextField queryField = new TextField("Try a search");
        queryField.setPlaceholder("What would a client ask this tool?");
        queryField.setValue(this.tryQueries.getOrDefault(current.sourceId(), ""));
        queryField.setWidthFull();
        queryField.setValueChangeMode(ValueChangeMode.EAGER);
        queryField.addValueChangeListener(e -> this.tryQueries.put(current.sourceId(), e.getValue()));
        Div results = new Div();
        results.getStyle().set("font-size", "var(--lumo-font-size-s)").set("white-space", "pre-wrap")
                .set("max-height", "320px").set("overflow-y", "auto");
        renderTryResults(results, current.sourceId());
        Button run = new Button("Run", VaadinIcon.PLAY.create(), e -> {
            this.tryResults.put(current.sourceId(), trySearch(current, queryField.getValue()));
            renderTryResults(results, current.sourceId());
        });
        run.setAriaLabel("Try " + toolName);
        run.addThemeVariants(ButtonVariant.LUMO_SMALL);
        HorizontalLayout tryRow = new HorizontalLayout(queryField, run);
        tryRow.setWidthFull();
        tryRow.setAlignItems(HorizontalLayout.Alignment.END);
        tryRow.expand(queryField);

        VerticalLayout panel = new VerticalLayout(fields, descriptionField, tryRow, results);
        panel.setSpacing(false);
        panel.getStyle().set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)");
        return panel;
    }

    private void saveKnowledge(ShipRequest.Knowledge current, String toolName, String description, Integer topK) {
        this.knowledgeEdits.put(current.sourceId(), new ShipRequest.Knowledge(current.sourceId(),
                toolName == null ? null : toolName.strip(), description == null ? null : description.strip(), topK));
        refresh();
    }

    private List<String> trySearch(ShipRequest.Knowledge current, String query) {
        if (query == null || query.isBlank()) return List.of("Enter a query first.");
        try {
            KnowledgeSearchService.SearchResult result = this.knowledgeSearchService.search(current.sourceId(),
                    query, current.topK(), Map.of());
            List<String> lines = new ArrayList<>(result.asTexts().stream()
                    .map(text -> text.length() > TRY_EXCERPT_CHARS ? text.substring(0, TRY_EXCERPT_CHARS) + "…" : text)
                    .toList());
            if (result.truncated()) lines.add("(more excerpts cut by the output budget)");
            return lines;
        } catch (RuntimeException e) {
            return List.of("Search failed: " + e.getMessage());
        }
    }

    private void renderTryResults(Div results, String sourceId) {
        results.removeAll();
        List<String> lines = this.tryResults.get(sourceId);
        if (lines == null) return;
        lines.forEach(line -> {
            Span excerpt = new Span(line);
            excerpt.getStyle().set("display", "block").set("padding", "var(--lumo-space-xs) 0")
                    .set("border-top", "1px solid var(--lumo-contrast-10pct)");
            results.add(excerpt);
        });
    }

    private void remove(ShippedTool tool) {
        switch (tool.kind()) {
            case PROXIED -> {
                Set<McpComposition.Member> remaining = new LinkedHashSet<>(this.proxiedToolGroup.getValue());
                remaining.removeIf(member -> member.exposedAlias().equals(tool.name()));
                this.proxiedToolGroup.setValue(remaining);
            }
            case TOOL -> deselect(this.customToolList, tool.name());
            case BUILT_IN -> deselect(this.builtInToolList, tool.name());
            case KNOWLEDGE -> knowledgeByToolName(tool.name()).ifPresent(knowledge -> {
                Set<RagSource> remaining = new LinkedHashSet<>(this.knowledgeGroup.getValue());
                remaining.removeIf(source -> source.sourceId().equals(knowledge.sourceId()));
                this.knowledgeEdits.remove(knowledge.sourceId());
                this.knowledgeGroup.setValue(remaining);
            });
        }
        refresh();
    }

    private Optional<ShipRequest.Knowledge> knowledgeByToolName(String toolName) {
        return this.bundleExportService.normalize(currentRequest()).knowledge().stream()
                .filter(knowledge -> toolName.equals(knowledge.toolName())).findFirst();
    }

    private static void deselect(BundleToolSelectionList list, String toolName) {
        list.reset(list.getSelectedTools().stream().filter(spec -> !spec.name().equals(toolName))
                .map(ToolSpec::toolId).toList());
    }

    private Component needsBlock(ShipRequest request, ValidationReport report) {
        Div needs = new Div();
        Span caption = new Span("Needs at run time");
        caption.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "700")
                .set("text-transform", "uppercase").set("letter-spacing", "0.05em")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin", "var(--lumo-space-s) 0 var(--lumo-space-xs)");
        needs.add(caption);
        BundleManifest.Models models = request.models();
        if (models.modelFree()) {
            needs.add(needLine("No model", "boots without any model provider"));
        }
        if (models.chat() != null) {
            needs.add(needLine("Chat model", models.chat().model() + " (" + models.chat().provider() + ")"
                    + chatReason(request)));
        }
        if (models.embedding() != null) {
            needs.add(needLine("Embedding model", models.embedding().model() + " ("
                    + models.embedding().provider() + ")"));
        }
        request.composedTools().stream().map(McpComposition.Member::serverId).distinct()
                .forEach(server -> needs.add(needLine("Upstream", server + ", reachable from the container")));
        snapshotLine(request).ifPresent(needs::add);
        long secrets = report.secretEnvCount();
        if (!report.envRequirements().isEmpty()) {
            needs.add(needLine("Environment", report.envRequirements().size() + " variable(s)"
                    + (secrets > 0 ? ", " + secrets + " secret" : "") + ", supplied when you run it"));
        }
        return needs;
    }

    private String chatReason(ShipRequest request) {
        List<String> reasons = new ArrayList<>();
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            this.knowledgeSearchService.snapshot(knowledge.sourceId())
                    .filter(snapshot -> !snapshot.llmStages().isEmpty())
                    .ifPresent(snapshot -> reasons.add(knowledge.toolName() + " runs "
                            + String.join(", ", snapshot.llmStages())));
        }
        return reasons.isEmpty() ? "" : ", needed because " + String.join("; ", reasons);
    }

    private Optional<Component> snapshotLine(ShipRequest request) {
        if (request.knowledge().isEmpty()) return Optional.empty();
        int documents = 0;
        int chunks = 0;
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            Optional<KnowledgeSearchService.Snapshot> snapshot = this.knowledgeSearchService.snapshot(
                    knowledge.sourceId());
            documents += snapshot.map(KnowledgeSearchService.Snapshot::documents).orElse(0);
            chunks += snapshot.map(KnowledgeSearchService.Snapshot::chunks).orElse(0);
        }
        return Optional.of(needLine("Snapshot", documents + " document(s), " + chunks
                + " chunks frozen into the bundle; size shows after shipping"));
    }

    private static Component needLine(String label, String value) {
        Span strong = new Span(label);
        strong.getStyle().set("font-weight", "600");
        Span line = new Span(strong, new Span(" · " + value));
        line.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)")
                .set("padding", "1px 0");
        return line;
    }

    private Component previewCard(ShipRequest request, ValidationReport report) {
        String name = request.serverName() == null || request.serverName().isBlank()
                ? "unnamed server" : request.serverName();

        Div avatar = ShipyardVisuals.serverAvatar(name, ShipyardVisuals.AvatarSize.PREVIEW);
        Span title = new Span(name);
        title.getStyle().set("font-weight", "700").set("font-size", "var(--lumo-font-size-l)")
                .set("display", "block");
        Span subtitle = new Span("v" + request.version() + " · not shipped yet");
        subtitle.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("display", "block");
        HorizontalLayout top = new HorizontalLayout(avatar, new Div(title, subtitle));
        top.setAlignItems(HorizontalLayout.Alignment.CENTER);

        HorizontalLayout chips = new HorizontalLayout(
                ShipyardVisuals.badge(ShipyardVisuals.toolCount(report.tools().size())),
                ShipyardVisuals.badge(ShipyardVisuals.modelBadge(request.models())),
                ShipyardVisuals.badge(transportBadge(request)));
        chips.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap")
                .set("margin", "var(--lumo-space-s) 0");

        Div tools = new Div();
        for (ShippedTool tool : report.tools()) {
            Span line = new Span(tool.name());
            line.getStyle().set("font-size", "var(--lumo-font-size-s)").set("display", "block")
                    .set("padding", "2px 0").set("font-family", "var(--lumo-font-family-monospace, monospace)");
            tools.add(line);
        }

        Span endpointLabel = new Span("Will listen on");
        endpointLabel.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin-top", "var(--lumo-space-s)");
        Span endpoint = new Span("port " + request.effectiveHttpPort() + " at /mcp");
        endpoint.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-xs)").set("display", "block");

        VerticalLayout card = new VerticalLayout(top, chips, tools, needsBlock(request, report), endpointLabel,
                endpoint);
        card.setPadding(true);
        card.setSpacing(false);
        card.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("background", "var(--lumo-base-color)");

        Span caption = new Span("Server preview");
        caption.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("text-transform", "uppercase").set("letter-spacing", "0.05em")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin-bottom", "var(--lumo-space-xs)");
        return new Div(caption, card);
    }

    private static String transportBadge(ShipRequest request) {
        return switch (request.transport()) {
            case HTTP -> "HTTP :" + request.effectiveHttpPort();
            case STDIO -> "stdio";
            case BOTH -> "HTTP+stdio :" + request.effectiveHttpPort();
        };
    }

    private Component issueLine(String text, boolean blocker) {
        Span line = new Span(text);
        line.getStyle().set("font-size", "var(--lumo-font-size-s)").set("display", "block")
                .set("margin-top", "var(--lumo-space-xs)")
                .set("color", blocker ? "var(--lumo-error-text-color)"
                        : "var(--lumo-warning-text-color)");
        return line;
    }

    private List<String> selectedToolIds() {
        List<String> toolIds = new ArrayList<>(this.customToolList.getSelectedToolIds());
        toolIds.addAll(this.builtInToolList.getSelectedToolIds());
        return toolIds;
    }

    private ShipRequest currentRequest() {
        boolean dependent = MODEL_DEPENDENT.equals(this.modelModeGroup.getValue());
        boolean chatRequired = dependent && Boolean.TRUE.equals(this.chatModelCheck.getValue());
        boolean embeddingRequired = dependent && Boolean.TRUE.equals(this.embeddingModelCheck.getValue());
        BundleManifest.Models models = this.shipyardService.models(
                chatRequired ? this.chatModelCombo.getValue() : null, embeddingRequired);
        return new ShipRequest(this.nameField.getValue(), this.descriptionField.getValue(),
                this.versionField.getValue(), selectedToolIds().stream().sorted().toList(),
                this.transportGroup.getValue(), this.portField.getValue(), models, selectedProxiedTools(),
                this.proxiedMaxRisk, selectedKnowledge());
    }

    private List<String> modelBlockers() {
        List<String> blockers = new ArrayList<>();
        if (!MODEL_DEPENDENT.equals(this.modelModeGroup.getValue())) {
            return blockers;
        }
        boolean chatChecked = Boolean.TRUE.equals(this.chatModelCheck.getValue());
        if (!chatChecked && !Boolean.TRUE.equals(this.embeddingModelCheck.getValue())) {
            blockers.add("Model-dependent server: check at least one of chat or embedding model, "
                    + "or switch back to model-free");
        }
        if (chatChecked && this.chatModelCombo.getValue() == null) {
            blockers.add("Chat model is required but none is selected");
        }
        return blockers;
    }

    private void ship() {
        try {
            ExportResult result =
                    this.bundleExportService.exportBundle(currentRequest());
            this.onShipped.accept(result.manifest().server().name());
        } catch (RuntimeException e) {
            VaadinUtils.showErrorNotification("Ship failed: " + e.getMessage());
        }
    }

    static Component successPanel(String serverName, Path bundleDir, int hostPort, Runnable onRun,
            Runnable onOpenServer) {
        Div avatar = ShipyardVisuals.serverAvatar(serverName, ShipyardVisuals.AvatarSize.HERO);
        H2 heading = new H2(serverName + " is ready");
        heading.getStyle().set("margin", "var(--lumo-space-s) 0 var(--lumo-space-xs)");
        Span lead = new Span("The bundle is in " + bundleDir + ". Run it on this machine or a remote Docker "
                + "target to get a live endpoint and connect commands.");
        lead.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)").set("display", "block")
                .set("max-width", "62ch");
        Span endpoint = new Span("Will listen on port " + hostPort + " at /mcp");
        endpoint.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)").set("display", "block")
                .set("margin-top", "var(--lumo-space-s)");

        Button run = new Button("Run", VaadinIcon.PLAY.create(), e -> onRun.run());
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button open = new Button("Open server page", e -> onOpenServer.run());
        HorizontalLayout ctas = new HorizontalLayout(run, open);
        ctas.getStyle().set("margin-top", "var(--lumo-space-m)");

        VerticalLayout layout = new VerticalLayout(avatar, heading, lead, endpoint, ctas);
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setMaxWidth("620px");
        Div wrapper = new Div(layout);
        wrapper.getStyle().set("padding", "var(--lumo-space-xl) var(--lumo-space-l)");
        return wrapper;
    }

}
