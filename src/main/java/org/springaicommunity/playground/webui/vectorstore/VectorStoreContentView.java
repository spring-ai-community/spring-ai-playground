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

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyDownEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.ColumnRendering;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.dataview.GridDataView;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springaicommunity.playground.webui.PersistentUiDataStorage;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.vaadin.crudui.crud.CrudOperation;
import org.vaadin.crudui.crud.impl.GridCrud;
import org.vaadin.crudui.form.CrudFormFactory;
import org.vaadin.crudui.form.impl.form.factory.DefaultCrudFormFactory;
import org.vaadin.crudui.layout.CrudLayout;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.vaadin.flow.component.grid.GridVariant.LUMO_NO_BORDER;
import static com.vaadin.flow.component.grid.GridVariant.LUMO_ROW_STRIPES;
import static com.vaadin.flow.component.grid.GridVariant.LUMO_WRAP_CELL_CONTENT;
import static org.springaicommunity.playground.service.vectorstore.VectorStoreService.ALL_SEARCH_REQUEST_OPTION;
import static org.springaicommunity.playground.service.vectorstore.VectorStoreService.DOC_INFO_ID;
import static org.springaicommunity.playground.service.vectorstore.VectorStoreService.SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION;

public class VectorStoreContentView extends VerticalLayout implements BeforeEnterObserver, BeforeLeaveObserver {

    private static final String LAST_SEARCH_REQUEST_OPTION = "lastSearchRequestOption";
    private record SearchRequestOption(String query, String searchFilter) {}

    public static final String DEFAULT_CUSTOM_DOCUMENT_NAME = "custom-document";
    private static final ObjectMapper ObjectMapper =
            JsonMapper.builder().findAndAddModules()
                    .enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
    private static final TypeReference<Media> MEDIA_TYPE_REFERENCE = new TypeReference<>() {};

    private final PersistentUiDataStorage persistentUiDataStorage;
    private final VectorStoreService vectorStoreService;
    private final OfflineEtlPipelineService offlineEtlPipelineService;
    private final Consumer<VectorStoreDocumentInfo> onCustomDocumentCreated;
    private final Supplier<Collection<VectorStoreDocumentInfo>> selectedDocumentsSupplier;
    private final GridCrud<VectorStoreContentItem> gridCrud;
    private final GridDataView<VectorStoreContentItem> dataView;
    private final TextField userPromptTextField;
    private final TextField filterExpressionTextField;
    private SearchRequest searchRequest;

    @Override
    public void beforeEnter(BeforeEnterEvent beforeEnterEvent) {
        this.persistentUiDataStorage.loadData(LAST_SEARCH_REQUEST_OPTION, new TypeReference<SearchRequestOption>() {},
                searchRequestOption -> {
                    if (Objects.nonNull(searchRequestOption)) {
                        this.userPromptTextField.setValue(searchRequestOption.query());
                        this.filterExpressionTextField.setValue(searchRequestOption.searchFilter());
                    }
                });
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent beforeLeaveEvent) {
        this.persistentUiDataStorage.saveData(LAST_SEARCH_REQUEST_OPTION,
                new SearchRequestOption(this.userPromptTextField.getValue(),
                        this.filterExpressionTextField.getValue()));
    }

    public VectorStoreContentView(PersistentUiDataStorage persistentUiDataStorage,
            VectorStoreService vectorStoreService, OfflineEtlPipelineService offlineEtlPipelineService,
            Consumer<VectorStoreDocumentInfo> onCustomDocumentCreated,
            Supplier<Collection<VectorStoreDocumentInfo>> selectedDocumentsSupplier) {
        this.persistentUiDataStorage = persistentUiDataStorage;
        this.vectorStoreService = vectorStoreService;
        this.offlineEtlPipelineService = offlineEtlPipelineService;
        this.onCustomDocumentCreated = onCustomDocumentCreated;
        this.selectedDocumentsSupplier = selectedDocumentsSupplier;

        setSizeFull();

        this.gridCrud = new GridCrud<>(VectorStoreContentItem.class,
                new DefaultCrudFormFactory<>(VectorStoreContentItem.class, new FormLayout.ResponsiveStep("0px", 1))) {

            @Override
            protected void deleteButtonClicked() {
                VectorStoreContentItem domainObject = grid.asSingleSelect().getValue();
                showForm(CrudOperation.DELETE, domainObject, true, deletedMessage, event -> {
                    try {
                        deleteOperation.perform(domainObject);
                        showAllDocuments();
                        grid.asSingleSelect().clear();
                    } catch (Exception e) {
                        VaadinUtils.showErrorNotification(e.getMessage());
                    }
                });
            }

            @Override
            protected void findAllButtonClicked() {
                grid.asSingleSelect().clear();
                showAllDocuments();
            }

            @Override
            protected void addButtonClicked() {
                openCustomChunkDialog();
            }

            private void afterRefreshGrid(VectorStoreContentItem vectorStoreContentItem) {
                showAllDocuments();
                VectorStoreContentItem realVectorStoreContentItem =
                        dataView.getItems().filter(item -> item.getId().equals(vectorStoreContentItem.getId()))
                                .findFirst().orElseGet(() -> dataView.getItem(0));
                grid.asSingleSelect().setValue(realVectorStoreContentItem);
                grid.deselect(realVectorStoreContentItem);
                grid.scrollToItem(realVectorStoreContentItem);
            }

            @Override
            protected void updateButtonClicked() {
                VectorStoreContentItem domainObject = grid.asSingleSelect().getValue();
                showForm(CrudOperation.UPDATE, domainObject, false, savedMessage, event -> {
                    try {
                        VectorStoreContentItem updatedObject = updateOperation.perform(domainObject);
                        grid.asSingleSelect().clear();
                        afterRefreshGrid(updatedObject);
                    } catch (Exception e) {
                        VaadinUtils.showErrorNotification(e.getMessage());
                    }
                });
            }

            @Override
            public void refreshGrid() {
                try {
                    super.refreshGrid();
                    grid.setEmptyStateText("No items found.");
                } catch (Exception e) {
                    VaadinUtils.showErrorNotification(e.getMessage());
                    grid.setItems(List.<VectorStoreContentItem>of());
                    grid.setEmptyStateText("Search unavailable - check the embedding provider configuration.");
                }
            }
        };

        Button findAllButton = this.gridCrud.getFindAllButton();
        findAllButton.getStyle().set("marginLeft", "var(--lumo-space-s)");
        findAllButton.setTooltipText("Search all");
        this.gridCrud.getAddButton().setTooltipText("Add a custom chunk");
        this.gridCrud.getUpdateButton().setTooltipText("Update a chunk");
        this.gridCrud.getDeleteButton().setTooltipText("Delete a chunk");
        new VectorStoreContentContextMenu(this.gridCrud);
        add(this.gridCrud);

        this.userPromptTextField = new TextField();
        userPromptTextField.setPlaceholder("Enter a prompt to test similarity search...");
        userPromptTextField.setWidth("60%");
        userPromptTextField.setAutofocus(true);
        userPromptTextField.focus();
        userPromptTextField.getStyle().setPadding("0");
        userPromptTextField.setClearButtonVisible(true);
        userPromptTextField.setValueChangeMode(ValueChangeMode.EAGER);
        Button searchButton =
                VaadinUtils.styledButton("Search", VaadinIcon.SEARCH.create(), buttonClickEvent -> refreshGrid());
        userPromptTextField.setSuffixComponent(searchButton);
        userPromptTextField.addKeyDownListener(Key.ENTER, event -> clickSearchButton(event, searchButton));

        this.filterExpressionTextField = new TextField();
        filterExpressionTextField.setPlaceholder("Enter a Spring AI metadata filters, e.g., country == 'BG'");
        filterExpressionTextField.setWidth("40%");
        filterExpressionTextField.getStyle().setPadding("0");
        filterExpressionTextField.setClearButtonVisible(true);
        Icon filterExpressionInfoIcon = VaadinUtils.styledIcon(VaadinIcon.INFO_CIRCLE.create());
        filterExpressionInfoIcon.addClickListener(event -> VaadinUtils.getUi(this).getPage()
                .open("https://docs.spring.io/spring-ai/reference/api/vectordbs.html#_filter_string"));
        filterExpressionInfoIcon.setTooltipText("Click for metadata filter documentation");
        filterExpressionTextField.setSuffixComponent(filterExpressionInfoIcon);
        filterExpressionTextField.addKeyDownListener(Key.ENTER, event -> clickSearchButton(event, searchButton));

        HorizontalLayout searchInputLayout =
                new HorizontalLayout(userPromptTextField, filterExpressionTextField);
        searchInputLayout.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        searchInputLayout.setJustifyContentMode(JustifyContentMode.START);
        searchInputLayout.setWidthFull();
        searchInputLayout.setPadding(false);
        searchInputLayout.getStyle().set("marginRight", "var(--lumo-space-s)");
        CrudLayout crudLayout = this.gridCrud.getCrudLayout();
        ((Component) crudLayout).addClassName("custom-container");
        crudLayout.addToolbarComponent(searchInputLayout);

        Grid<VectorStoreContentItem> grid = this.gridCrud.getGrid();
        this.dataView = grid.getGenericDataView();
        this.gridCrud.setOperations(
                () -> (Objects.isNull(this.searchRequest) ?
                        vectorStoreService.search(this.userPromptTextField.getValue(),
                                filterExpressionTextField.getValue(),
                                offlineEtlPipelineService.getChatOriginDocInfoIds()) : vectorStoreService.search(
                        this.searchRequest)).stream()
                        .map(this::convertToViewDocument).toList(),
                item -> { throw new UnsupportedOperationException("Use openCustomChunkDialog() instead"); },
                item -> convertToViewDocument(this.vectorStoreService.update(convertToDocument(item))),
                item -> vectorStoreService.delete(
                        grid.getSelectedItems().stream().map(VectorStoreContentItem::getId).toList()));

        grid.addThemeVariants(LUMO_NO_BORDER, LUMO_WRAP_CELL_CONTENT, LUMO_ROW_STRIPES);
        grid.setColumns("score", "id", "text", "metadata", "media");
        grid.setColumnReorderingAllowed(true);
        grid.setColumnRendering(ColumnRendering.LAZY);
        grid.setEmptyStateText("No items found.");
        Map<String, String> columnWidths = Map.of(
                "Score", "10%",
                "Id", "15%",
                "Text", "50%",
                "Metadata", "20%",
                "Media", "5%"
        );
        grid.getColumns().forEach(column -> {
            column.setResizable(true);
            column.setWidth(columnWidths.get(column.getHeaderText()));
        });

        CrudFormFactory<VectorStoreContentItem> crudFormFactory = this.gridCrud.getCrudFormFactory();
        crudFormFactory.setUseBeanValidation(true);
        crudFormFactory.setVisibleProperties("id", "text", "metadata", "media");
        crudFormFactory.setDisabledProperties("score", "id", "media");

        crudFormFactory.setFieldProvider("text", o -> {
            TextArea textArea = new TextArea();
            textArea.setWidthFull();
            return textArea;
        });
        crudFormFactory.setFieldProvider("metadata", o -> {
            TextArea textArea = new TextArea();
            textArea.setWidthFull();
            textArea.setPlaceholder("Example JSON object:\n{ \"key\": \"value\" }");
            return textArea;
        });
    }

    private static void clickSearchButton(KeyDownEvent event, Button searchButton) {
        if (!event.isComposing())
            searchButton.click();
    }

    private void openCustomChunkDialog() {
        Dialog dialog = VaadinUtils.headerDialog("Add chunk to a document");
        dialog.setModality(ModalityMode.STRICT);
        dialog.setWidth("520px");

        List<VectorStoreDocumentInfo> existingDocs = this.offlineEtlPipelineService.getVisibleDocumentList();

        ComboBox<String> nameCombo = new ComboBox<>("Document name");
        nameCombo.setItems(existingDocs.stream().map(VectorStoreDocumentInfo::title).toList());
        nameCombo.setAllowCustomValue(true);
        nameCombo.addCustomValueSetListener(event -> nameCombo.setValue(event.getDetail()));
        nameCombo.setWidthFull();
        Optional<VectorStoreDocumentInfo> sidebarSelected = Objects.isNull(this.selectedDocumentsSupplier) ?
                Optional.empty() : this.selectedDocumentsSupplier.get().stream().findFirst();
        sidebarSelected.ifPresentOrElse(selected -> {
            nameCombo.setValue(selected.title());
            nameCombo.setHelperText("Adding to the document selected on the left. Pick another or type a new name.");
        }, () -> {
            nameCombo.setValue(DEFAULT_CUSTOM_DOCUMENT_NAME);
            nameCombo.setHelperText("No document selected on the left, pick an existing one or type a new name "
                    + "(default: " + DEFAULT_CUSTOM_DOCUMENT_NAME + ").");
        });

        TextArea textArea = new TextArea("Text");
        textArea.setWidthFull();
        textArea.setMinHeight("8em");

        TextArea metadataArea = new TextArea("Metadata (JSON, optional)");
        metadataArea.setWidthFull();
        metadataArea.setPlaceholder("Example JSON object:\n{ \"key\": \"value\" }");

        VerticalLayout body = new VerticalLayout(nameCombo, textArea, metadataArea);
        body.setPadding(true);
        body.setSpacing(false);
        dialog.add(body);

        Button save = new Button("Add", event -> {
            String name = Optional.ofNullable(nameCombo.getValue()).map(String::trim).orElse("");
            String text = Optional.ofNullable(textArea.getValue()).orElse("");
            if (name.isBlank() || text.isBlank()) {
                VaadinUtils.showErrorNotification("Document name and text are required");
                return;
            }
            try {
                Document raw = buildSimpleDocument(text, metadataArea.getValue());
                addChunkToDocument(name, raw, existingDocs);
                showAllDocuments();
                dialog.close();
            } catch (Exception ex) {
                VaadinUtils.showErrorNotification(ex.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", event -> dialog.close());
        dialog.getFooter().add(save, cancel);

        dialog.open();
        nameCombo.focus();
    }

    private Document buildSimpleDocument(String text, String metadataJson) {
        Map<String, Object> metadata = new HashMap<>();
        if (Objects.nonNull(metadataJson) && !metadataJson.isBlank())
            metadata.putAll(readToObject(metadataJson, MAP_TYPE_REFERENCE));
        return new Document(text, metadata);
    }

    private void addChunkToDocument(String name, Document raw, List<VectorStoreDocumentInfo> existingDocs) {
        VectorStoreDocumentInfo existing =
                existingDocs.stream().filter(d -> name.equals(d.title())).findFirst().orElse(null);
        if (Objects.nonNull(existing)) {
            Map<String, Object> withDocInfo = new HashMap<>(raw.getMetadata());
            withDocInfo.put(DOC_INFO_ID, existing.docInfoId());
            this.vectorStoreService.add(List.of(new Document(raw.getText(), withDocInfo)));
            return;
        }
        VectorStoreDocumentInfo created = this.offlineEtlPipelineService.loadDocument(name, List.of(raw));
        this.vectorStoreService.add(created);
        if (Objects.nonNull(this.onCustomDocumentCreated))
            this.onCustomDocumentCreated.accept(created);
    }

    private VectorStoreContentItem convertToViewDocument(Document document) {
        return new VectorStoreContentItem(document.getScore(), document.getId(), document.getText(),
                Objects.nonNull(document.getMedia()) ? writeAsJson(document.getMedia()) : null,
                writeAsJson(document.getMetadata()));
    }

    private String writeAsJson(Object value) {
        try {
            return ObjectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Error converting value to JSON", e);
        }
    }

    private <T> T readToObject(String jsonString, TypeReference<T> typeReference) {
        try {
            return ObjectMapper.readValue(jsonString, typeReference);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Error converting JSON to " + typeReference.getType().getTypeName(), e);
        }
    }

    private Document convertToDocument(VectorStoreContentItem vectorStoreContentItem) {
        Document.Builder builder = new Document.Builder();
        Optional.ofNullable(vectorStoreContentItem.getId()).filter(Predicate.not(String::isBlank))
                .ifPresent(builder::id);
        builder.metadata(
                Optional.ofNullable(vectorStoreContentItem.getMetadata()).filter(Predicate.not(String::isBlank))
                        .map(metadata -> readToObject(metadata, MAP_TYPE_REFERENCE)).orElseGet(Map::of));
        Optional.ofNullable(vectorStoreContentItem.getMedia()).filter(Predicate.not(String::isBlank))
                .map(media -> readToObject(media, MEDIA_TYPE_REFERENCE)).ifPresent(builder::media);
        return builder.text(vectorStoreContentItem.getText()).build();
    }

    public void showDocuments(List<String> selectDocInfoIds) {
        this.searchRequest = selectDocInfoIds.isEmpty() ? null :
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(selectDocInfoIds);
        refreshGrid();

    }

    public void showAllDocuments() {
        this.searchRequest =
                new SearchRequest.Builder().similarityThreshold(ALL_SEARCH_REQUEST_OPTION.similarityThreshold())
                        .topK(ALL_SEARCH_REQUEST_OPTION.topK()).build();
        refreshGrid();
    }

    private void refreshGrid() {
        try {
            this.gridCrud.refreshGrid();
            VaadinUtils.showInfoNotification(String.format("Search results: %d items (Threshold: %.2f, TopK: %d)",
                    dataView.getItems().count(), Objects.nonNull(this.searchRequest) ?
                            ALL_SEARCH_REQUEST_OPTION.similarityThreshold() : vectorStoreService.getSearchRequestOption()
                            .similarityThreshold(),
                    Objects.nonNull(this.searchRequest) ? ALL_SEARCH_REQUEST_OPTION.topK()
                            : vectorStoreService.getSearchRequestOption().topK()));
            this.searchRequest = null;
        } catch (Exception e) {
            VaadinUtils.showErrorNotification(e.getMessage());
        }
    }

    private static class VectorStoreContentContextMenu extends GridContextMenu<VectorStoreContentItem> {
        public VectorStoreContentContextMenu(GridCrud<VectorStoreContentItem> gridCrud) {
            super(gridCrud.getGrid());
            Grid<VectorStoreContentItem> grid = gridCrud.getGrid();
            addItem("Edit", e -> e.getItem().ifPresent(item -> {
                grid.select(item);
                gridCrud.getUpdateButton().click();
            }));
            addItem("Delete", e -> e.getItem().ifPresent(item -> {
                grid.select(item);
                gridCrud.getDeleteButton().click();
            }));
            setDynamicContentHandler(Objects::nonNull);
        }

    }
}
