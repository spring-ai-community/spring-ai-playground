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
package org.springaicommunity.playground.webui.vectorstore;

import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.WorkspaceSidebar;

import java.beans.PropertyChangeSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.springaicommunity.playground.webui.vectorstore.VectorStoreView.PIPELINE_DELETE_EVENT;
import static org.springaicommunity.playground.webui.vectorstore.VectorStoreView.PIPELINE_SELECTING_EVENT;

public class VectorStorePipelineListView extends WorkspaceSidebar implements BeforeEnterObserver {

    private final RagPipelineService ragPipelineService;
    private final ListBox<RagPipeline> pipelineListBox;
    private final PropertyChangeSupport pipelineChangeSupport;
    private final Consumer<RagPipeline> onEditRequest;
    private Runnable onSelectionStart;

    public VectorStorePipelineListView(RagPipelineService ragPipelineService,
            PropertyChangeSupport pipelineChangeSupport, Consumer<RagPipeline> onEditRequest) {
        super("RAG Pipelines");
        this.ragPipelineService = ragPipelineService;
        this.pipelineChangeSupport = pipelineChangeSupport;
        this.onEditRequest = onEditRequest;

        addHeaderIcon(VaadinIcon.CLOSE, "Delete", e -> deletePipeline());
        addHeaderIcon(VaadinIcon.PENCIL, "Edit", e -> editPipeline());

        this.pipelineListBox = new ListBox<>();
        this.pipelineListBox.addClassName("custom-list-box");
        this.pipelineListBox.setWidthFull();
        this.pipelineListBox.getStyle().set("overflow-x", "hidden").set("white-space", "nowrap");
        this.pipelineListBox.setRenderer(new ComponentRenderer<>(pipeline -> {
            HorizontalLayout row = new HorizontalLayout();
            row.setAlignItems(Alignment.CENTER);
            Span name = listItemText(pipeline.name());
            name.getElement().setAttribute("title", LocalDateTime
                    .ofInstant(Instant.ofEpochMilli(pipeline.updatedAt()), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            Span ago = new Span(formatRelative(pipeline.updatedAt()));
            ago.getStyle().set("font-size", "var(--lumo-font-size-xxs)")
                    .set("color", "var(--lumo-secondary-text-color)").set("margin-left", "auto");
            row.add(name, ago);
            row.setWidthFull();
            return row;
        }));
        this.pipelineListBox.addValueChangeListener(event -> {
            RagPipeline newValue = event.getValue();
            if (Objects.isNull(newValue)) return;
            if (Objects.nonNull(this.onSelectionStart)) this.onSelectionStart.run();
            this.pipelineChangeSupport.firePropertyChange(PIPELINE_SELECTING_EVENT,
                    Objects.isNull(event.getOldValue()) ? List.of() : List.of(event.getOldValue()),
                    List.of(newValue));
        });

        setSidebarContent(this.pipelineListBox);
    }

    public void setOnSelectionStart(Runnable onSelectionStart) {
        this.onSelectionStart = onSelectionStart;
    }

    public void clearSelection() {
        VaadinUtils.getUi(this).access(this.pipelineListBox::clear);
    }

    private void editPipeline() {
        RagPipeline selected = this.pipelineListBox.getValue();
        if (Objects.isNull(selected) || Objects.isNull(this.onEditRequest)) return;
        this.onEditRequest.accept(selected);
    }

    private void deletePipeline() {
        RagPipeline selected = this.pipelineListBox.getValue();
        if (Objects.isNull(selected)) return;

        String headerTitle = String.format("Delete: %s", selected.name());
        Dialog dialog = VaadinUtils.headerDialog(headerTitle);
        dialog.setModality(ModalityMode.STRICT);
        dialog.add("Are you sure you want to delete this pipeline?");

        Button deleteButton = new Button("Delete", e -> {
            this.ragPipelineService.deleteById(selected.id());
            refreshPipelines();
            this.pipelineChangeSupport.firePropertyChange(PIPELINE_DELETE_EVENT, null, List.of(selected));
            dialog.close();
        });
        deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        deleteButton.getStyle().set("margin-right", "auto");
        deleteButton.focus();
        dialog.getFooter().add(deleteButton);

        Button cancelButton = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelButton);
        dialog.open();
    }

    public void refreshPipelines() {
        VaadinUtils.getUi(this).access(() -> {
            this.pipelineListBox.removeAll();
            this.pipelineListBox.setItems(this.ragPipelineService.list());
        });
    }

    public void selectPipeline(RagPipeline pipeline) {
        VaadinUtils.getUi(this).access(() -> this.pipelineListBox.setValue(pipeline));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent beforeEnterEvent) {
        refreshPipelines();
    }

    private static String formatRelative(long epochMs) {
        long now = System.currentTimeMillis();
        Duration age = Duration.ofMillis(Math.max(0L, now - epochMs));
        if (age.toMinutes() < 1) return "just now";
        if (age.toHours() < 1) return age.toMinutes() + "m ago";
        if (age.toDays() < 1) return age.toHours() + "h ago";
        return age.toDays() + "d ago";
    }
}
