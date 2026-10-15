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
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ToolChoices;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog.CategoryDef;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.webui.common.Pills;
import org.springaicommunity.playground.webui.common.sidebar.CategoryGroupDetails;
import org.springaicommunity.playground.webui.common.sidebar.SidebarFilterBar;
import org.springaicommunity.playground.webui.common.sidebar.SidebarItemLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class BundleToolSelectionList extends Div {

    public record Config(String narrative, String emptyMessage, boolean filters) {}

    private final Supplier<ToolChoices> choices;
    private final ToolCategoryCatalog categoryCatalog;
    private final Config config;
    private ToolChoices current = new ToolChoices(List.of(), 0, 0, Map.of());

    private final SidebarFilterBar filterBar;
    private final VerticalLayout groupContainer = new VerticalLayout();
    private final Span excludedSummary = new Span();
    private final Set<String> selectedToolIds = new LinkedHashSet<>();

    private Runnable onSelectionChange = () -> { };

    public BundleToolSelectionList(Supplier<ToolChoices> choices, ToolCategoryCatalog categoryCatalog,
            Config config) {
        this.choices = choices;
        this.categoryCatalog = categoryCatalog;
        this.config = config;

        Span narrative = new Span(config.narrative());
        narrative.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block");

        this.filterBar = new SidebarFilterBar(new SidebarFilterBar.Config(
                "Search tools…", "Category", "Tag", 200));
        this.filterBar.setOnChange(this::renderGroups);

        this.groupContainer.setPadding(false);
        this.groupContainer.setSpacing(false);
        this.groupContainer.setWidthFull();
        this.groupContainer.getStyle().set("gap", "0");

        this.excludedSummary.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("font-style", "italic")
                .set("display", "block").set("padding-top", "var(--lumo-space-xs)");

        add(narrative);
        if (config.filters()) {
            add(this.filterBar);
        }
        add(this.groupContainer, this.excludedSummary);
        setWidthFull();
    }

    public void setOnSelectionChange(Runnable onSelectionChange) {
        this.onSelectionChange = onSelectionChange;
    }

    public List<String> getSelectedToolIds() {
        return List.copyOf(this.selectedToolIds);
    }

    public List<ToolSpec> getSelectedTools() {
        return activeTools().stream().filter(tool -> this.selectedToolIds.contains(tool.toolId()))
                .sorted(Comparator.comparing(ToolSpec::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void reset(List<String> preselectedToolIds) {
        this.selectedToolIds.clear();
        List<ToolSpec> active = activeTools();
        active.stream().map(ToolSpec::toolId).filter(preselectedToolIds::contains)
                .forEach(this.selectedToolIds::add);
        refreshFilterOptions(active);
        renderGroups();
    }

    private List<ToolSpec> activeTools() {
        return this.choices.get().shippable();
    }

    private void renderGroups() {
        this.groupContainer.removeAll();

        this.current = this.choices.get();
        ToolChoices current = this.current;
        List<ToolSpec> active = current.shippable();
        List<ToolSpec> filtered = applyAllFilters(active);

        if (filtered.isEmpty()) {
            Span empty = new Span(active.isEmpty()
                    ? this.config.emptyMessage()
                    : "No tools match the current filters.");
            empty.getStyle().set("padding", "var(--lumo-space-m)")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            this.groupContainer.add(empty);
        } else {
            for (Component child : buildCategoryChildren(filtered)) {
                this.groupContainer.add(child);
            }
        }
        updateExcludedSummary(current);
    }

    private void updateExcludedSummary(ToolChoices current) {
        long drafts = current.drafts();
        long missingEnv = current.missingRequirements();
        if (drafts == 0 && missingEnv == 0) {
            this.excludedSummary.setText("");
            this.excludedSummary.setVisible(false);
            return;
        }
        StringBuilder text = new StringBuilder("Excluded from bundling: ");
        if (drafts > 0) {
            text.append(drafts).append(" draft(s) - Test & Publish them in Tool Studio first");
        }
        if (missingEnv > 0) {
            if (drafts > 0) text.append("; ");
            text.append(missingEnv).append(" tool(s) missing required environment variables locally");
        }
        text.append(". ").append(current.shippable().size()).append(" Local Pass tool(s) available.");
        this.excludedSummary.setText(text.toString());
        this.excludedSummary.setVisible(true);
    }

    private List<Component> buildCategoryChildren(List<ToolSpec> tools) {
        Map<String, List<ToolSpec>> byCategory = new LinkedHashMap<>();
        for (CategoryDef categoryDef : this.categoryCatalog.categories()) {
            byCategory.put(categoryDef.id(), new ArrayList<>());
        }
        for (ToolSpec tool : tools) {
            byCategory.get(this.categoryCatalog.resolveOrFallback(tool.category()).id()).add(tool);
        }
        List<Component> children = new ArrayList<>();
        for (Map.Entry<String, List<ToolSpec>> entry : byCategory.entrySet()) {
            List<ToolSpec> bucket = entry.getValue();
            if (bucket.isEmpty()) continue;
            bucket.sort(Comparator.comparing(ToolSpec::name, String.CASE_INSENSITIVE_ORDER));
            CategoryDef categoryDef = this.categoryCatalog.find(entry.getKey()).orElseThrow();
            children.add(buildCategoryGroup(categoryDef, bucket));
        }
        return children;
    }

    private Component buildCategoryGroup(CategoryDef categoryDef, List<ToolSpec> tools) {
        CheckboxGroup<ToolSpec> checkboxGroup = new CheckboxGroup<>();
        checkboxGroup.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        checkboxGroup.setWidthFull();
        checkboxGroup.setItems(tools);
        checkboxGroup.setRenderer(new ComponentRenderer<>(this::renderToolItem));
        checkboxGroup.setValue(tools.stream()
                .filter(tool -> this.selectedToolIds.contains(tool.toolId()))
                .collect(Collectors.toSet()));
        checkboxGroup.addValueChangeListener(event -> {
            tools.forEach(tool -> this.selectedToolIds.remove(tool.toolId()));
            event.getValue().forEach(tool -> this.selectedToolIds.add(tool.toolId()));
            this.onSelectionChange.run();
        });
        return CategoryGroupDetails.build(categoryDef.displayName(), tools.size(), checkboxGroup);
    }

    private Div renderToolItem(ToolSpec tool) {
        Span dot = Pills.dot("var(--lumo-success-color)");
        dot.getElement().setAttribute("title", "Local Pass");

        Span title = new Span(tool.name());
        title.getStyle().set("font-size", "var(--lumo-font-size-s)");
        title.getElement().setAttribute("title", Objects.toString(tool.description(), ""));

        List<Span> pills = new ArrayList<>();
        pills.add(Pills.pill(this.current.riskLevels().getOrDefault(tool.toolId(), ""), "badge pill small"));
        for (String tag : tool.tags()) {
            pills.add(Pills.pill(tag, "badge success pill small"));
        }
        return SidebarItemLayout.twoRow(dot, title, pills, false);
    }

    private List<ToolSpec> applyAllFilters(List<ToolSpec> tools) {
        List<ToolSpec> out = new ArrayList<>(tools.size());
        for (ToolSpec tool : tools) {
            if (!matchesSearch(tool)) continue;
            if (!matchesCategoryFilter(tool)) continue;
            if (!matchesTagFilter(tool)) continue;
            out.add(tool);
        }
        return out;
    }

    private boolean matchesSearch(ToolSpec tool) {
        String query = this.filterBar.getSearchQuery();
        if (query.isEmpty()) return true;
        String name = tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
        String description = tool.description() == null ? "" : tool.description().toLowerCase(Locale.ROOT);
        return name.contains(query) || description.contains(query);
    }

    private boolean matchesCategoryFilter(ToolSpec tool) {
        Set<String> selection = this.filterBar.getCategorySelection();
        if (selection.isEmpty()) return true;
        return selection.contains(this.categoryCatalog.resolveOrFallback(tool.category()).id());
    }

    private boolean matchesTagFilter(ToolSpec tool) {
        Set<String> selection = this.filterBar.getTagSelection();
        if (selection.isEmpty()) return true;
        for (String wanted : selection) {
            if (tool.tags().contains(wanted)) return true;
        }
        return false;
    }

    private void refreshFilterOptions(List<ToolSpec> tools) {
        Set<String> presentCategoryIds = new LinkedHashSet<>();
        Set<String> observedTags = new TreeSet<>();
        for (ToolSpec tool : tools) {
            presentCategoryIds.add(this.categoryCatalog.resolveOrFallback(tool.category()).id());
            observedTags.addAll(tool.tags());
        }
        List<String> categoryItems = new ArrayList<>();
        for (CategoryDef categoryDef : this.categoryCatalog.categories()) {
            if (presentCategoryIds.contains(categoryDef.id())) categoryItems.add(categoryDef.id());
        }
        this.filterBar.setCategoryItems(categoryItems,
                id -> this.categoryCatalog.resolveOrFallback(id).displayName());
        this.filterBar.setTagItems(new ArrayList<>(observedTags));
    }

}
