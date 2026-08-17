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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.listbox.MultiSelectListBox;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.analytics.UsageAnalyticsService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springaicommunity.playground.webui.PersistentUiDataStorage;
import org.springaicommunity.playground.webui.SpringAiPlaygroundAppLayout;
import org.springaicommunity.playground.webui.UsageEventTracker;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.ContentWorkspaceView;
import org.springaicommunity.playground.webui.common.WorkspaceSettingsDrawer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.springaicommunity.playground.webui.VaadinUtils.styledButton;

@SpringComponent
@UIScope
@AnonymousAllowed
@CssImport("./playground/vectorstore-styles.css")
@PageTitle("Vector Database")
@Route(value = "vector-database", layout = SpringAiPlaygroundAppLayout.class)
public class VectorStoreView extends ContentWorkspaceView {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreView.class);

    public static final String DOCUMENT_SELECTING_EVENT = "DOCUMENT_SELECTING_EVENT";
    public static final String DOCUMENT_ADDING_EVENT = "DOCUMENT_ADDING_EVENT";
    public static final String DOCUMENTS_DELETE_EVENT = "DOCUMENTS_DELETE_EVENT";
    public static final String PIPELINE_SELECTING_EVENT = "PIPELINE_SELECTING_EVENT";
    public static final String PIPELINE_DELETE_EVENT = "PIPELINE_DELETE_EVENT";

    private static final double NAIVE_SPLITTER_POSITION = 100;
    private static final double PIPELINE_SPLITTER_POSITION = 50;

    private final VectorStoreService vectorStoreService;
    private final OfflineEtlPipelineService offlineEtlPipelineService;
    private final UsageAnalyticsService usageAnalyticsService;
    private final UsageEventTracker usageEventTracker;
    private final RagPipelineService ragPipelineService;
    private final VectorStoreDocumentView vectorStoreDocumentView;
    private final VectorStorePipelineListView vectorStorePipelineListView;
    private final VectorStoreContentView vectorStoreContentView;
    private final VectorStorePipelineRunView vectorStorePipelineRunView;
    private final WorkspaceSettingsDrawer searchSettingsDrawer;
    private final SplitLayout mainSplitLayout;
    private final NewRagPipelineDialog newRagPipelineDialog;

    public VectorStoreView(PersistentUiDataStorage persistentUiDataStorage, VectorStoreService vectorStoreService,
            OfflineEtlPipelineService offlineEtlPipelineService, UsageAnalyticsService usageAnalyticsService,
            UsageEventTracker usageEventTracker, RagPipelineService ragPipelineService,
            RagPipelineExecutor ragPipelineExecutor) {
        this.vectorStoreService = vectorStoreService;
        this.offlineEtlPipelineService = offlineEtlPipelineService;
        this.usageAnalyticsService = usageAnalyticsService;
        this.usageEventTracker = usageEventTracker;
        this.ragPipelineService = ragPipelineService;

        this.vectorStoreDocumentView =
                new VectorStoreDocumentView(offlineEtlPipelineService, buildDocumentChangeSupport());
        this.vectorStorePipelineListView = new VectorStorePipelineListView(ragPipelineService,
                buildPipelineChangeSupport(), this::onEditPipelineRequested);
        this.vectorStoreDocumentView.enableCollapse();
        this.vectorStorePipelineListView.enableCollapse();
        this.vectorStoreDocumentView.setOnSelectionStart(this.vectorStorePipelineListView::clearSelection);
        this.vectorStorePipelineListView.setOnSelectionStart(this.vectorStoreDocumentView::clearSelection);
        configureSidebar(buildSidebar(), "Sidebar");

        installNewDocumentDialog();
        this.newRagPipelineDialog = new NewRagPipelineDialog(ragPipelineService, offlineEtlPipelineService,
                vectorStoreService, this::onPipelineSaved);
        installNewRagPipelineButton();

        setHeaderCenter(buildEmbeddingModelServiceTextDiv());

        VectorStoreSearchSettingView vectorStoreSearchSettingView =
                new VectorStoreSearchSettingView(this.vectorStoreService);
        this.searchSettingsDrawer = installSettingsDrawer(VaadinIcon.COG_O, "Search Settings",
                "Search Settings");
        this.searchSettingsDrawer.setBody(vectorStoreSearchSettingView);

        this.vectorStoreContentView = new VectorStoreContentView(persistentUiDataStorage, vectorStoreService,
                offlineEtlPipelineService, this::onCustomChunkCreatedNewDocument,
                this.vectorStoreDocumentView::getSelectedDocumentInfos);
        this.vectorStoreContentView.setSpacing(false);
        this.vectorStoreContentView.setMargin(false);
        this.vectorStoreContentView.setPadding(false);

        this.vectorStorePipelineRunView = new VectorStorePipelineRunView(ragPipelineExecutor);
        this.vectorStorePipelineRunView.setOnEditRequest(this::onEditPipelineRequested);

        this.mainSplitLayout = new SplitLayout(SplitLayout.Orientation.VERTICAL);
        this.mainSplitLayout.setSizeFull();
        this.mainSplitLayout.addToPrimary(this.vectorStoreContentView);
        this.mainSplitLayout.addToSecondary(this.vectorStorePipelineRunView);
        this.mainSplitLayout.setSplitterPosition(NAIVE_SPLITTER_POSITION);
        this.mainSplitLayout.addClassName("vstore-main-split");
        setContent(this.mainSplitLayout);
    }

    private VerticalLayout buildSidebar() {
        H4 sourcesHeader = new H4("Sources");
        sourcesHeader.getStyle()
                .set("margin", "0")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("text-transform", "uppercase")
                .set("letter-spacing", "0.05em")
                .set("flex", "0 0 auto");

        VerticalLayout sidebar = new VerticalLayout(sourcesHeader, this.vectorStoreDocumentView,
                this.vectorStorePipelineListView);
        sidebar.setPadding(false);
        sidebar.setSpacing(false);
        sidebar.setSizeFull();
        sidebar.addClassName("vstore-sidebar");
        return sidebar;
    }

    private void installNewRagPipelineButton() {
        Button newRagPipelineButton = styledButton("New RAG Pipeline", VaadinIcon.AUTOMATION.create(),
                e -> this.newRagPipelineDialog.openForCreate());
        addHeaderAction(newRagPipelineButton);
    }

    private void onPipelineSaved(RagPipeline pipeline) {
        this.vectorStorePipelineListView.refreshPipelines();
        this.vectorStorePipelineListView.selectPipeline(pipeline);
    }

    private void onCustomChunkCreatedNewDocument(VectorStoreDocumentInfo created) {
        this.vectorStoreDocumentView.beforeEnter(null);
        this.vectorStorePipelineListView.refreshPipelines();
    }

    private void onEditPipelineRequested(RagPipeline pipeline) {
        this.newRagPipelineDialog.openForEdit(pipeline);
    }

    private void installNewDocumentDialog() {
        Button newDocumentButton =
                styledButton("New Document & ETL Pipeline", VaadinIcon.FILE_ADD.create(), null);
        addHeaderAction(newDocumentButton);

        Dialog newDocumentDialog = VaadinUtils.headerDialog("New Document & ETL Pipeline");
        newDocumentDialog.setModality(ModalityMode.STRICT);
        newDocumentDialog.setWidth("760px");
        newDocumentDialog.setMaxWidth("96vw");
        newDocumentDialog.setMaxHeight("94vh");
        newDocumentDialog.setResizable(true);
        newDocumentButton.addClickListener(e -> newDocumentDialog.open());

        VectorStoreDocumentUpload vectorStoreDocumentUpload =
                new VectorStoreDocumentUpload(this.offlineEtlPipelineService);

        ComboBox<OfflineEtlPipelineService.ReaderType> readerCombo = new ComboBox<>("Document Reader");
        readerCombo.setItems(OfflineEtlPipelineService.ReaderType.values());
        readerCombo.setValue(OfflineEtlPipelineService.ReaderType.TIKA);
        readerCombo.setWidthFull();
        readerCombo.setHelperText("Auto-recommended from the uploaded file's extension. Override as needed.");

        TextField charsetField = new TextField("Charset");
        charsetField.setValue("UTF-8");
        TextField jsonKeysField = new TextField("JSON keys");
        jsonKeysField.setPlaceholder("title, body (empty = whole objects)");
        TextField htmlSelectorField = new TextField("CSS selector");
        htmlSelectorField.setValue("body");
        Checkbox mdCodeBlockCheck = new Checkbox("Include code blocks", true);
        Checkbox mdBlockquoteCheck = new Checkbox("Include blockquotes", true);
        Checkbox mdHorizontalRuleCheck = new Checkbox("Split at horizontal rules", false);
        IntegerField pdfPagesField = new IntegerField("Pages per document");
        pdfPagesField.setValue(1);
        pdfPagesField.setMin(1);
        pdfPagesField.setStepButtonsVisible(true);

        Div readerOptions = new Div(charsetField, jsonKeysField, htmlSelectorField, mdCodeBlockCheck,
                mdBlockquoteCheck, mdHorizontalRuleCheck, pdfPagesField);
        readerOptions.getStyle().set("display", "flex").set("flex-wrap", "wrap")
                .set("gap", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("align-items", "baseline").set("width", "100%");
        Runnable refreshReaderOptions = () -> {
            OfflineEtlPipelineService.ReaderType type = readerCombo.getValue();
            charsetField.setVisible(type == OfflineEtlPipelineService.ReaderType.TEXT
                    || type == OfflineEtlPipelineService.ReaderType.HTML);
            jsonKeysField.setVisible(type == OfflineEtlPipelineService.ReaderType.JSON);
            htmlSelectorField.setVisible(type == OfflineEtlPipelineService.ReaderType.HTML);
            boolean markdown = type == OfflineEtlPipelineService.ReaderType.MARKDOWN;
            mdCodeBlockCheck.setVisible(markdown);
            mdBlockquoteCheck.setVisible(markdown);
            mdHorizontalRuleCheck.setVisible(markdown);
            pdfPagesField.setVisible(type == OfflineEtlPipelineService.ReaderType.PDF_PAGE);
            readerOptions.setVisible(type != OfflineEtlPipelineService.ReaderType.TIKA
                    && type != OfflineEtlPipelineService.ReaderType.PDF_PARAGRAPH);
        };
        readerCombo.addValueChangeListener(e -> refreshReaderOptions.run());
        refreshReaderOptions.run();

        Span extractLabel = popoverSubLabel("Extract");
        VerticalLayout extractBlock = new VerticalLayout(extractLabel, readerCombo, readerOptions);
        extractBlock.setPadding(false);
        extractBlock.setSpacing(false);
        extractBlock.setWidthFull();

        TextField nameField = new TextField("Name");
        nameField.setRequired(true);
        nameField.setWidthFull();
        nameField.setHelperText("Auto-filled from the uploaded filename. Edit to give it a friendlier title.");
        TextField docDescField = new TextField("Description");
        docDescField.setMaxLength(200);
        docDescField.setWidthFull();
        docDescField.setHelperText("Short note shown when picking documents for a RAG pipeline.");

        Span infoLabel = popoverSubLabel("Document info");
        VerticalLayout infoBlock = new VerticalLayout(infoLabel, nameField, docDescField);
        infoBlock.setPadding(false);
        infoBlock.setSpacing(false);
        infoBlock.setWidthFull();

        vectorStoreDocumentUpload.setOnFileUploaded(fileName -> {
            String baseName = stripExtension(fileName);
            String current = nameField.getValue();
            if (current == null || current.isBlank()) nameField.setValue(baseName);
            readerCombo.setValue(OfflineEtlPipelineService.ReaderType.recommendFor(fileName));
        });
        vectorStoreDocumentUpload.setOnFileRemoved(fileName -> {
            String baseName = stripExtension(fileName);
            if (baseName.equals(nameField.getValue())) {
                nameField.clear();
                nameField.setInvalid(false);
            }
            readerCombo.setValue(OfflineEtlPipelineService.ReaderType.TIKA);
        });

        VectorStoreDocumentTokenChunkInfo vectorStoreDocumentTokenChunkInfo = new VectorStoreDocumentTokenChunkInfo();

        Checkbox keywordCheck = new Checkbox("Keyword metadata (LLM)");
        IntegerField keywordCountField = new IntegerField("Keywords");
        keywordCountField.setValue(5);
        keywordCountField.setMin(1);
        keywordCountField.setMax(20);
        keywordCountField.setWidth("7em");
        keywordCountField.setEnabled(false);
        keywordCheck.addValueChangeListener(e -> keywordCountField.setEnabled(Boolean.TRUE.equals(e.getValue())));

        Checkbox summaryCheck = new Checkbox("Summary metadata (LLM)");
        Checkbox summaryPrevCheck = new Checkbox("Previous", true);
        Checkbox summaryNextCheck = new Checkbox("Next", true);
        summaryPrevCheck.setEnabled(false);
        summaryNextCheck.setEnabled(false);
        summaryCheck.addValueChangeListener(e -> {
            boolean on = Boolean.TRUE.equals(e.getValue());
            summaryPrevCheck.setEnabled(on);
            summaryNextCheck.setEnabled(on);
        });

        Span enrichHint = new Span("Each enabled enricher costs 1 LLM call per chunk, run before embedding "
                + "so results are reviewable in the chunk summary.");
        enrichHint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("display", "block");

        HorizontalLayout keywordRow = new HorizontalLayout(keywordCheck, keywordCountField);
        keywordRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        HorizontalLayout summaryRow = new HorizontalLayout(summaryCheck, summaryPrevCheck, summaryNextCheck);
        summaryRow.setAlignItems(FlexComponent.Alignment.BASELINE);

        Span enrichLabel = popoverSubLabel("Metadata Enrichment (optional)");
        VerticalLayout enrichBlock = new VerticalLayout(enrichLabel, keywordRow, summaryRow, enrichHint);
        enrichBlock.setPadding(false);
        enrichBlock.setSpacing(false);
        enrichBlock.setWidthFull();

        VerticalLayout body = new VerticalLayout(vectorStoreDocumentUpload, extractBlock, infoBlock,
                vectorStoreDocumentTokenChunkInfo, enrichBlock);
        body.setPadding(true);
        body.setSpacing(false);
        body.setWidthFull();
        body.setAlignItems(FlexComponent.Alignment.STRETCH);
        newDocumentDialog.add(body);

        Button chunkDocumentButton = new Button("Chunk Document", VaadinIcon.SCISSORS.create());
        chunkDocumentButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button("Cancel", e -> newDocumentDialog.close());
        newDocumentDialog.getFooter().add(cancelButton, chunkDocumentButton);

        AtomicBoolean filesHandedOff = new AtomicBoolean(false);

        newDocumentDialog.addOpenedChangeListener(event -> {
            if (event.isOpened()) {
                filesHandedOff.set(false);
                return;
            }
            // Dialog dismissed (X, Cancel, Esc) before chunking — remove orphan staged files.
            if (!filesHandedOff.get())
                removeStagedFiles(vectorStoreDocumentUpload.getUploadedFileNames());
        });

        chunkDocumentButton.addClickListener(buttonClickEvent -> {
            String titleValue = nameField.getValue();
            String resolvedTitle = titleValue == null || titleValue.isBlank() ? null : titleValue.trim();
            String descValue = docDescField.getValue();
            String resolvedDescription = descValue == null || descValue.isBlank() ? null : descValue.trim();
            OfflineEtlPipelineService.ExtractOptions extractOptions = new OfflineEtlPipelineService.ExtractOptions(
                    readerCombo.getValue(), charsetField.getValue(), jsonKeysField.getValue(),
                    htmlSelectorField.getValue(), Boolean.TRUE.equals(mdCodeBlockCheck.getValue()),
                    Boolean.TRUE.equals(mdBlockquoteCheck.getValue()),
                    Boolean.TRUE.equals(mdHorizontalRuleCheck.getValue()),
                    pdfPagesField.getValue() == null ? 1 : pdfPagesField.getValue());
            OfflineEtlPipelineService.EnrichOptions enrichOptions = new OfflineEtlPipelineService.EnrichOptions(
                    Boolean.TRUE.equals(keywordCheck.getValue()),
                    keywordCountField.getValue() == null ? 5 : keywordCountField.getValue(),
                    Boolean.TRUE.equals(summaryCheck.getValue()),
                    Boolean.TRUE.equals(summaryPrevCheck.getValue()),
                    Boolean.TRUE.equals(summaryNextCheck.getValue()));

            List<String> uploadedFileNames = new ArrayList<>(vectorStoreDocumentUpload.getUploadedFileNames());
            filesHandedOff.set(true);
            newDocumentDialog.close();
            vectorStoreDocumentUpload.clearFileList();
            // Reset dialog fields so reopening starts clean. Also clear the invalid flag —
            // clear() on a touched required field would otherwise leave the red error state visible.
            nameField.clear();
            nameField.setInvalid(false);
            docDescField.clear();
            docDescField.setInvalid(false);
            if (uploadedFileNames.isEmpty()) {
                VaadinUtils.showInfoNotification("No uploaded files found");
                return;
            }

            Map<String, List<Document>> uploadedDocumentItems;
            try {
                uploadedDocumentItems = this.offlineEtlPipelineService.extractAndTransform(uploadedFileNames,
                        extractOptions, offlineEtlPipelineService.newTokenTextSplitter(
                                vectorStoreDocumentTokenChunkInfo.collectInput()));
            } catch (Exception e) {
                logger.warn("Chunk extraction failed", e);
                VaadinUtils.showErrorNotification("Chunk extraction failed: " + e.getMessage());
                removeStagedFiles(uploadedFileNames);
                return;
            }
            List<Document> chunks = uploadedDocumentItems.values().stream().flatMap(List::stream).toList();
            if (chunks.isEmpty()) {
                VaadinUtils.showInfoNotification("No chunks found");
                removeStagedFiles(uploadedFileNames);
                return;
            }

            if (enrichOptions.llmCallsPerChunk() > 0) {
                runEnrichmentThenConfirm(uploadedFileNames, uploadedDocumentItems, chunks, enrichOptions,
                        resolvedTitle, resolvedDescription);
            } else {
                openChunkConfirmationDialog(uploadedFileNames, uploadedDocumentItems, chunks, resolvedTitle,
                        resolvedDescription);
            }
        });
    }

    private void runEnrichmentThenConfirm(List<String> uploadedFileNames,
            Map<String, List<Document>> uploadedDocumentItems, List<Document> chunks,
            OfflineEtlPipelineService.EnrichOptions enrichOptions, String resolvedTitle, String resolvedDescription) {
        int totalCalls = chunks.size() * enrichOptions.llmCallsPerChunk();
        Dialog progressDialog = VaadinUtils.headerDialog(
                String.format("Enriching %d chunks · %d LLM calls", chunks.size(), totalCalls));
        progressDialog.setModality(ModalityMode.STRICT);
        progressDialog.setWidth("440px");
        progressDialog.setCloseOnEsc(false);
        progressDialog.setCloseOnOutsideClick(false);

        ProgressBar progressBar = new ProgressBar();
        progressBar.setIndeterminate(true);
        Span progressLabel = new Span("Running enrichers…");
        progressLabel.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");
        VerticalLayout progressBody = new VerticalLayout(progressLabel, progressBar);
        progressBody.setPadding(true);
        progressBody.setSpacing(false);
        progressDialog.add(progressBody);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Button cancelButton = new Button("Cancel", e -> {
            cancelled.set(true);
            progressLabel.setText("Cancelling, keeping chunks enriched so far…");
        });
        progressDialog.getFooter().add(cancelButton);
        progressDialog.open();

        UI ui = UI.getCurrent();
        new Thread(() -> {
            List<Document> enriched;
            try {
                enriched = this.offlineEtlPipelineService.enrich(chunks, enrichOptions,
                        (done, total) -> ui.access(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setMin(0);
                            progressBar.setMax(total);
                            progressBar.setValue(done);
                            progressLabel.setText(String.format("Keyword enrichment %d / %d chunks", done, total));
                        }), cancelled::get);
            } catch (Exception ex) {
                logger.warn("Enrichment failed", ex);
                ui.access(() -> {
                    progressDialog.close();
                    VaadinUtils.showErrorNotification("Enrichment failed: " + ex.getMessage()
                            + ", continuing with plain chunks");
                    openChunkConfirmationDialog(uploadedFileNames, uploadedDocumentItems, chunks, resolvedTitle,
                            resolvedDescription);
                });
                return;
            }
            Map<String, List<Document>> enrichedByFile = new java.util.LinkedHashMap<>();
            int offset = 0;
            for (Map.Entry<String, List<Document>> entry : uploadedDocumentItems.entrySet()) {
                int size = entry.getValue().size();
                enrichedByFile.put(entry.getKey(), enriched.subList(offset, offset + size));
                offset += size;
            }
            ui.access(() -> {
                progressDialog.close();
                openChunkConfirmationDialog(uploadedFileNames, enrichedByFile, enriched, resolvedTitle,
                        resolvedDescription);
            });
        }, "etl-enrich").start();
    }

    private void openChunkConfirmationDialog(List<String> uploadedFileNames,
            Map<String, List<Document>> uploadedDocumentItems, List<Document> chunks, String resolvedTitle,
            String resolvedDescription) {
        int totalChars = chunks.stream().mapToInt(d -> d.getText() == null ? 0 : d.getText().length()).sum();
        int avgChars = chunks.isEmpty() ? 0 : totalChars / chunks.size();

        IdentityHashMap<Document, Integer> chunkIndex = new IdentityHashMap<>();
        for (int i = 0; i < chunks.size(); i++) chunkIndex.put(chunks.get(i), i + 1);

        MultiSelectListBox<Document> documentListBox = new MultiSelectListBox<>();
        documentListBox.setItems(chunks);
        documentListBox.select(chunks);
        documentListBox.setWidthFull();
        documentListBox.setRenderer(new ComponentRenderer<Component, Document>(
                document -> renderChunkCard(document, chunkIndex.getOrDefault(document, 0))));

        Div embedSummary = buildEmbeddingSummary(uploadedFileNames, resolvedTitle, resolvedDescription, chunks.size(),
                totalChars, avgChars);

        Span chunksLabel = popoverSubLabel("Chunks (uncheck any to skip embedding)");
        Span selectionHint = new Span(chunks.size() + " of " + chunks.size() + " selected");
        selectionHint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("margin-left", "auto");
        HorizontalLayout chunksHeader = new HorizontalLayout(chunksLabel, selectionHint);
        chunksHeader.setWidthFull();
        chunksHeader.setAlignItems(FlexComponent.Alignment.BASELINE);
        chunksHeader.setSpacing(false);
        chunksHeader.setPadding(false);
        documentListBox.addSelectionListener(event -> selectionHint.setText(
                event.getValue().size() + " of " + chunks.size() + " selected"));

        Div chunksScroller = new Div(documentListBox);
        chunksScroller.setWidthFull();
        // Cap height so the dialog (default max-height ~90vh) doesn't overflow once you add the
        // header / footer / summary / chunks-header chrome — that would force an outer scrollbar.
        // calc() leaves room for ~320px of chrome and grows with the viewport.
        chunksScroller.getStyle().set("max-height", "calc(90vh - 320px)").set("overflow-y", "auto")
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background", "var(--lumo-base-color)");

        VerticalLayout body = new VerticalLayout(embedSummary, chunksHeader, chunksScroller);
        body.setPadding(true);
        body.setSpacing(false);
        body.setWidthFull();
        body.setAlignItems(FlexComponent.Alignment.STRETCH);
        body.getStyle().set("width", "min(1500px, 90vw)");

        Dialog confirmationDialog = VaadinUtils.headerDialog(
                String.format("Chunk Summary · %d chunks extracted · ~%,d chars total", chunks.size(), totalChars));
        confirmationDialog.setModality(ModalityMode.STRICT);
        confirmationDialog.setMaxWidth("95vw");
        confirmationDialog.setMinWidth("720px");
        confirmationDialog.setDraggable(true);
        confirmationDialog.setResizable(true);
        confirmationDialog.add(body);

        AtomicBoolean confirmed = new AtomicBoolean(false);
        confirmationDialog.addOpenedChangeListener(event -> {
            if (event.isOpened() || confirmed.get()) return;
            // Cancel / X / Esc — staged files were never embedded, so remove them.
            removeStagedFiles(uploadedFileNames);
        });

        Button confirmButton = new Button("Embed and insert", VaadinIcon.CHECK.create());
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        Button cancelButton = new Button("Cancel", e -> confirmationDialog.close());
        confirmationDialog.getFooter().add(cancelButton, confirmButton);
        confirmationDialog.open();

        confirmButton.addClickListener(event -> {
            Set<Document> selectedItems = documentListBox.getSelectedItems();
            if (selectedItems.isEmpty()) {
                VaadinUtils.showInfoNotification("Select at least one chunk to embed");
                return;
            }
            confirmed.set(true);
            confirmationDialog.setEnabled(false);
            confirmationDialog.close();
            Map<String, List<Document>> filenameDocuments =
                    uploadedDocumentItems.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> entry.getValue().stream().filter(selectedItems::contains).toList()));
            this.vectorStoreDocumentView.addDocumentContent(uploadedFileNames, filenameDocuments, resolvedTitle,
                    resolvedDescription);
        });
    }

    private static Component renderChunkCard(Document document, int oneBasedIndex) {
        String text = document.getText() == null ? "" : document.getText();
        Object source = document.getMetadata().get("source");

        Span indexBadge = new Span("#" + oneBasedIndex);
        indexBadge.getStyle().set("font-weight", "600").set("font-family", "var(--lumo-font-family-mono)")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("background", "var(--lumo-primary-color-10pct)")
                .set("color", "var(--lumo-primary-text-color)")
                .set("padding", "2px var(--lumo-space-xs)")
                .set("border-radius", "var(--lumo-border-radius-s)");

        Span charCount = new Span(String.format("%,d chars", text.length()));
        charCount.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)");

        HorizontalLayout meta = new HorizontalLayout(indexBadge, charCount);
        meta.setSpacing(true);
        meta.setPadding(false);
        meta.setAlignItems(FlexComponent.Alignment.CENTER);
        if (source != null) {
            Span sourceTag = new Span(source.toString());
            sourceTag.getStyle().set("color", "var(--lumo-tertiary-text-color)")
                    .set("font-size", "var(--lumo-font-size-xs)").set("margin-left", "auto")
                    .set("white-space", "nowrap").set("overflow", "hidden")
                    .set("text-overflow", "ellipsis").set("max-width", "520px");
            meta.add(sourceTag);
            meta.setFlexGrow(1, sourceTag);
        }
        meta.setWidthFull();

        Div body = new Div();
        body.setText(text);
        body.getStyle().set("white-space", "normal").set("word-break", "break-word")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("font-family", "var(--lumo-font-family)")
                .set("font-size", "var(--lumo-font-size-s)").set("line-height", "1.55")
                .set("margin", "var(--lumo-space-xs) 0 0 0").set("max-height", "260px")
                .set("overflow-y", "auto").set("color", "var(--lumo-body-text-color)");

        VerticalLayout card = new VerticalLayout(meta, body);
        card.setPadding(false);
        card.setSpacing(false);
        card.setMargin(false);
        card.setWidthFull();
        // STRETCH so the Pre body fills the card width — default flex-start would shrink it to
        // intrinsic width and waste the wide dialog real estate the user explicitly asked for.
        card.setAlignItems(FlexComponent.Alignment.STRETCH);
        card.getStyle().set("padding", "var(--lumo-space-xs) 0");

        appendMetadataLine(card, "Keywords", document.getMetadata().get("excerpt_keywords"));
        appendMetadataLine(card, "Summary", document.getMetadata().get("section_summary"));
        appendMetadataLine(card, "Prev summary", document.getMetadata().get("prev_section_summary"));
        appendMetadataLine(card, "Next summary", document.getMetadata().get("next_section_summary"));
        return card;
    }

    private static void appendMetadataLine(VerticalLayout card, String label, Object value) {
        if (value == null || value.toString().isBlank()) return;
        String text = value.toString().trim();
        if (text.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT) + ":"))
            text = text.substring(label.length() + 1).trim();
        Span line = new Span(label + ": " + text);
        line.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("background", "var(--lumo-primary-color-10pct)")
                .set("padding", "2px var(--lumo-space-s)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("margin-top", "var(--lumo-space-xs)");
        card.add(line);
    }

    private Div buildEmbeddingSummary(List<String> uploadedFileNames, String resolvedTitle, String resolvedDescription,
            int chunkCount, int totalChars, int avgChars) {
        Div embedSummary = new Div();
        embedSummary.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("margin-bottom", "var(--lumo-space-s)");

        Span summaryHeader = new Span("Embedding as");
        summaryHeader.getStyle().set("display", "block").set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("text-transform", "uppercase")
                .set("letter-spacing", "0.05em");

        String summaryName = resolvedTitle == null ? uploadedFileNames.get(0) : resolvedTitle;
        Span nameSummary = new Span(summaryName);
        nameSummary.getStyle().set("display", "block").set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-m)").set("margin-top", "2px");
        embedSummary.add(summaryHeader, nameSummary);

        if (resolvedDescription != null) {
            Span descSummary = new Span(resolvedDescription);
            descSummary.getStyle().set("display", "block")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)").set("margin-top", "2px");
            embedSummary.add(descSummary);
        }

        String stats = String.format("%s · %d chunks · ~%,d chars · avg %,d", uploadedFileNames.get(0), chunkCount,
                totalChars, avgChars);
        Span statsLine = new Span(stats);
        statsLine.getStyle().set("display", "block")
                .set("color", "var(--lumo-tertiary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("margin-top", "var(--lumo-space-xs)")
                .set("font-family", "var(--lumo-font-family-mono)");
        embedSummary.add(statsLine);
        return embedSummary;
    }

    private void removeStagedFiles(List<String> fileNames) {
        for (String fileName : fileNames) {
            try {
                this.offlineEtlPipelineService.removeUploadedDocumentFile(fileName);
            } catch (Exception e) {
                logger.warn("Failed to remove staged file {}: {}", fileName, e.getMessage());
            }
        }
    }

    private static Span popoverSubLabel(String text) {
        Span s = new Span(text);
        s.getStyle().set("display", "block").set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-body-text-color)")
                .set("margin", "var(--lumo-space-m) 0 var(--lumo-space-xs) 0");
        return s;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private PropertyChangeSupport buildDocumentChangeSupport() {
        PropertyChangeSupport documentInfoChangeSupport = new PropertyChangeSupport(this);
        documentInfoChangeSupport.addPropertyChangeListener(changeEvent -> {
            if (Objects.isNull(changeEvent.getNewValue()))
                return;
            Collection<VectorStoreDocumentInfo> newDocumentInfos =
                    (Collection<VectorStoreDocumentInfo>) changeEvent.getNewValue();
            switch (changeEvent.getPropertyName()) {
                case DOCUMENT_ADDING_EVENT -> handleDocumentAdding(newDocumentInfos);
                case DOCUMENT_SELECTING_EVENT -> handleDocumentSelecting(newDocumentInfos);
                case DOCUMENTS_DELETE_EVENT -> handleDocumentDeleting(newDocumentInfos);
            }
        });
        return documentInfoChangeSupport;
    }

    private PropertyChangeSupport buildPipelineChangeSupport() {
        PropertyChangeSupport pipelineChangeSupport = new PropertyChangeSupport(this);
        pipelineChangeSupport.addPropertyChangeListener(changeEvent -> {
            if (Objects.isNull(changeEvent.getNewValue()))
                return;
            Collection<RagPipeline> selected = (Collection<RagPipeline>) changeEvent.getNewValue();
            switch (changeEvent.getPropertyName()) {
                case PIPELINE_SELECTING_EVENT -> handlePipelineSelecting(selected);
                case PIPELINE_DELETE_EVENT -> handlePipelineDeleting(selected);
            }
        });
        return pipelineChangeSupport;
    }

    private void handleDocumentAdding(Collection<VectorStoreDocumentInfo> newEventDocumentInfos) {
        newEventDocumentInfos.forEach(this.vectorStoreService::add);
        this.vectorStorePipelineListView.refreshPipelines();
        newEventDocumentInfos.forEach(docInfo -> this.usageEventTracker.track(UI.getCurrent(),
                "rag_document_indexed", this.usageAnalyticsService.documentIndexedParams(
                        docInfo.getDocumentFileName(), docInfo.documentListSupplier().get().size())));
        handleDocumentSelecting(newEventDocumentInfos);
    }

    private void handleDocumentSelecting(Collection<VectorStoreDocumentInfo> newEventDocumentInfos) {
        this.vectorStoreContentView.showDocuments(
                newEventDocumentInfos.stream().map(VectorStoreDocumentInfo::docInfoId).toList());
        switchToNaiveMode();
    }

    private void handleDocumentDeleting(Collection<VectorStoreDocumentInfo> newEventDocumentInfos) {
        this.vectorStoreService.delete(
                newEventDocumentInfos.stream().map(VectorStoreDocumentInfo::documentListSupplier).map(Supplier::get)
                        .flatMap(List::stream).map(Document::getId).toList());
        vectorStoreContentView.showAllDocuments();
    }

    private void handlePipelineSelecting(Collection<RagPipeline> selectedPipelines) {
        if (selectedPipelines.isEmpty()) {
            switchToNaiveMode();
            return;
        }
        RagPipeline pipeline = selectedPipelines.iterator().next();
        this.vectorStorePipelineRunView.showPipeline(pipeline);
        switchToPipelineMode();
    }

    private void handlePipelineDeleting(Collection<RagPipeline> deletedPipelines) {
        switchToNaiveMode();
        this.vectorStorePipelineRunView.showPlaceholder();
    }

    private void switchToNaiveMode() {
        this.mainSplitLayout.setSplitterPosition(NAIVE_SPLITTER_POSITION);
    }

    private void switchToPipelineMode() {
        this.mainSplitLayout.setSplitterPosition(PIPELINE_SPLITTER_POSITION);
    }

    private Div buildEmbeddingModelServiceTextDiv() {
        H4 embeddingModelServiceText = buildEmbeddingModelServiceText();
        embeddingModelServiceText.getStyle().set("white-space", "nowrap").set("margin", "0");
        Div embeddingModelServiceTextDiv = new Div(embeddingModelServiceText);
        embeddingModelServiceTextDiv.getStyle().set("display", "flex").set("justify-content", "center")
                .set("align-items", "center").set("height", "100%");
        return embeddingModelServiceTextDiv;
    }

    private H4 buildEmbeddingModelServiceText() {
        EmbeddingOptions embeddingOptions = this.vectorStoreService.getEmbeddingOptions();
        return new H4(Objects.nonNull(embeddingOptions.getDimensions()) ?
                String.format("%s - %s: %s - %d", this.vectorStoreService.getVectorStoreName(),
                        vectorStoreService.getEmbeddingModelServiceName(), embeddingOptions.getModel(),
                        embeddingOptions.getDimensions()) :
                String.format("%s - %s: %s", this.vectorStoreService.getVectorStoreName(),
                        this.vectorStoreService.getEmbeddingModelServiceName(), embeddingOptions.getModel()));
    }
}
