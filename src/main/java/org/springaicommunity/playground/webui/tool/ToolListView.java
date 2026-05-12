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
package org.springaicommunity.playground.webui.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog.CategoryDef;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator.State;
import org.springaicommunity.playground.webui.PersistentUiDataStorage;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.WorkspaceSidebar;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.springaicommunity.playground.webui.tool.ToolStudioView.TOOL_EMPTY_EVENT;
import static org.springaicommunity.playground.webui.tool.ToolStudioView.TOOL_SELECT_EVENT;

public class ToolListView extends WorkspaceSidebar implements BeforeEnterObserver {

    private static final String LAST_SELECTED_TOOL = "lastSelectedTool";

    private final PersistentUiDataStorage persistentUiDataStorage;
    private final PropertyChangeSupport toolChangeSupport;
    private final ToolSpecService toolSpecService;
    private final ToolCategoryCatalog categoryCatalog;
    private final ToolActivationCalculator activationCalculator;
    private final TextField searchField;
    private final MultiSelectComboBox<String> categoryFilter;
    private final MultiSelectComboBox<String> tagFilter;
    private final VerticalLayout groupContainer;
    private final List<ListBox<ToolSpec>> categoryListBoxes = new ArrayList<>();

    private String searchQuery = "";
    private Set<String> categoryFilterSelection = Set.of();
    private Set<String> tagFilterSelection = Set.of();
    private ToolSpec selectedSpec;

    @Override
    public void beforeEnter(BeforeEnterEvent beforeEnterEvent) {
        refreshFilterOptions(this.toolSpecService.getToolSpecList());
        renderGroups();
        this.persistentUiDataStorage.loadData(LAST_SELECTED_TOOL, new TypeReference<ToolSpec>() {},
                tool -> {
                    if (Objects.nonNull(tool)) {
                        this.toolSpecService.getToolSpecList().stream()
                                .filter(t -> t.toolId().equals(tool.toolId()))
                                .findFirst().ifPresent(this::selectInListBoxes);
                    }
                });
    }

    public ToolListView(PersistentUiDataStorage persistentUiDataStorage, ToolSpecService toolSpecService,
            ToolCategoryCatalog categoryCatalog, ToolActivationCalculator activationCalculator,
            PropertyChangeSupport toolChangeSupport) {
        super("Tool List");
        this.persistentUiDataStorage = persistentUiDataStorage;
        this.toolSpecService = toolSpecService;
        this.categoryCatalog = categoryCatalog;
        this.activationCalculator = activationCalculator;
        this.toolChangeSupport = toolChangeSupport;

        addHeaderIcon(VaadinIcon.CLOSE, "Delete Selected Tool", e -> deleteTool());

        this.searchField = new TextField();
        this.searchField.setPlaceholder("Search tools…");
        this.searchField.setClearButtonVisible(true);
        this.searchField.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        this.searchField.setWidthFull();
        this.searchField.addValueChangeListener(e -> {
            this.searchQuery = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT);
            renderGroups();
        });

        this.categoryFilter = new MultiSelectComboBox<>();
        this.categoryFilter.setPlaceholder("Category");
        this.categoryFilter.setWidthFull();
        this.categoryFilter.addValueChangeListener(e -> {
            this.categoryFilterSelection = e.getValue() == null ? Set.of() : Set.copyOf(e.getValue());
            renderGroups();
        });

        this.tagFilter = new MultiSelectComboBox<>();
        this.tagFilter.setPlaceholder("Tag");
        this.tagFilter.setWidthFull();
        this.tagFilter.addValueChangeListener(e -> {
            this.tagFilterSelection = e.getValue() == null ? Set.of() : Set.copyOf(e.getValue());
            renderGroups();
        });

        this.searchField.getStyle().set("flex", "1 1 100%");
        this.categoryFilter.getStyle().set("flex", "1 1 80px");
        this.categoryFilter.setMinWidth("0");
        this.tagFilter.getStyle().set("flex", "1 1 80px");
        this.tagFilter.setMinWidth("0");
        Div filtersBlock = new Div(this.searchField, this.categoryFilter, this.tagFilter);
        filtersBlock.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("gap", "var(--lumo-space-xs)")
                .set("padding", "0 var(--lumo-space-s) var(--lumo-space-s) var(--lumo-space-s)");

        this.groupContainer = new VerticalLayout();
        this.groupContainer.setPadding(false);
        this.groupContainer.setSpacing(false);
        this.groupContainer.setWidthFull();

        VerticalLayout body = new VerticalLayout(filtersBlock, this.groupContainer);
        body.setPadding(false);
        body.setSpacing(false);
        body.setWidthFull();
        setSidebarContent(verticalScroller(body));
    }

    private void renderGroups() {
        this.groupContainer.removeAll();
        this.categoryListBoxes.clear();

        List<ToolSpec> all = this.toolSpecService.getToolSpecList();
        List<ToolSpec> filtered = applyAllFilters(all);
        updateHeaderCount(filtered.size(), all.size());
        if (filtered.isEmpty()) {
            Span empty = new Span(all.isEmpty() ? "No tools yet." : "No tools match the current filters.");
            empty.getStyle()
                    .set("padding", "var(--lumo-space-m)")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            this.groupContainer.add(empty);
            return;
        }

        Map<String, List<ToolSpec>> byCategory = new LinkedHashMap<>();
        for (CategoryDef categoryDef : this.categoryCatalog.categories()) {
            byCategory.put(categoryDef.id(), new ArrayList<>());
        }
        for (ToolSpec tool : filtered) {
            String id = this.categoryCatalog.resolveOrFallback(tool.category()).id();
            byCategory.get(id).add(tool);
        }

        for (Map.Entry<String, List<ToolSpec>> entry : byCategory.entrySet()) {
            List<ToolSpec> bucket = entry.getValue();
            if (bucket.isEmpty()) continue;
            bucket.sort(Comparator.comparing(ToolSpec::name, String.CASE_INSENSITIVE_ORDER));
            CategoryDef categoryDef = this.categoryCatalog.find(entry.getKey()).orElseThrow();
            this.groupContainer.add(buildCategoryGroup(categoryDef, bucket));
        }
    }

    private void updateHeaderCount(int filteredCount, int totalCount) {
        String suffix = filteredCount == totalCount ? " (" + totalCount + ")"
                : " (" + filteredCount + " of " + totalCount + ")";
        setSidebarTitle("Tool List" + suffix);
    }

    private List<ToolSpec> applyAllFilters(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
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
        if (this.searchQuery.isBlank()) return true;
        String name = tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
        String desc = tool.description() == null ? "" : tool.description().toLowerCase(Locale.ROOT);
        return name.contains(this.searchQuery) || desc.contains(this.searchQuery);
    }

    private boolean matchesCategoryFilter(ToolSpec tool) {
        if (this.categoryFilterSelection.isEmpty()) return true;
        String resolved = this.categoryCatalog.resolveOrFallback(tool.category()).id();
        return this.categoryFilterSelection.contains(resolved);
    }

    private boolean matchesTagFilter(ToolSpec tool) {
        if (this.tagFilterSelection.isEmpty()) return true;
        for (String wanted : this.tagFilterSelection) {
            if (tool.tags().contains(wanted)) return true;  // ANY tag match passes
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
        this.categoryFilter.setItems(categoryItems);
        this.categoryFilter.setItemLabelGenerator(id -> this.categoryCatalog.resolveOrFallback(id).displayName());

        this.tagFilter.setItems(observedTags);
    }

    private Details buildCategoryGroup(CategoryDef categoryDef, List<ToolSpec> tools) {
        Span header = new Span(categoryDef.displayName() + "  (" + tools.size() + ")");
        header.getStyle()
                .set("font-weight", "var(--lumo-font-weight-semibold)")
                .set("font-size", "var(--lumo-font-size-s)");

        ListBox<ToolSpec> listBox = new ListBox<>();
        listBox.addClassName("custom-list-box");
        listBox.setWidthFull();
        listBox.setItems(tools);
        listBox.setRenderer(new ComponentRenderer<>(this::renderToolItem));
        listBox.addValueChangeListener(event -> notifyToolSelection(event.getOldValue(), event.getValue()));
        this.categoryListBoxes.add(listBox);

        Details details = new Details(header, listBox);
        details.addClassName("workspace-sidebar-details");
        details.setOpened(true);
        details.setWidthFull();
        details.getStyle().set("margin-bottom", "var(--lumo-space-xs)");
        return details;
    }

    private Div renderToolItem(ToolSpec tool) {
        Div row = new Div();
        row.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "0.35em")
                .set("width", "100%");

        // Activation state visible without clicking the row.
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(false);
        titleRow.setAlignItems(HorizontalLayout.Alignment.CENTER);
        titleRow.getStyle().set("gap", "0.4em");

        State state = this.activationCalculator.calculate(tool);
        Span dot = stateDot(state);
        dot.getElement().setAttribute("title", state.name());

        Span title = listItemText(tool.name());
        title.getElement().setAttribute("title", Objects.toString(tool.description(), ""));

        titleRow.add(dot, title);
        row.add(titleRow);

        if (!tool.tags().isEmpty()) {
            HorizontalLayout pillRow = new HorizontalLayout();
            pillRow.setSpacing(false);
            pillRow.getStyle().set("gap", "0.25em").set("flex-wrap", "wrap")
                    .set("padding-inline-start", "1em");  // align under title text, not dot
            for (String tag : tool.tags()) {
                pillRow.add(pill(tag, "badge success pill small"));
            }
            row.add(pillRow);
        }
        return row;
    }

    private static Span stateDot(State state) {
        Span dot = new Span();
        String color = switch (state) {
            case ACTIVE               -> "var(--lumo-success-color)";
            case TEST_FAILED          -> "var(--lumo-error-color)";
            case MISSING_REQUIREMENTS -> "#f0a500";  // amber — Vaadin Lumo lacks a stock 'warning' base color
            case DRAFT                -> "var(--lumo-contrast-30pct)";
        };
        dot.getStyle()
                .set("display", "inline-block")
                .set("width", "0.55em")
                .set("height", "0.55em")
                .set("border-radius", "50%")
                .set("background", color)
                .set("flex", "0 0 auto");
        return dot;
    }

    private static Span pill(String text, String themeAttr) {
        Span s = new Span(text);
        s.getElement().setAttribute("theme", themeAttr);
        s.getStyle()
                .set("font-size", "0.65em")
                .set("padding", "0 0.4em")
                .set("line-height", "1.4");
        return s;
    }

    private void notifyToolSelection(ToolSpec oldToolSpec, ToolSpec newToolSpec) {
        if (Objects.isNull(newToolSpec)) return;
        if (newToolSpec.equals(this.selectedSpec)) return;

        for (ListBox<ToolSpec> toolSpecListBox : this.categoryListBoxes) {
            if (!Objects.equals(toolSpecListBox.getValue(), newToolSpec)) {
                toolSpecListBox.clear();
            }
        }
        ToolSpec previous = this.selectedSpec;
        this.selectedSpec = newToolSpec;
        this.toolChangeSupport.firePropertyChange(TOOL_SELECT_EVENT, previous, newToolSpec);
        this.persistentUiDataStorage.saveData(LAST_SELECTED_TOOL, newToolSpec);
    }

    private void selectInListBoxes(ToolSpec target) {
        for (ListBox<ToolSpec> listBox : this.categoryListBoxes) {
            if (listBox.getListDataView().getItems().anyMatch(t -> t.toolId().equals(target.toolId()))) {
                listBox.setValue(target);
                return;
            }
        }
    }

    private void deleteTool() {
        this.getCurrentToolAsOpt().ifPresent(toolSpec -> {
            Dialog dialog = VaadinUtils.headerDialog("Delete Tool: " + toolSpec.name());
            dialog.setModal(true);
            dialog.add("Are you sure you want to delete toolSpec '" + toolSpec.name() + "' permanently?");

            Button deleteButton = new Button("Delete", e -> {
                this.toolSpecService.deleteToolSpec(toolSpec.toolId());
                this.changeToolContent(null);
                dialog.close();
            });
            deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
            dialog.getFooter().add(deleteButton);

            dialog.open();
            deleteButton.focus();
        });
    }

    public void changeToolContent(ToolSpec toolSpec) {
        VaadinUtils.getUi(this).access(() -> {
            List<ToolSpec> toolList = this.toolSpecService.getToolSpecList();
            refreshFilterOptions(toolList);

            if (toolList.isEmpty()) {
                this.selectedSpec = null;
                renderGroups();
                this.toolChangeSupport.firePropertyChange(TOOL_EMPTY_EVENT, false, true);
                return;
            }

            renderGroups();

            ToolSpec target = Objects.isNull(toolSpec)
                    ? toolList.getFirst()
                    : toolList.stream().filter(t -> t.name().equals(toolSpec.name())).findFirst()
                            .orElse(toolList.getFirst());
            selectInListBoxes(target);
        });
    }

    public void clearSelectTool() {
        for (ListBox<ToolSpec> listBox : this.categoryListBoxes) {
            listBox.clear();
        }
        this.selectedSpec = null;
    }

    private Optional<ToolSpec> getCurrentToolAsOpt() {
        return Optional.ofNullable(this.selectedSpec);
    }
}
