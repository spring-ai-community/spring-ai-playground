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
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskAdvisor;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskComposer;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.webui.mcp.inspector.InspectorHelpers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class McpExposedToolsPanel {

    private static final int FILTER_THRESHOLD = 8;

    private final McpExposedToolService exposedToolService;
    private final McpCompositionService compositionService;
    private final McpServerInfoService serverInfoService;
    private final McpClientService clientService;
    private final McpToolRiskAdvisor riskAdvisor;

    private final Map<String, McpComposition.Member> selected = new LinkedHashMap<>();
    private Select<RiskLevel> capField;
    private VerticalLayout exposedArea;

    public McpExposedToolsPanel(McpExposedToolService exposedToolService, McpCompositionService compositionService,
            McpServerInfoService serverInfoService, McpClientService clientService, McpToolRiskAdvisor riskAdvisor) {
        this.exposedToolService = exposedToolService;
        this.compositionService = compositionService;
        this.serverInfoService = serverInfoService;
        this.clientService = clientService;
        this.riskAdvisor = riskAdvisor;
    }

    public Component build() {
        this.selected.clear();
        Optional<McpComposition> existing = this.compositionService.getExposed();
        existing.ifPresent(composition -> composition.members().forEach(member ->
                this.selected.put(key(member.serverId(), member.toolName()), member)));
        RiskLevel cap = existing.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L3);

        Span intro = new Span("Pick a server, select its tools, and they are merged into the built-in server "
                + "(spring-ai-playground-tool-mcp) — exposed on /mcp and in chat. Selected tools expand for "
                + "renaming and description editing; the input schema is passed through unchanged. Requiring "
                + "approval (HITL) lowers a tool's effective risk by one band.");
        intro.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        this.capField = new Select<>();
        this.capField.setLabel("Max risk to expose (tools above this can't be selected)");
        this.capField.setItems(RiskLevel.L1, RiskLevel.L2, RiskLevel.L3, RiskLevel.L4, RiskLevel.L5);
        this.capField.setValue(cap);
        this.capField.setWidthFull();

        Checkbox hitlAll = new Checkbox("Require approval (HITL) for all selected tools");
        hitlAll.addValueChangeListener(event -> applyHitlToAllSelected(Boolean.TRUE.equals(event.getValue())));

        VerticalLayout root = new VerticalLayout(intro, this.capField, hitlAll);
        root.setPadding(false);
        root.setSpacing(true);

        List<McpServerInfo> active = activeServers();
        if (active.isEmpty()) {
            Span empty = new Span("No active external MCP servers — connect and activate a server first.");
            empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
            root.add(empty);
            return root;
        }

        root.add(sectionLabel("Active MCP servers"));
        for (McpServerInfo server : active) {
            root.add(buildServerAccordion(server));
        }

        this.exposedArea = new VerticalLayout();
        this.exposedArea.setPadding(false);
        this.exposedArea.setSpacing(false);
        root.add(sectionLabel("Exposed on built-in server"), this.exposedArea);
        refreshExposedArea();
        return root;
    }

    public void apply() {
        this.exposedToolService.apply(new ArrayList<>(this.selected.values()), this.capField.getValue());
        Notification.show(this.selected.size() + " tool(s) exposed on the built-in MCP server", 3000,
                Notification.Position.BOTTOM_END);
    }

    // Lazy build: tools/list is a network round-trip and each tool runs a risk scan, so defer to first expand.
    private Details buildServerAccordion(McpServerInfo server) {
        McpToolRiskAdvisor.ServerRiskView serverRisk = this.riskAdvisor.evaluateServer(server);

        Span name = new Span(server.serverName());
        name.getStyle().set("font-weight", "600");
        String transport = server.mcpTransportType() == null ? "" : server.mcpTransportType().name();
        Span info = new Span(transport + " · " + exposedCountFor(server) + " exposed");
        info.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout summary = new HorizontalLayout(name,
                new McpRiskChip(serverRisk.level(), serverRisk.floorTrigger(), "Server"), info);
        summary.setAlignItems(FlexComponent.Alignment.CENTER);

        Details details = new Details();
        details.setSummary(summary);
        details.setOpened(false);
        details.setWidthFull();
        boolean[] built = {false};
        details.addOpenedChangeListener(event -> {
            if (!event.isOpened() || built[0]) return;
            built[0] = true;
            List<McpSchema.Tool> tools = this.clientService.getToolListAsOpt(server).orElseGet(List::of);
            info.setText(transport + " · " + tools.size() + " tools · " + exposedCountFor(server) + " exposed");
            details.add(buildServerContent(server, tools));
        });
        return details;
    }

    private Component buildServerContent(McpServerInfo server, List<McpSchema.Tool> tools) {
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(false);
        if (tools.isEmpty()) {
            content.add(new Span("This server exposes no tools."));
            return content;
        }
        Span count = new Span();
        List<Checkbox> rowChecks = new ArrayList<>();
        Checkbox selectAll = new Checkbox("Select all");
        selectAll.addValueChangeListener(event -> {
            boolean value = Boolean.TRUE.equals(event.getValue());
            rowChecks.forEach(check -> {
                if (check.isEnabled()) check.setValue(value);
            });
        });
        HorizontalLayout head = new HorizontalLayout(selectAll, count);
        head.setAlignItems(FlexComponent.Alignment.CENTER);
        content.add(head);

        List<RowEntry> rowEntries = new ArrayList<>();
        if (tools.size() > FILTER_THRESHOLD) {
            TextField filter = new TextField();
            filter.setPlaceholder("Filter tools by name");
            filter.setClearButtonVisible(true);
            filter.setValueChangeMode(ValueChangeMode.LAZY);
            filter.setWidthFull();
            filter.setPrefixComponent(VaadinIcon.SEARCH.create());
            filter.addValueChangeListener(event -> {
                String query = event.getValue() == null ? "" : event.getValue().trim().toLowerCase(Locale.ROOT);
                rowEntries.forEach(entry -> entry.row().setVisible(query.isEmpty() || entry.name().contains(query)));
            });
            content.add(filter);
        }
        for (McpSchema.Tool tool : tools) {
            if (tool.name() == null || tool.name().isBlank()) continue;
            Component row = buildToolRow(server, tool, count, rowChecks, tools.size());
            rowEntries.add(new RowEntry(tool.name().toLowerCase(Locale.ROOT), row));
            content.add(row);
        }
        updateServerCount(count, server, tools.size());
        return content;
    }

    private Component buildToolRow(McpServerInfo server, McpSchema.Tool tool, Span count, List<Checkbox> rowChecks,
            int toolTotal) {
        String rowKey = key(server.serverName(), tool.name());
        InspectorHelpers.ToolInfo toolInfo = InspectorHelpers.toToolInfo(tool);
        String originalDescription = tool.description() == null ? "" : tool.description();
        McpToolRiskAdvisor.ToolRiskView risk = this.riskAdvisor.evaluateTool(server, tool.name(),
                tool.description(), toolInfo.propertySchemas());
        RiskLevel baseLevel = risk.finalLevel();
        McpComposition.Member existing = this.selected.get(rowKey);

        Checkbox check = new Checkbox(existing != null);
        rowChecks.add(check);
        Span name = new Span(tool.name());
        name.getStyle().set("font-weight", "500").set("cursor", "pointer");
        if (!originalDescription.isBlank()) {
            Tooltip.forComponent(name).withText(originalDescription);
        }
        Icon caret = VaadinIcon.ANGLE_RIGHT.create();
        caret.setVisible(false);
        caret.getStyle().set("cursor", "pointer").set("transition", "transform 150ms ease-out")
                .set("width", "0.9em").set("height", "0.9em")
                .set("color", "var(--lumo-secondary-text-color)");
        Checkbox hitl = new Checkbox("HITL");
        hitl.setValue(existing != null && existing.hitl());
        hitl.setTooltipText("Require human approval before this tool runs (lowers effective risk by one band)");
        Div chipHolder = new Div();
        chipHolder.getStyle().set("display", "inline-flex").set("align-items", "center").set("gap", "0.3em");

        TextField alias = new TextField("Exposed name");
        alias.setValue(existing != null ? existing.exposedAlias()
                : McpComposition.Member.defaultAlias(server.serverName(), tool.name()));
        alias.setWidthFull();
        TextArea description = new TextArea("Description");
        description.setValue(existing != null && existing.descriptionOverride() != null
                ? existing.descriptionOverride() : originalDescription);
        description.setWidthFull();
        description.setMaxHeight("90px");
        VerticalLayout editor = new VerticalLayout(alias, description);
        editor.setPadding(false);
        editor.setSpacing(false);
        editor.getStyle().set("margin", "0 0 0.5em 2.2em");

        boolean[] expanded = {false};
        Runnable updateExpansion = () -> {
            boolean active = Boolean.TRUE.equals(check.getValue()) && check.isEnabled();
            caret.setVisible(active);
            caret.getStyle().set("transform", expanded[0] ? "rotate(90deg)" : "none");
            editor.setVisible(active && expanded[0]);
        };
        Runnable toggleExpand = () -> {
            if (!Boolean.TRUE.equals(check.getValue())) return;
            expanded[0] = !expanded[0];
            updateExpansion.run();
        };
        name.getElement().addEventListener("click", event -> toggleExpand.run());
        caret.getElement().addEventListener("click", event -> toggleExpand.run());

        Runnable rebuildMember = () -> {
            if (Boolean.TRUE.equals(check.getValue())) {
                this.selected.put(rowKey, memberOf(server, tool, alias.getValue(), description.getValue(),
                        originalDescription, hitl.getValue()));
            } else {
                this.selected.remove(rowKey);
            }
        };
        Runnable refreshRow = () -> {
            RiskLevel effective = McpToolRiskComposer.applyHitlMitigation(baseLevel, hitl.getValue());
            boolean overCap = effective.ordinal() > this.capField.getValue().ordinal();
            chipHolder.removeAll();
            chipHolder.add(new McpRiskChip(effective, risk.floorTrigger()));
            if (Boolean.TRUE.equals(hitl.getValue())) {
                Span badge = new Span("HITL −1");
                badge.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                        .set("color", "var(--lumo-success-text-color)");
                chipHolder.add(badge);
            }
            if (overCap && Boolean.TRUE.equals(check.getValue())) check.setValue(false);
            check.setEnabled(!overCap);
            alias.setEnabled(!overCap);
            description.setEnabled(!overCap);
            updateExpansion.run();
        };

        check.addValueChangeListener(event -> {
            if (Boolean.TRUE.equals(event.getValue())) expanded[0] = true;
            rebuildMember.run();
            updateExpansion.run();
            updateServerCount(count, server, toolTotal);
            refreshExposedArea();
        });
        hitl.addValueChangeListener(event -> {
            refreshRow.run();
            rebuildMember.run();
            refreshExposedArea();
        });
        alias.addValueChangeListener(event -> {
            rebuildMember.run();
            refreshExposedArea();
        });
        description.addValueChangeListener(event -> {
            rebuildMember.run();
            refreshExposedArea();
        });

        refreshRow.run();
        HorizontalLayout top = new HorizontalLayout(check, caret, name, chipHolder, hitl);
        top.setAlignItems(FlexComponent.Alignment.CENTER);
        VerticalLayout row = new VerticalLayout(top, editor);
        row.setPadding(false);
        row.setSpacing(false);
        return row;
    }

    private McpComposition.Member memberOf(McpServerInfo server, McpSchema.Tool tool, String aliasValue,
            String descriptionValue, String originalDescription, boolean hitl) {
        String alias = aliasValue == null || aliasValue.isBlank()
                ? McpComposition.Member.defaultAlias(server.serverName(), tool.name()) : aliasValue.trim();
        String override = descriptionValue == null || descriptionValue.equals(originalDescription)
                ? null : descriptionValue;
        return new McpComposition.Member(server.serverName(), tool.name(), alias, override, hitl, "");
    }

    // Open accordion rows aren't re-synced live; the selected set is authoritative on Apply.
    private void applyHitlToAllSelected(boolean hitl) {
        this.selected.replaceAll((rowKey, member) -> new McpComposition.Member(member.serverId(), member.toolName(),
                member.exposedAlias(), member.descriptionOverride(), hitl, member.contentHash()));
        refreshExposedArea();
    }

    private void updateServerCount(Span count, McpServerInfo server, int toolTotal) {
        long selectedHere = this.selected.keySet().stream()
                .filter(k -> k.startsWith(server.serverName() + "::")).count();
        count.setText(selectedHere + " / " + toolTotal + " selected");
        count.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
    }

    private void refreshExposedArea() {
        if (this.exposedArea == null) return;
        this.exposedArea.removeAll();
        if (this.selected.isEmpty()) {
            Span none = new Span("Nothing exposed yet.");
            none.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
            this.exposedArea.add(none);
            return;
        }
        for (McpComposition.Member member : this.selected.values()) {
            String label = member.exposedAlias() + "  ·  " + member.serverId() + "/" + member.toolName()
                    + (member.hitl() ? "  · HITL" : "");
            Span chip = new Span(label);
            chip.getStyle().set("font-size", "var(--lumo-font-size-s)")
                    .set("background-color", "var(--lumo-contrast-5pct)")
                    .set("border-radius", "var(--lumo-border-radius-s)")
                    .set("padding", "2px 8px").set("margin", "2px 0").set("display", "inline-block");
            this.exposedArea.add(chip);
        }
    }

    private long exposedCountFor(McpServerInfo server) {
        return this.selected.keySet().stream().filter(k -> k.startsWith(server.serverName() + "::")).count();
    }

    private List<McpServerInfo> activeServers() {
        McpServerInfo builtin = this.serverInfoService.getDefaultMcpServerInfo();
        return this.serverInfoService.read().stream()
                .filter(this.clientService::isConnecting)
                .filter(info -> builtin == null || !info.serverName().equals(builtin.serverName()))
                .toList();
    }

    private Span sectionLabel(String text) {
        Span label = new Span(text);
        label.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("margin-top", "0.5em");
        return label;
    }

    private static String key(String serverId, String toolName) {
        return serverId + "::" + toolName;
    }

    private record RowEntry(String name, Component row) {}
}
