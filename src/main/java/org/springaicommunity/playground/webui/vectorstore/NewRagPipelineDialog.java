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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dialog.DialogVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AnchorTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H6;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NewRagPipelineDialog extends Dialog {

    static final String DEFAULT_REWRITE_TEMPLATE =
            "Given a user query, rewrite it to provide better results when querying a {target}.\n"
                    + "Remove any irrelevant information, and ensure the query is concise and specific.\n\n"
                    + "Original query:\n{query}\n\nRewritten query:\n";

    static final String DEFAULT_COMPRESSION_TEMPLATE =
            "Given the following conversation history and a follow-up query, your task is to synthesize\n"
                    + "a concise, standalone query that incorporates the context from the history.\n"
                    + "Ensure the standalone query is clear, specific, and maintains the user's intent.\n\n"
                    + "Conversation history:\n{history}\n\nFollow-up query:\n{query}\n\nStandalone query:\n";

    static final String DEFAULT_TRANSLATION_TEMPLATE =
            "Given a user query, translate it to {targetLanguage}.\n"
                    + "If the query is already in {targetLanguage}, return it unchanged.\n"
                    + "If you don't know the language of the query, return it unchanged.\n"
                    + "Do not add explanations nor any other text.\n\n"
                    + "Original query: {query}\n\nTranslated query:\n";

    static final String DEFAULT_MULTI_QUERY_TEMPLATE =
            "You are an expert at information retrieval and search optimization.\n"
                    + "Your task is to generate {number} different versions of the given query.\n\n"
                    + "Each variant must cover different perspectives or aspects of the topic,\n"
                    + "while maintaining the core intent of the original query. The goal is to\n"
                    + "expand the search space and improve the chances of finding relevant information.\n\n"
                    + "Do not explain your choices or add any other text.\n"
                    + "Provide the query variants separated by newlines.\n\n"
                    + "Original query: {query}\n\nQuery variants:\n";

    static final String DEFAULT_AUGMENTER_TEMPLATE =
            "Context information is below.\n\n---------------------\n{context}\n---------------------\n\n"
                    + "Given the context information and no prior knowledge, answer the query.\n\n"
                    + "Follow these rules:\n\n"
                    + "1. If the answer is not in the context, just say that you don't know.\n"
                    + "2. Avoid statements like \"Based on the context...\" or \"The provided information...\".\n\n"
                    + "Query: {query}\n\nAnswer:\n";

    static final String DEFAULT_EMPTY_CONTEXT_TEMPLATE =
            "The user query is outside your knowledge base.\n"
                    + "Politely inform the user that you can't answer it.\n";

    private static final String FILTER_DSL_HINT =
            "Operators: == != < <= > >= && || ! · in [v1, v2] · nin [v1, v2]\n"
                    + "Strings in single quotes, booleans/numbers bare. Combine with && / ||.";

    private static final String SPRING_AI_RAG_DOCS_URL =
            "https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html";

    private static final String INTRO_TEXT =
            "Build a retrieval pipeline by composing four independent stages: query transformation, vector "
                    + "retrieval, post-processing, and prompt augmentation. Enable only the stages your use case "
                    + "needs.";

    private static final String PRE_RETRIEVAL_DESC =
            "The first stage reshapes the raw user query before it touches the vector store, so the embedding "
                    + "search has the cleanest possible input. Each transformer is independent: chain or skip per "
                    + "use case. Every enabled stage costs one extra LLM call.";

    private static final String REWRITE_DESC =
            "Cleans up verbose or conversational input into a search-optimized form.";

    private static final String COMPRESSION_DESC =
            "Folds chat history + follow-up into one standalone query. Chat-only, a no-op for single-shot.";

    private static final String TRANSLATION_DESC =
            "Translates the query into the embedding model's primary language. Useful for cross-lingual corpora.";

    private static final String MULTI_QUERY_DESC =
            "Generates N reworded variants and joins the results. Better recall at N× embedding + 1× LLM cost.";

    private static final String RETRIEVAL_DESC =
            "Vector similarity search over the selected documents. topK, similarityThreshold and the filter "
                    + "expression are stored on this pipeline, independent of the main view's Search Settings.";

    private static final String POST_RETRIEVAL_DESC =
            "Refines the raw candidate set before it reaches the prompt. Re-ranking re-scores documents, critical "
                    + "after Multi-Query joining where results from N variants are unordered and likely overlap. "
                    + "Truncation trims the list to fit the model's context window and mitigates the "
                    + "\"lost-in-the-middle\" effect. No LLM call.";

    private static final String GENERATION_DESC =
            "Assembles the final prompt and (optionally) invokes the model. The augmenter templates retrieved "
                    + "documents and the user query into a single grounded prompt. \"allowEmptyContext\" controls "
                    + "fallback when retrieval is empty; \"Run LLM after augment\" actually dispatches the prompt "
                    + "to the model; leave it off to inspect the prompt without spending tokens.";

    private final RagPipelineService ragPipelineService;
    private final OfflineEtlPipelineService offlineEtlPipelineService;
    private final VectorStoreService vectorStoreService;
    private final Consumer<RagPipeline> onSaved;

    private final TextField nameField = new TextField("Name");
    private final TextField descField = new TextField("Description");
    private final CheckboxGroup<VectorStoreDocumentInfo> documentsCheckGroup = new CheckboxGroup<>();

    private static final String DEFAULT_REWRITE_TARGET = "vector store";

    private final Checkbox rewriteCheck = new Checkbox("Rewrite");
    private final TextField rewriteTargetField = new TextField("Target search system");
    private final TextArea rewriteTemplateField = new TextArea();

    private final Checkbox compressionCheck = new Checkbox("Compression");
    private final TextArea compressionTemplateField = new TextArea();

    private final Checkbox translationCheck = new Checkbox("Translation");
    private final ComboBox<String> translationLangCombo = new ComboBox<>("Target language");
    private final TextArea translationTemplateField = new TextArea();

    private final Checkbox multiQueryCheck = new Checkbox("Multi-Query expansion");
    private final IntegerField multiQueryCountField = new IntegerField("N");
    private final Checkbox multiQueryIncludeOriginalCheck = new Checkbox("include original query");
    private final TextArea multiQueryTemplateField = new TextArea();

    private final IntegerField topKField = new IntegerField("Top K");
    private final NumberField similarityThresholdField = new NumberField("Similarity Threshold (0 = All)");
    private final TextField extraFilterField = new TextField("Filter expression (metadata)");

    private final Checkbox reRankCheck = new Checkbox("Re-rank by score (descending)");
    private final IntegerField topNTruncateField = new IntegerField("Top-N truncate");

    private final TextArea promptTemplateField = new TextArea();
    private final ComboBox<RagPipeline.DocumentFormat> documentFormatCombo = new ComboBox<>("Context format");
    private final Checkbox allowEmptyContextCheck = new Checkbox("Allow empty context");
    private final TextArea emptyContextTemplateField = new TextArea();
    private final Checkbox runAugmentLLMCheck = new Checkbox("Run LLM after augment (call ChatModel for the answer)");

    private Tabs tabs;
    private Div contentArea;
    private final LinkedHashMap<Tab, Component> tabPanels = new LinkedHashMap<>();
    private Button prevBtn;
    private Button nextBtn;

    private ExpandableCard rewriteCard;
    private ExpandableCard compressionCard;
    private ExpandableCard translationCard;
    private ExpandableCard multiQueryCard;
    private ExpandableCard augmenterCard;
    private ExpandableCard emptyContextCard;

    private RagPipeline editingPipeline;

    public NewRagPipelineDialog(RagPipelineService ragPipelineService,
            OfflineEtlPipelineService offlineEtlPipelineService, VectorStoreService vectorStoreService,
            Consumer<RagPipeline> onSaved) {
        this.ragPipelineService = ragPipelineService;
        this.offlineEtlPipelineService = offlineEtlPipelineService;
        this.vectorStoreService = vectorStoreService;
        this.onSaved = onSaved;
        configureDialog();
        configureFields();
        add(buildBody());
        getFooter().add(buildSaveButton(), buildCancelButton());
    }

    public void openForCreate() {
        this.editingPipeline = null;
        setHeaderTitle("New RAG Pipeline");
        applyValues(null);
        this.tabs.setSelectedIndex(0);
        updateNavButtons();
        open();
        this.nameField.focus();
    }

    public void openForEdit(RagPipeline pipeline) {
        this.editingPipeline = pipeline;
        setHeaderTitle("Edit RAG Pipeline · " + pipeline.name());
        applyValues(pipeline);
        this.tabs.setSelectedIndex(0);
        updateNavButtons();
        open();
    }

    private void configureDialog() {
        setModality(ModalityMode.STRICT);
        setResizable(true);
        setDraggable(true);
        setWidth("1040px");
        setMaxWidth("96vw");
        setHeight("960px");
        setMinHeight("760px");
        setMaxHeight("94vh");
        addThemeVariants(DialogVariant.LUMO_NO_PADDING);
    }

    private void configureFields() {
        this.nameField.setRequired(true);
        this.nameField.setWidthFull();
        this.descField.setWidthFull();
        this.descField.setMaxLength(200);

        this.documentsCheckGroup.setItemLabelGenerator(VectorStoreDocumentInfo::title);
        this.documentsCheckGroup.setRenderer(new ComponentRenderer<>(document -> {
            Div wrapper = new Div();
            wrapper.getStyle().set("display", "flex").set("flex-direction", "column").set("line-height", "1.3");
            Span title = new Span(document.title());
            title.getStyle().set("font-weight", "500");
            wrapper.add(title);
            if (document.description() != null && !document.description().isBlank()) {
                Span desc = new Span(document.description());
                desc.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                        .set("color", "var(--lumo-body-text-color)");
                wrapper.add(desc);
            }
            int chunkCount = document.documentListSupplier() == null ? 0 : document.documentListSupplier().get().size();
            String meta = chunkCount + " chunk" + (chunkCount == 1 ? "" : "s") + " · updated "
                    + formatRelative(document.updateTimestamp());
            Span metaSpan = new Span(meta);
            metaSpan.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                    .set("color", "var(--lumo-secondary-text-color)");
            wrapper.add(metaSpan);
            return wrapper;
        }));
        this.documentsCheckGroup.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        this.documentsCheckGroup.setWidthFull();
        this.documentsCheckGroup.getStyle()
                .set("max-height", "160px")
                .set("overflow-y", "auto")
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-s)");

        this.rewriteTargetField.setPlaceholder(DEFAULT_REWRITE_TARGET);
        this.rewriteTargetField.setWidth("260px");
        this.rewriteTargetField.setHelperText(
                "Fills the {target} placeholder, e.g. \"web search\". Blank = \"vector store\".");
        configureTemplateField(this.rewriteTemplateField, "Required placeholders: {target} {query}");
        configureTemplateField(this.compressionTemplateField, "Required placeholders: {history} {query}");
        configureTemplateField(this.translationTemplateField, "Required placeholders: {targetLanguage} {query}");
        configureTemplateField(this.multiQueryTemplateField, "Required placeholders: {number} {query}");

        this.translationLangCombo.setItems("english", "korean", "japanese", "chinese", "spanish", "french", "german",
                "italian", "portuguese", "russian", "arabic", "hindi");
        this.translationLangCombo.setAllowCustomValue(true);
        this.translationLangCombo.setValue("english");
        this.translationLangCombo.addCustomValueSetListener(e -> this.translationLangCombo.setValue(e.getDetail()));

        this.multiQueryCountField.setMin(1);
        this.multiQueryCountField.setMax(10);
        this.multiQueryCountField.setStepButtonsVisible(true);
        this.multiQueryCountField.setValue(3);

        this.topKField.setMin(1);
        this.topKField.setMax(1000);
        this.topKField.setStepButtonsVisible(true);
        this.topKField.setHelperText("Number of documents to return per query.");

        this.similarityThresholdField.setMin(0d);
        this.similarityThresholdField.setMax(1d);
        this.similarityThresholdField.setStep(0.05);
        this.similarityThresholdField.setStepButtonsVisible(true);
        this.similarityThresholdField.setHelperText("0.0 = no filter (accept all), 1.0 = exact match only.");

        this.extraFilterField.setPlaceholder("country == 'KR' && year >= 2020");
        this.extraFilterField.setWidthFull();
        this.extraFilterField.setHelperText("Optional metadata filter applied at retrieval time. Click ⓘ for the DSL.");

        this.reRankCheck.setValue(true);
        this.topNTruncateField.setMin(1);
        this.topNTruncateField.setMax(1000);
        this.topNTruncateField.setStepButtonsVisible(true);
        this.topNTruncateField.setHelperText("blank = no truncate. Keep only the top-N after re-ranking.");

        this.documentFormatCombo.setItems(RagPipeline.DocumentFormat.values());
        this.documentFormatCombo.setItemLabelGenerator(format ->
                format == RagPipeline.DocumentFormat.TEXT_WITH_SOURCE
                        ? "Text with source label" : "Document text only (default)");
        this.documentFormatCombo.setValue(RagPipeline.DocumentFormat.TEXT);
        this.documentFormatCombo.setWidth("280px");
        this.documentFormatCombo.setHelperText(
                "How retrieved chunks are rendered into {context}. Source labels help the model attribute answers.");

        configureTemplateField(this.promptTemplateField, "Required placeholders: {query} {context}");
        configureTemplateField(this.emptyContextTemplateField,
                "Used only when context is empty AND \"Allow empty context\" is enabled.");
        this.emptyContextTemplateField.setEnabled(false);
        this.allowEmptyContextCheck.addValueChangeListener(e -> this.emptyContextTemplateField.setEnabled(e.getValue()));
    }

    private static void configureTemplateField(TextArea field, String helperText) {
        field.setLabel("Prompt template");
        field.setWidthFull();
        field.setMinHeight("90px");
        field.setMaxHeight("220px");
        field.setHelperText(helperText);
    }

    private VerticalLayout buildBody() {
        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.setAlignItems(FlexComponent.Alignment.STRETCH);

        body.add(buildIntroBanner());
        body.add(buildIdentityHeader());
        body.add(buildTabs());
        body.addAndExpand(buildContentArea());
        body.add(buildNavRow());

        return body;
    }

    private Component buildIntroBanner() {
        Div banner = new Div();
        banner.getStyle()
                .set("padding", "var(--lumo-space-m) var(--lumo-space-l)")
                .set("background", "var(--lumo-primary-color-10pct)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        H6 title = new H6("Spring AI's Advanced Retrieval pipeline · Modular RAG");
        title.getStyle().set("margin", "0").set("color", "var(--lumo-primary-text-color)");

        Anchor docsLink = new Anchor(SPRING_AI_RAG_DOCS_URL, "");
        docsLink.setTarget(AnchorTarget.BLANK);
        docsLink.add(new Span("Spring AI RAG reference"));
        Icon ext = new Icon(VaadinIcon.EXTERNAL_LINK);
        ext.setSize("0.85em");
        ext.getStyle().set("margin-left", "4px").set("vertical-align", "middle");
        docsLink.add(ext);
        docsLink.getStyle().set("font-size", "var(--lumo-font-size-s)").set("margin-left",
                "var(--lumo-space-m)").set("white-space", "nowrap");

        HorizontalLayout titleRow = new HorizontalLayout(title, docsLink);
        titleRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        titleRow.getStyle().set("flex-wrap", "wrap").set("row-gap", "4px");

        Span desc = new Span(INTRO_TEXT);
        desc.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)").set("line-height", "1.5")
                .set("display", "block").set("margin-top", "4px");

        banner.add(titleRow, desc);
        return banner;
    }

    private Component buildIdentityHeader() {
        Div header = new Div();
        header.getStyle()
                .set("padding", "var(--lumo-space-m) var(--lumo-space-l)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("display", "grid")
                .set("grid-template-columns", "minmax(180px, 1fr) minmax(280px, 2fr)")
                .set("gap", "var(--lumo-space-m)")
                .set("align-items", "start");
        this.nameField.setWidthFull();
        this.descField.setWidthFull();
        header.add(this.nameField, this.descField);
        return header;
    }

    private Tabs buildTabs() {
        this.tabs = new Tabs();
        addTab("① Pre-Retrieval", buildPreRetrievalPanel());
        addTab("② Retrieval", buildRetrievalPanel());
        addTab("③ Post-Retrieval", buildPostRetrievalPanel());
        addTab("④ Generation", buildGenerationPanel());
        this.tabs.setWidthFull();
        this.tabs.getStyle()
                .set("flex", "0 0 auto")
                .set("padding", "0 var(--lumo-space-m)")
                .set("box-sizing", "border-box");
        this.tabs.addSelectedChangeListener(e -> {
            this.contentArea.removeAll();
            this.contentArea.add(this.tabPanels.get(e.getSelectedTab()));
            this.contentArea.getElement().executeJs("this.scrollTop = 0");
            updateNavButtons();
        });
        return this.tabs;
    }

    private void addTab(String label, Component panel) {
        Tab tab = new Tab(label);
        this.tabs.add(tab);
        this.tabPanels.put(tab, panel);
    }

    private Div buildContentArea() {
        this.contentArea = new Div();
        this.contentArea.getStyle()
                .set("flex", "1 1 auto")
                .set("min-height", "0")
                .set("min-width", "0")
                .set("overflow-y", "auto")
                .set("overflow-x", "hidden");
        Tab firstTab = (Tab) this.tabs.getComponentAt(0);
        this.contentArea.add(this.tabPanels.get(firstTab));
        return this.contentArea;
    }

    private void updateNavButtons() {
        int idx = this.tabs.getSelectedIndex();
        this.prevBtn.setEnabled(idx > 0);
        this.nextBtn.setEnabled(idx < this.tabPanels.size() - 1);
    }

    private HorizontalLayout buildNavRow() {
        this.prevBtn = new Button("Previous", VaadinIcon.ARROW_LEFT.create(), e -> {
            int idx = this.tabs.getSelectedIndex();
            if (idx > 0) this.tabs.setSelectedIndex(idx - 1);
        });
        this.nextBtn = new Button("Next", VaadinIcon.ARROW_RIGHT.create(), e -> {
            int idx = this.tabs.getSelectedIndex();
            if (idx < this.tabPanels.size() - 1) this.tabs.setSelectedIndex(idx + 1);
        });
        this.nextBtn.setIconAfterText(true);
        this.nextBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout row = new HorizontalLayout(this.prevBtn, this.nextBtn);
        row.setWidthFull();
        row.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        row.getStyle()
                .set("padding", "var(--lumo-space-s) var(--lumo-space-l)")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("background", "var(--lumo-base-color)");
        return row;
    }

    private Component buildPreRetrievalPanel() {
        VerticalLayout panel = newPanel();
        panel.add(stepHeader("Pre-Retrieval (Query Transformation)", llmBadge()));
        panel.add(stepDescription(PRE_RETRIEVAL_DESC));

        VerticalLayout rewriteBody = new VerticalLayout(this.rewriteTargetField);
        rewriteBody.setPadding(false);
        rewriteBody.setSpacing(false);
        this.rewriteCard = new ExpandableCard(this.rewriteCheck, "Rewrite", REWRITE_DESC,
                this.rewriteTemplateField, rewriteBody);
        this.compressionCard = new ExpandableCard(this.compressionCheck, "Compression", COMPRESSION_DESC,
                this.compressionTemplateField, null);
        VerticalLayout transBody = new VerticalLayout(this.translationLangCombo);
        transBody.setPadding(false);
        transBody.setSpacing(false);
        this.translationCard = new ExpandableCard(this.translationCheck, "Translation", TRANSLATION_DESC,
                this.translationTemplateField, transBody);
        VerticalLayout mqBody = new VerticalLayout();
        mqBody.setPadding(false);
        mqBody.setSpacing(false);
        HorizontalLayout mqRow = new HorizontalLayout(this.multiQueryCountField, this.multiQueryIncludeOriginalCheck);
        mqRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        mqBody.add(mqRow);
        this.multiQueryCard = new ExpandableCard(this.multiQueryCheck, "Multi-Query expansion", MULTI_QUERY_DESC,
                this.multiQueryTemplateField, mqBody);

        panel.add(this.rewriteCard, this.compressionCard, this.translationCard, this.multiQueryCard);
        return panel;
    }

    private Component buildRetrievalPanel() {
        VerticalLayout panel = newPanel();
        panel.add(stepHeader("Retrieval (Vector Similarity Search)", localBadge()));
        panel.add(stepDescription(RETRIEVAL_DESC));

        panel.add(sectionSubLabelWithHelper("Search scope (documents)",
                "Checked = search only those; none checked = search the entire vector store."));
        panel.add(this.documentsCheckGroup);

        panel.add(sectionSubLabelWithHelper("Search settings",
                "Stored on this pipeline. New pipelines start from the main view's Search Settings values."));
        HorizontalLayout settingsRow = new HorizontalLayout(this.topKField, this.similarityThresholdField);
        settingsRow.setWidthFull();
        settingsRow.setFlexGrow(1, this.topKField, this.similarityThresholdField);
        panel.add(settingsRow);

        panel.add(sectionSubLabelWithHelper("Metadata filter (pipeline-specific)",
                "Optional. Applied alongside the document scope at retrieval time."));
        panel.add(buildFilterRow());

        return panel;
    }

    private static Component sectionSubLabelWithHelper(String label, String helper) {
        Div wrap = new Div();
        wrap.getStyle().set("margin", "var(--lumo-space-s) 0 var(--lumo-space-xs) 0");
        Span labelSpan = new Span(label);
        labelSpan.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-body-text-color)");
        Span helperSpan = new Span("  · " + helper);
        helperSpan.getStyle().set("font-weight", "400").set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)");
        wrap.add(labelSpan, helperSpan);
        return wrap;
    }

    private Component buildPostRetrievalPanel() {
        VerticalLayout panel = newPanel();
        panel.add(stepHeader("Post-Retrieval (Re-ranking & Truncation)", localBadge()));
        panel.add(stepDescription(POST_RETRIEVAL_DESC));
        panel.add(this.reRankCheck);
        panel.add(inlineHelper("Sort documents by similarity score (highest first)."));
        panel.add(this.topNTruncateField);
        return panel;
    }

    private Component buildGenerationPanel() {
        VerticalLayout panel = newPanel();
        panel.add(stepHeader("Generation (Augmenter + LLM call)", llmBadge()));
        panel.add(stepDescription(GENERATION_DESC));

        VerticalLayout augmenterBody = new VerticalLayout(this.documentFormatCombo);
        augmenterBody.setPadding(false);
        augmenterBody.setSpacing(false);
        this.augmenterCard = new ExpandableCard(null, "Augmenter prompt (always runs)",
                "Builds the final prompt: retrieved context + user question. Uses the Spring AI default unless "
                        + "you override.",
                this.promptTemplateField, augmenterBody);
        panel.add(this.augmenterCard);

        panel.add(new Hr());
        panel.add(this.allowEmptyContextCheck);
        panel.add(inlineHelper("Off → empty retrieval aborts with a static refusal. On → use the empty-context "
                + "prompt below."));
        this.emptyContextCard = new ExpandableCard(null, "Empty-context prompt",
                "Used only when retrieval is empty AND \"Allow empty context\" is on. Uses the Spring AI default "
                        + "unless you override.",
                this.emptyContextTemplateField, null);
        panel.add(this.emptyContextCard);

        panel.add(new Hr());
        panel.add(this.runAugmentLLMCheck);
        panel.add(inlineHelper("Off → only the prompt is built. On → the prompt is dispatched to the "
                + "default-configured ChatClient."));
        return panel;
    }

    private static VerticalLayout newPanel() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(false);
        panel.setWidthFull();
        panel.setAlignItems(FlexComponent.Alignment.STRETCH);
        return panel;
    }

    private static Component stepHeader(String title, Component badge) {
        Span titleSpan = new Span(title);
        titleSpan.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-l)");
        HorizontalLayout layout = new HorizontalLayout(titleSpan, badge);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        layout.getStyle().set("margin-bottom", "var(--lumo-space-xs)");
        return layout;
    }

    private static Span stepDescription(String text) {
        Span span = new Span(text);
        span.getStyle().set("display", "block")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin-bottom", "var(--lumo-space-s)")
                .set("line-height", "1.45");
        return span;
    }

    private static Span sectionSubLabel(String text) {
        Span span = new Span(text);
        span.getStyle().set("display", "block")
                .set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-body-text-color)")
                .set("margin", "var(--lumo-space-m) 0 var(--lumo-space-xs) 0");
        return span;
    }

    private static Span inlineHelper(String text) {
        Span span = new Span(text);
        span.getStyle().set("display", "block")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin-bottom", "var(--lumo-space-s)");
        return span;
    }

    private static class ExpandableCard extends Div {
        private final Div editor = new Div();
        private final Icon chevron = new Icon(VaadinIcon.CHEVRON_DOWN);
        private boolean expanded = false;

        ExpandableCard(Checkbox enable, String title, String description, TextArea customField, Component extraBody) {
            getStyle()
                    .set("border", "1px solid var(--lumo-contrast-10pct)")
                    .set("border-radius", "var(--lumo-border-radius-m)")
                    .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                    .set("margin-bottom", "var(--lumo-space-s)")
                    .set("background", "var(--lumo-base-color)");

            this.chevron.setSize("0.85rem");
            this.chevron.getStyle()
                    .set("cursor", "pointer")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("flex", "0 0 auto");
            this.chevron.addClickListener(e -> setExpanded(!this.expanded));

            HorizontalLayout headerRow = new HorizontalLayout();
            headerRow.setWidthFull();
            headerRow.setAlignItems(FlexComponent.Alignment.CENTER);
            if (enable != null) {
                enable.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-m)")
                        .set("flex", "1 1 auto");
                headerRow.add(enable);
            } else {
                Span titleSpan = new Span(title);
                titleSpan.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-m)")
                        .set("flex", "1 1 auto");
                headerRow.add(titleSpan);
            }
            headerRow.add(this.chevron);
            add(headerRow);

            Span desc = new Span(description);
            desc.getStyle().set("display", "block")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)")
                    .set("margin", "2px 0 0 " + (enable != null ? "calc(var(--lumo-space-l) + 4px)" : "0"))
                    .set("line-height", "1.4");
            add(desc);

            this.editor.getStyle()
                    .set("margin-top", "var(--lumo-space-m)")
                    .set("padding-top", "var(--lumo-space-s)")
                    .set("border-top", "1px solid var(--lumo-contrast-10pct)");
            if (extraBody != null) {
                extraBody.getElement().getStyle().set("margin-bottom", "var(--lumo-space-s)");
                this.editor.add(extraBody);
            }
            Span hint = new Span("Prompt template, pre-filled with the Spring AI default. "
                    + "Edit to override; leave as-is to use the default.");
            hint.getStyle().set("display", "block")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-xs)")
                    .set("margin-bottom", "var(--lumo-space-xs)");
            this.editor.add(hint, customField);
            this.editor.setVisible(false);
            add(this.editor);

            if (enable != null) {
                enable.addValueChangeListener(e -> {
                    if (Boolean.TRUE.equals(e.getValue())) setExpanded(true);
                });
            }
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
            this.editor.setVisible(expanded);
            this.chevron.getElement().setAttribute("icon", expanded ? "vaadin:chevron-up" : "vaadin:chevron-down");
        }
    }

    private Component buildFilterRow() {
        Button infoBtn = new Button(VaadinIcon.INFO_CIRCLE_O.create());
        infoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        infoBtn.getElement().setAttribute("aria-label", "Filter expression DSL help");
        Popover popover = new Popover();
        popover.addThemeVariants(PopoverVariant.ARROW);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.setTarget(infoBtn);
        popover.setWidth("380px");

        VerticalLayout pop = new VerticalLayout();
        pop.setPadding(true);
        pop.setSpacing(false);
        H6 title = new H6("Filter expression DSL");
        title.getStyle().set("margin", "0 0 var(--lumo-space-xs) 0");
        Span hint = new Span(FILTER_DSL_HINT);
        hint.getStyle().set("white-space", "pre-line").set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");
        pop.add(title, hint);
        Span examplesLabel = new Span("Click an example to insert:");
        examplesLabel.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("margin-top", "var(--lumo-space-s)");
        pop.add(examplesLabel);
        for (String example : List.of(
                "country == 'KR'",
                "published == true",
                "year >= 2020 && year < 2025",
                "category in ['legal', 'support']")) {
            Button chip = new Button(example, e -> {
                this.extraFilterField.setValue(example);
                popover.close();
            });
            chip.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            chip.getStyle().set("margin", "var(--lumo-space-xs) var(--lumo-space-xs) 0 0").set("font-family",
                    "var(--lumo-font-family-monospace)");
            pop.add(chip);
        }
        popover.add(pop);

        HorizontalLayout row = new HorizontalLayout(this.extraFilterField, infoBtn);
        row.setAlignItems(FlexComponent.Alignment.END);
        row.setWidthFull();
        row.setFlexGrow(1, this.extraFilterField);
        return row;
    }

    private static Component llmBadge() {
        Div badge = new Div(new Span("LLM"));
        badge.getStyle().set("background", "var(--lumo-primary-color-10pct)")
                .set("color", "var(--lumo-primary-text-color)").set("padding", "2px var(--lumo-space-xs)")
                .set("border-radius", "var(--lumo-border-radius-s)").set("font-size", "var(--lumo-font-size-xxs)")
                .set("font-weight", "600").set("letter-spacing", "0.05em");
        badge.getElement().setAttribute("title", "These stages call the default-configured ChatClient.");
        return badge;
    }

    private static Component localBadge() {
        Div badge = new Div(new Span("LOCAL"));
        badge.getStyle().set("background", "var(--lumo-contrast-10pct)")
                .set("color", "var(--lumo-secondary-text-color)").set("padding", "2px var(--lumo-space-xs)")
                .set("border-radius", "var(--lumo-border-radius-s)").set("font-size", "var(--lumo-font-size-xxs)")
                .set("font-weight", "600").set("letter-spacing", "0.05em");
        badge.getElement().setAttribute("title", "Local in-process implementation. No LLM call.");
        return badge;
    }

    private Button buildSaveButton() {
        Button save = new Button("Save", VaadinIcon.CHECK.create(), e -> handleSave());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        return save;
    }

    private Button buildCancelButton() {
        return new Button("Cancel", e -> close());
    }

    private void applyValues(RagPipeline pipeline) {
        List<VectorStoreDocumentInfo> docs = this.offlineEtlPipelineService.getVisibleDocumentList();
        this.documentsCheckGroup.setItems(docs);

        VectorStoreService.SearchRequestOption opt = this.vectorStoreService.getSearchRequestOption();
        RagPipeline.RetrievalConfig retrieval = pipeline == null ? null : pipeline.retrieval();
        this.topKField.setValue(retrieval != null && retrieval.topK() != null ? retrieval.topK() : opt.topK());
        this.similarityThresholdField.setValue(retrieval != null && retrieval.similarityThreshold() != null
                ? retrieval.similarityThreshold() : opt.similarityThreshold());

        if (pipeline == null) {
            this.nameField.clear();
            this.descField.clear();
            this.documentsCheckGroup.clear();
            this.rewriteCheck.setValue(false);
            this.rewriteTargetField.clear();
            this.rewriteTemplateField.setValue(DEFAULT_REWRITE_TEMPLATE);
            this.compressionCheck.setValue(false);
            this.compressionTemplateField.setValue(DEFAULT_COMPRESSION_TEMPLATE);
            this.translationCheck.setValue(false);
            this.translationLangCombo.setValue("english");
            this.translationTemplateField.setValue(DEFAULT_TRANSLATION_TEMPLATE);
            this.multiQueryCheck.setValue(false);
            this.multiQueryCountField.setValue(3);
            this.multiQueryIncludeOriginalCheck.setValue(true);
            this.multiQueryTemplateField.setValue(DEFAULT_MULTI_QUERY_TEMPLATE);
            this.extraFilterField.clear();
            this.reRankCheck.setValue(true);
            this.topNTruncateField.clear();
            this.allowEmptyContextCheck.setValue(false);
            this.promptTemplateField.setValue(DEFAULT_AUGMENTER_TEMPLATE);
            this.documentFormatCombo.setValue(RagPipeline.DocumentFormat.TEXT);
            this.emptyContextTemplateField.setValue(DEFAULT_EMPTY_CONTEXT_TEMPLATE);
            this.emptyContextTemplateField.setEnabled(false);
            this.runAugmentLLMCheck.setValue(false);
            collapseAllCards();
            return;
        }

        this.nameField.setValue(pipeline.name());
        this.descField.setValue(Optional.ofNullable(pipeline.description()).orElse(""));
        Set<VectorStoreDocumentInfo> selected = docs.stream()
                .filter(document -> pipeline.docInfoIds().contains(document.docInfoId())).collect(Collectors.toSet());
        this.documentsCheckGroup.setValue(selected);
        RagPipeline.PreRetrievalConfig pre = pipeline.preRetrieval();
        this.rewriteCheck.setValue(pre.rewrite());
        this.rewriteTargetField.setValue(Optional.ofNullable(pre.rewriteTargetSearchSystem()).orElse(""));
        this.rewriteTemplateField.setValue(
                Optional.ofNullable(pre.rewritePromptTemplate()).orElse(DEFAULT_REWRITE_TEMPLATE));
        this.compressionCheck.setValue(pre.compression());
        this.compressionTemplateField.setValue(
                Optional.ofNullable(pre.compressionPromptTemplate()).orElse(DEFAULT_COMPRESSION_TEMPLATE));
        this.translationCheck.setValue(pre.translation());
        this.translationLangCombo.setValue(Optional.ofNullable(pre.translationTargetLanguage()).orElse("english"));
        this.translationTemplateField.setValue(
                Optional.ofNullable(pre.translationPromptTemplate()).orElse(DEFAULT_TRANSLATION_TEMPLATE));
        this.multiQueryCheck.setValue(pre.multiQuery());
        this.multiQueryCountField.setValue(pre.multiQueryCount());
        this.multiQueryIncludeOriginalCheck.setValue(pre.multiQueryIncludeOriginal());
        this.multiQueryTemplateField.setValue(
                Optional.ofNullable(pre.multiQueryPromptTemplate()).orElse(DEFAULT_MULTI_QUERY_TEMPLATE));
        RagPipeline.RetrievalConfig ret = pipeline.retrieval();
        this.extraFilterField.setValue(Optional.ofNullable(ret.extraFilterExpression()).orElse(""));
        RagPipeline.PostRetrievalConfig post = pipeline.postRetrieval();
        this.reRankCheck.setValue(post.reRankByScore());
        if (post.topNTruncate() != null) this.topNTruncateField.setValue(post.topNTruncate()); else
            this.topNTruncateField.clear();
        RagPipeline.GenerationConfig gen = pipeline.generation();
        this.allowEmptyContextCheck.setValue(gen.allowEmptyContext());
        this.documentFormatCombo.setValue(gen.documentFormat());
        this.promptTemplateField.setValue(
                Optional.ofNullable(gen.promptTemplate()).orElse(DEFAULT_AUGMENTER_TEMPLATE));
        this.emptyContextTemplateField.setValue(
                Optional.ofNullable(gen.emptyContextPromptTemplate()).orElse(DEFAULT_EMPTY_CONTEXT_TEMPLATE));
        this.emptyContextTemplateField.setEnabled(gen.allowEmptyContext());
        this.runAugmentLLMCheck.setValue(gen.runAugmentLLM());

        this.rewriteCard.setExpanded(pre.rewrite() || pre.rewritePromptTemplate() != null);
        this.compressionCard.setExpanded(pre.compression() || pre.compressionPromptTemplate() != null);
        this.translationCard.setExpanded(pre.translation() || pre.translationPromptTemplate() != null);
        this.multiQueryCard.setExpanded(pre.multiQuery() || pre.multiQueryPromptTemplate() != null);
        this.augmenterCard.setExpanded(gen.promptTemplate() != null);
        this.emptyContextCard.setExpanded(gen.allowEmptyContext() || gen.emptyContextPromptTemplate() != null);
    }

    private void collapseAllCards() {
        this.rewriteCard.setExpanded(false);
        this.compressionCard.setExpanded(false);
        this.translationCard.setExpanded(false);
        this.multiQueryCard.setExpanded(false);
        this.augmenterCard.setExpanded(false);
        this.emptyContextCard.setExpanded(false);
    }

    private void handleSave() {
        String name = Optional.ofNullable(this.nameField.getValue()).map(String::trim).orElse("");
        if (name.isEmpty()) {
            VaadinUtils.showErrorNotification("Name is required");
            this.nameField.focus();
            return;
        }
        try {
            List<String> docInfoIds = this.documentsCheckGroup.getValue().stream()
                    .map(VectorStoreDocumentInfo::docInfoId).toList();
            RagPipeline.PreRetrievalConfig pre = new RagPipeline.PreRetrievalConfig(
                    this.rewriteCheck.getValue(),
                    overrideOrNull(this.rewriteTemplateField.getValue(), DEFAULT_REWRITE_TEMPLATE),
                    this.compressionCheck.getValue(),
                    overrideOrNull(this.compressionTemplateField.getValue(), DEFAULT_COMPRESSION_TEMPLATE),
                    this.translationCheck.getValue(), this.translationLangCombo.getValue(),
                    overrideOrNull(this.translationTemplateField.getValue(), DEFAULT_TRANSLATION_TEMPLATE),
                    this.multiQueryCheck.getValue(),
                    Optional.ofNullable(this.multiQueryCountField.getValue()).orElse(3),
                    this.multiQueryIncludeOriginalCheck.getValue(),
                    overrideOrNull(this.multiQueryTemplateField.getValue(), DEFAULT_MULTI_QUERY_TEMPLATE),
                    overrideOrNull(this.rewriteTargetField.getValue(), DEFAULT_REWRITE_TARGET));
            RagPipeline.RetrievalConfig ret = new RagPipeline.RetrievalConfig(
                    blankToNull(this.extraFilterField.getValue()),
                    this.topKField.getValue(), this.similarityThresholdField.getValue());
            RagPipeline.PostRetrievalConfig post = new RagPipeline.PostRetrievalConfig(this.reRankCheck.getValue(),
                    this.topNTruncateField.getValue());
            RagPipeline.GenerationConfig gen = new RagPipeline.GenerationConfig(
                    this.allowEmptyContextCheck.getValue(),
                    overrideOrNull(this.promptTemplateField.getValue(), DEFAULT_AUGMENTER_TEMPLATE),
                    overrideOrNull(this.emptyContextTemplateField.getValue(), DEFAULT_EMPTY_CONTEXT_TEMPLATE),
                    this.runAugmentLLMCheck.getValue(), this.documentFormatCombo.getValue());
            String description = blankToNull(this.descField.getValue());

            RagPipeline saved;
            if (this.editingPipeline == null) {
                saved = this.ragPipelineService.create(name, description, docInfoIds, pre, ret, post, gen);
            } else {
                RagPipeline updated = new RagPipeline(this.editingPipeline.id(), name, description, docInfoIds, pre,
                        ret, post, gen, this.editingPipeline.createdAt(), System.currentTimeMillis());
                saved = this.ragPipelineService.update(updated);
            }
            close();
            if (this.onSaved != null) this.onSaved.accept(saved);
        } catch (Exception e) {
            VaadinUtils.showErrorNotification("Failed to save pipeline: " + e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return Objects.nonNull(value) && !value.isBlank() ? value : null;
    }

    private static String formatRelative(long epochMs) {
        long now = System.currentTimeMillis();
        Duration age = Duration.ofMillis(Math.max(0L, now - epochMs));
        if (age.toMinutes() < 1) return "just now";
        if (age.toHours() < 1) return age.toMinutes() + "m ago";
        if (age.toDays() < 1) return age.toHours() + "h ago";
        return age.toDays() + "d ago";
    }

    private static String overrideOrNull(String value, String defaultTemplate) {
        if (value == null || value.isBlank()) return null;
        if (Objects.equals(value, defaultTemplate)) return null;
        return value;
    }
}
