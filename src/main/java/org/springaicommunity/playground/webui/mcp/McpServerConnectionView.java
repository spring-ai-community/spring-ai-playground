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

import com.fasterxml.jackson.core.type.TypeReference;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.catalog.CategoryDef;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogEntry;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.catalog.McpCategoryService;
import org.springaicommunity.playground.service.mcp.catalog.McpTagSuggestionService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpClientService.StatusEntry;
import org.springaicommunity.playground.webui.PersistentUiDataStorage;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.Pills;
import org.springaicommunity.playground.webui.common.WorkspaceSidebar;
import org.springaicommunity.playground.webui.common.sidebar.CategoryGroupDetails;
import org.springaicommunity.playground.webui.common.sidebar.SidebarFilterBar;
import org.springaicommunity.playground.webui.common.sidebar.SidebarItemLayout;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.springaicommunity.playground.webui.mcp.McpServerView.MCP_CONNECTION_SELECT_EVENT;

public class McpServerConnectionView extends WorkspaceSidebar implements BeforeEnterObserver {

    private final PersistentUiDataStorage persistentUiDataStorage;
    private final PropertyChangeSupport mcpServerInfoChangeSupport;
    private final McpServerInfoService mcpServerInfoService;
    private final McpClientService mcpClientService;
    private final McpCategoryService mcpCategoryService;
    private final McpCatalogService mcpCatalogService;
    private final McpTagSuggestionService mcpTagSuggestionService;

    private final Map<String, ListBox<McpServerInfo>> mcpServerInfoListBoxMap = new LinkedHashMap<>();
    private final Set<String> ghostNames = new HashSet<>();
    private McpServerInfo selectedMcpServerInfo;

    private final SidebarFilterBar filterBar;
    private final VerticalLayout groupContainer = new VerticalLayout();

    @Override
    public void beforeEnter(BeforeEnterEvent beforeEnterEvent) {
        this.persistentUiDataStorage.loadData(McpContentView.LAST_SELECTED_MCP_CONNECTION,
                new TypeReference<McpServerInfo>() {},
                savedInfo -> resolveInitialSelection(savedInfo).ifPresent(this::selectMcpConnectionContent));
    }

    private Optional<McpServerInfo> resolveInitialSelection(McpServerInfo savedInfo) {
        Optional<McpServerInfo> fromSaved = Optional.ofNullable(savedInfo)
                .map(info -> this.mcpServerInfoService.getMcpServerInfos().get(info.mcpTransportType()))
                .flatMap(list -> list.stream()
                        .filter(info -> info.serverName().equals(savedInfo.serverName()))
                        .findFirst());
        return fromSaved
                .or(() -> Optional.ofNullable(this.mcpServerInfoService.getDefaultMcpServerInfo()))
                .or(() -> this.mcpServerInfoService.getMcpServerInfos().values().stream()
                        .flatMap(List::stream).findFirst());
    }

    public McpServerConnectionView(PersistentUiDataStorage persistentUiDataStorage,
            McpServerInfoService mcpServerInfoService, McpClientService mcpClientService,
            McpCategoryService mcpCategoryService, McpCatalogService mcpCatalogService,
            McpTagSuggestionService mcpTagSuggestionService,
            PropertyChangeSupport mcpServerInfoChangeSupport) {
        super("MCP Server Connections");
        this.persistentUiDataStorage = persistentUiDataStorage;
        this.mcpServerInfoService = mcpServerInfoService;
        this.mcpClientService = mcpClientService;
        this.mcpCategoryService = mcpCategoryService;
        this.mcpCatalogService = mcpCatalogService;
        this.mcpTagSuggestionService = mcpTagSuggestionService;
        this.mcpServerInfoChangeSupport = mcpServerInfoChangeSupport;

        this.filterBar = new SidebarFilterBar(new SidebarFilterBar.Config(
                "Search by name, vendor…", "Categories", "Tags", 200));
        this.filterBar.setOnChange(this::renderCategoryGroups);

        setSidebarContent(buildSidebarBody());
        updateMcpConnections();

        addAttachListener(e -> {
            McpServerInfo target = this.selectedMcpServerInfo != null
                    ? this.selectedMcpServerInfo
                    : this.mcpServerInfoService.getDefaultMcpServerInfo();
            if (target == null) return;
            e.getUI().beforeClientResponse(this, ctx -> {
                ListBox<McpServerInfo> box = this.mcpServerInfoListBoxMap.get(listBoxKeyFor(target));
                if (box == null) return;
                box.clear();
                box.setValue(target);
            });
        });
    }

    private VerticalLayout buildSidebarBody() {
        this.groupContainer.setPadding(false);
        this.groupContainer.setSpacing(false);
        this.groupContainer.setWidthFull();
        this.groupContainer.getStyle()
                .set("flex", "1 1 auto")
                .set("min-height", "0")
                .set("gap", "0");

        VerticalLayout body = new VerticalLayout(this.filterBar, verticalScroller(this.groupContainer));
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.getStyle().set("min-height", "0").set("gap", "0").set("overflow-x", "hidden");
        return body;
    }

    public void updateMcpConnections() {
        List<String> categoryOptions = this.mcpCategoryService.getAllCategories(collectAllCategoryIds()).stream()
                .map(CategoryDef::id).toList();
        Set<String> previousCats = this.filterBar.getCategorySelection();
        this.filterBar.setCategoryItems(categoryOptions,
                id -> this.mcpCategoryService.resolveDef(id).displayName());
        Set<String> retainedCats = new LinkedHashSet<>(previousCats);
        retainedCats.retainAll(categoryOptions);
        this.filterBar.setCategorySelection(retainedCats);

        List<String> tagOptions = this.mcpTagSuggestionService.getAllTags(collectAllTags());
        Set<String> previousTags = this.filterBar.getTagSelection();
        this.filterBar.setTagItems(tagOptions);
        Set<String> retainedTags = new LinkedHashSet<>(previousTags);
        retainedTags.retainAll(tagOptions);
        this.filterBar.setTagSelection(retainedTags);

        renderCategoryGroups();
    }

    private Set<String> collectAllCategoryIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (McpCatalogEntry e : this.mcpCatalogService.getCatalog()) ids.add(e.category());
        this.mcpServerInfoService.getMcpServerInfos().values().stream().flatMap(List::stream)
                .forEach(i -> ids.add(i.category()));
        return ids;
    }

    private Set<String> collectAllTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (McpCatalogEntry e : this.mcpCatalogService.getCatalog()) tags.addAll(e.tags());
        this.mcpServerInfoService.getMcpServerInfos().values().stream().flatMap(List::stream)
                .forEach(i -> tags.addAll(i.tags()));
        return tags;
    }

    private void renderCategoryGroups() {
        this.mcpServerInfoListBoxMap.clear();
        this.ghostNames.clear();
        this.groupContainer.removeAll();

        McpServerInfo builtinInfo = this.mcpServerInfoService.getDefaultMcpServerInfo();
        String builtinName = builtinInfo == null ? null : builtinInfo.serverName();

        Map<String, List<McpServerInfo>> userByCategory = new HashMap<>();
        Set<String> userIds = new HashSet<>();
        this.mcpServerInfoService.getMcpServerInfos().values().stream().flatMap(List::stream).forEach(info -> {
            userIds.add(info.serverName());
            if (builtinName != null && builtinName.equals(info.serverName())) return;
            userByCategory.computeIfAbsent(info.category(), k -> new ArrayList<>()).add(info);
        });

        int totalCount = userIds.size();
        boolean builtinMatchesFilters = builtinInfo != null
                && matchesSearch(builtinInfo) && matchesTagFilter(builtinInfo)
                && (this.filterBar.getCategorySelection().isEmpty()
                        || this.filterBar.getCategorySelection().contains(builtinInfo.category()));
        if (builtinInfo != null && builtinMatchesFilters) {
            ListBox<McpServerInfo> builtinBox = buildMcpServerInfoListBox();
            builtinBox.setItems(List.of(builtinInfo));
            this.mcpServerInfoListBoxMap.put("__builtin__", builtinBox);
            this.groupContainer.add(buildBuiltinGroup(builtinBox));
        }

        Map<String, List<McpServerInfo>> ghostByCategory = new HashMap<>();
        for (McpCatalogEntry entry : this.mcpCatalogService.getCatalog()) {
            if (userIds.contains(entry.id())) continue;
            try {
                McpServerInfo ghost = this.mcpCatalogService.instantiate(entry, null, Map.of());
                ghostByCategory.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(ghost);
                this.ghostNames.add(ghost.serverName());
                totalCount++;
            } catch (RuntimeException ignore) {
            }
        }

        Set<String> observedCats = new LinkedHashSet<>();
        observedCats.addAll(userByCategory.keySet());
        observedCats.addAll(ghostByCategory.keySet());
        List<CategoryDef> orderedDefs = this.mcpCategoryService.getAllCategories(observedCats);

        boolean filtersActive = this.filterBar.isActive();
        Set<String> categorySelection = this.filterBar.getCategorySelection();
        boolean anyVisible = builtinInfo != null && builtinMatchesFilters;
        int filteredCount = anyVisible ? 1 : 0;

        for (CategoryDef def : orderedDefs) {
            if (!categorySelection.isEmpty() && !categorySelection.contains(def.id())) continue;

            List<McpServerInfo> userItems = applyAllFilters(userByCategory.getOrDefault(def.id(), List.of()));
            List<McpServerInfo> ghostItems = applyAllFilters(ghostByCategory.getOrDefault(def.id(), List.of()));
            if (userItems.isEmpty() && ghostItems.isEmpty()) continue;

            List<McpServerInfo> tier1Ghosts = new ArrayList<>();
            List<McpServerInfo> tier2Ghosts = new ArrayList<>();
            for (McpServerInfo g : ghostItems) {
                int tier = this.mcpCatalogService.findById(g.serverName()).map(McpCatalogEntry::tier).orElse(1);
                (tier == 2 ? tier2Ghosts : tier1Ghosts).add(g);
            }

            List<McpServerInfo> primaryItems = new ArrayList<>(userItems);
            primaryItems.addAll(tier1Ghosts);

            ListBox<McpServerInfo> primaryListBox = buildMcpServerInfoListBox();
            primaryListBox.setItems(primaryItems);
            this.mcpServerInfoListBoxMap.put(def.id(), primaryListBox);

            VerticalLayout body = new VerticalLayout();
            body.setPadding(false);
            body.setSpacing(false);
            body.add(primaryListBox);

            if (!tier2Ghosts.isEmpty()) {
                ListBox<McpServerInfo> tier2ListBox = buildMcpServerInfoListBox();
                tier2ListBox.setItems(tier2Ghosts);
                this.mcpServerInfoListBoxMap.put(def.id() + "::tier2", tier2ListBox);
                Details moreDetails = filtersActive
                        ? CategoryGroupDetails.build("More from " + def.displayName(),
                                tier2Ghosts.size(), tier2ListBox)
                        : CategoryGroupDetails.buildCollapsed(
                                "More from " + def.displayName() + " (" + tier2Ghosts.size() + ")", tier2ListBox);
                body.add(moreDetails);
            }

            int groupCount = primaryItems.size() + tier2Ghosts.size();
            filteredCount += groupCount;
            Details categoryDetails = def.builtIn()
                    ? CategoryGroupDetails.build(def.displayName(), groupCount, body)
                    : CategoryGroupDetails.buildUserDefined(def.displayName(), groupCount, body);
            this.groupContainer.add(categoryDetails);
            anyVisible = true;
        }

        if (!anyVisible && filtersActive) {
            this.groupContainer.add(buildEmptyState());
        }

        updateHeaderCount(filteredCount, totalCount);
    }

    private void updateHeaderCount(int filteredCount, int totalCount) {
        String suffix = filteredCount == totalCount ? " (" + totalCount + ")"
                : " (" + filteredCount + " of " + totalCount + ")";
        setSidebarTitle("MCP Server Connections" + suffix);
    }

    private Div buildBuiltinGroup(ListBox<McpServerInfo> builtinBox) {
        Span header = new Span("Built-in");
        header.getStyle()
                .set("display", "block")
                .set("font-weight", "var(--lumo-font-weight-semibold)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-primary-text-color)")
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-s)");
        Div group = new Div(header, builtinBox);
        group.setWidthFull();
        group.getStyle()
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-bottom", "var(--lumo-space-xs)")
                .set("margin-bottom", "var(--lumo-space-xs)");
        return group;
    }

    private Div buildEmptyState() {
        Span title = new Span("No servers match your filters");
        title.getStyle().set("font-weight", "500");
        Paragraph hint = new Paragraph(
                "Try a broader keyword, fewer categories or tags. Tool-name search needs the server to be activated first.");
        hint.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin", "var(--lumo-space-xs) 0 var(--lumo-space-s)");
        Button clear = new Button("Clear filters", VaadinIcon.CLOSE_SMALL.create(), e -> this.filterBar.clear());
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        Div empty = new Div(title, hint, clear);
        empty.getStyle()
                .set("padding", "var(--lumo-space-m)")
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "flex-start");
        return empty;
    }

    private List<McpServerInfo> applyAllFilters(List<McpServerInfo> items) {
        if (items.isEmpty()) return items;
        List<McpServerInfo> out = new ArrayList<>(items.size());
        for (McpServerInfo i : items) {
            if (!matchesSearch(i)) continue;
            if (!matchesTagFilter(i)) continue;
            out.add(i);
        }
        return out;
    }

    private boolean matchesSearch(McpServerInfo info) {
        String query = this.filterBar.getSearchQuery();
        if (query.isEmpty()) return true;
        if (lower(info.serverName()).contains(query)) return true;
        if (lower(info.description()).contains(query)) return true;
        String vendor = this.mcpCatalogService.findById(info.serverName()).map(McpCatalogEntry::vendor).orElse("");
        if (lower(vendor).contains(query)) return true;
        return this.mcpClientService.getToolListAsOpt(info)
                .map(tools -> tools.stream().anyMatch(t ->
                        lower(t.name()).contains(query)
                                || lower(t.description()).contains(query)))
                .orElse(false);
    }

    private boolean matchesTagFilter(McpServerInfo info) {
        Set<String> selection = this.filterBar.getTagSelection();
        if (selection.isEmpty()) return true;
        for (String wanted : selection) {
            if (info.tags().contains(wanted)) return true;
        }
        return false;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private ListBox<McpServerInfo> buildMcpServerInfoListBox() {
        ListBox<McpServerInfo> box = new ListBox<>();
        box.addClassName("custom-list-box");
        box.setWidthFull();
        box.setRenderer(new ComponentRenderer<>(this::renderItem));
        box.addValueChangeListener(event -> notifyMcpServerInfoSelection(event.getOldValue(), event.getValue()));
        box.getStyle().set("--lumo-size-m", "var(--lumo-size-s)");
        return box;
    }

    private Div renderItem(McpServerInfo info) {
        boolean ghost = this.ghostNames.contains(info.serverName());
        StatusEntry status = ghost ? recomputeGhostStatus(info) : mcpClientService.getStatus(info);
        Optional<McpCatalogEntry> entryOpt = this.mcpCatalogService.findById(info.serverName());

        Span dot = Pills.dot(statusColor(status));
        dot.getElement().setAttribute("title", status.status().name());

        Span title = listItemText(info.serverName());
        title.getElement().setAttribute("title", Objects.toString(info.description(), ""));
        if (ghost) title.getStyle().set("font-style", "italic");

        List<Span> pills = new ArrayList<>();
        CategoryDef catDef = this.mcpCategoryService.resolveDef(info.category());
        pills.add(Pills.pill(catDef.displayName(), "badge contrast pill small"));
        for (String tag : info.tags()) {
            if (tag == null || tag.isBlank() || "global".equalsIgnoreCase(tag)) continue;
            pills.add(Pills.pill(tag.toUpperCase(Locale.ROOT), "badge success pill small"));
        }
        entryOpt.map(McpCatalogEntry::stability)
                .filter(s -> s != null && !"GA".equalsIgnoreCase(s))
                .ifPresent(stab -> pills.add(Pills.pill(stab.toLowerCase(Locale.ROOT), "badge warning pill small")));

        Div row = SidebarItemLayout.twoRow(dot, title, pills, ghost);
        boolean isSelected = info.equals(this.selectedMcpServerInfo);
        if (!isSelected) {
            Tooltip.forComponent(row)
                    .withText(buildTooltip(info, status, entryOpt, ghost))
                    .withPosition(Tooltip.TooltipPosition.END)
                    .withHoverDelay(1);
        }
        if (!ghost) schedulePing(info);
        return row;
    }

    private static String statusColor(StatusEntry status) {
        return switch (status.status()) {
            case OK -> "var(--lumo-success-color)";
            case ERROR -> "var(--lumo-error-color)";
            case AWAITING_AUTHORIZATION -> "var(--lumo-warning-color)";
            case OFFLINE -> "var(--lumo-contrast-30pct)";
            case MISSING_CONFIG -> "var(--lumo-contrast-50pct)";
        };
    }

    private String buildTooltip(McpServerInfo info, StatusEntry status, Optional<McpCatalogEntry> entryOpt,
            boolean ghost) {
        StringBuilder sb = new StringBuilder();
        if (info.description() != null && !info.description().isBlank()) sb.append(info.description());
        String vendor = entryOpt.map(McpCatalogEntry::vendor).filter(v -> v != null && !v.isBlank()).orElse(null);
        if (vendor != null) appendLine(sb, "Vendor: " + vendor);
        appendLine(sb, "Transport: " + info.mcpTransportType().name());
        switch (status.status()) {
            case ERROR -> appendLine(sb, "Error: " + (status.error() == null ? "" : status.error()));
            case AWAITING_AUTHORIZATION ->
                    appendLine(sb, "Awaiting OAuth authorization — open the config panel and click Authorize.");
            case MISSING_CONFIG ->
                    appendLine(sb, status.error() == null ? "Missing required configuration" : status.error());
            default -> {
                if (ghost) appendLine(sb, "Catalog entry — click to activate.");
            }
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String text) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(text);
    }

    private StatusEntry recomputeGhostStatus(McpServerInfo ghost) {
        Set<String> missing = mcpClientService.findMissingEnv(ghost);
        if (!missing.isEmpty()) return StatusEntry.missingConfig(missing);
        return StatusEntry.OFFLINE;
    }

    private String listBoxKeyFor(McpServerInfo info) {
        McpServerInfo def = this.mcpServerInfoService.getDefaultMcpServerInfo();
        if (def != null && info != null && def.serverName().equals(info.serverName())) return "__builtin__";
        return info.category();
    }

    private void schedulePing(McpServerInfo info) {
        UI ui = VaadinUtils.getUi(this);
        if (ui == null) return;
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            StatusEntry before = mcpClientService.getStatus(info);
            StatusEntry after = mcpClientService.pingAndUpdateStatus(info);
            if (!Objects.equals(before, after)) {
                ListBox<McpServerInfo> box = mcpServerInfoListBoxMap.get(listBoxKeyFor(info));
                if (box != null) ui.access(() -> box.getDataProvider().refreshItem(info));
            }
        });
    }

    private void notifyMcpServerInfoSelection(McpServerInfo oldMcpServerInfo, McpServerInfo newMcpServerInfo) {
        if (Objects.isNull(newMcpServerInfo)) return;
        this.selectedMcpServerInfo = newMcpServerInfo;
        this.mcpServerInfoListBoxMap.values().stream()
                .filter(box -> !newMcpServerInfo.equals(box.getValue()))
                .forEach(ListBox::clear);
        this.mcpServerInfoChangeSupport.firePropertyChange(MCP_CONNECTION_SELECT_EVENT, oldMcpServerInfo,
                newMcpServerInfo);
        this.persistentUiDataStorage.saveData(McpContentView.LAST_SELECTED_MCP_CONNECTION, newMcpServerInfo);
    }

    public boolean isGhost(McpServerInfo info) {
        return info != null && this.ghostNames.contains(info.serverName());
    }

    public void selectMcpConnectionContent(McpServerInfo targetMcpServerInfo) {
        VaadinUtils.getUi(this).access(() -> {
            ListBox<McpServerInfo> box = this.mcpServerInfoListBoxMap.get(listBoxKeyFor(targetMcpServerInfo));
            if (box != null) box.setValue(targetMcpServerInfo);
        });
    }

    public void clearSelectConnection() {
        this.mcpServerInfoListBoxMap.values().forEach(ListBox::clear);
    }

}
