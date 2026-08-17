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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof that every modular RAG option the spec-coverage audit maps to a config field is actually
 * settable through the wizard UI and survives a save -> reopen-for-edit round trip. Fields live on
 * four tabs; panels are attached lazily, so each tab is selected before its fields are touched.
 */
@SpringBootTest
class NewRagPipelineDialogRoundTripTest extends SpringBrowserlessTest {

    private static final String REWRITE_TPL = "Rewrite for {target}: {query}";
    private static final String COMPRESSION_TPL = "Fold {history} into {query}";
    private static final String TRANSLATION_TPL = "Translate to {targetLanguage}: {query}";
    private static final String MULTI_QUERY_TPL = "Give {number} variants of {query}";
    private static final String AUGMENTER_TPL = "Context {context} Question {query}";
    private static final String EMPTY_CONTEXT_TPL = "Politely refuse.";

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private RagPipelineService ragPipelineService;

    private RagPipeline saved;

    @AfterEach
    void deletePipeline() {
        if (this.saved != null) this.ragPipelineService.deleteById(this.saved.id());
    }

    @Test
    void everyWizardOptionSurvivesSaveAndRepopulatesOnEdit() {
        VectorStoreView view = navigate(VectorStoreView.class);
        test($(Button.class, view)
                .withCondition(button -> "New RAG Pipeline".equals(button.getTooltip().getText()))
                .first()).click();
        NewRagPipelineDialog dialog = $(NewRagPipelineDialog.class).first();

        textField(dialog, "Name").setValue("wizard-roundtrip");

        Tabs tabs = $(Tabs.class, dialog).first();
        tabs.setSelectedIndex(0);
        checkbox(dialog, "Rewrite").setValue(true);
        textField(dialog, "Target search system").setValue("web search");
        templateArea(dialog, "{target} {query}").setValue(REWRITE_TPL);
        checkbox(dialog, "Compression").setValue(true);
        templateArea(dialog, "{history} {query}").setValue(COMPRESSION_TPL);
        checkbox(dialog, "Translation").setValue(true);
        this.<String>comboBox(dialog, "Target language").setValue("korean");
        templateArea(dialog, "{targetLanguage} {query}").setValue(TRANSLATION_TPL);
        checkbox(dialog, "Multi-Query expansion").setValue(true);
        integerField(dialog, "N").setValue(4);
        checkbox(dialog, "include original query").setValue(false);
        templateArea(dialog, "{number} {query}").setValue(MULTI_QUERY_TPL);

        tabs.setSelectedIndex(1);
        integerField(dialog, "topK").setValue(7);
        $(NumberField.class, dialog).withCondition(f -> "similarityThreshold".equals(f.getLabel())).first()
                .setValue(0.25);
        textField(dialog, "Filter expression (metadata)").setValue("country == 'KR'");

        tabs.setSelectedIndex(2);
        checkbox(dialog, "Re-rank by score (descending)").setValue(true);
        integerField(dialog, "Top-N truncate").setValue(5);

        tabs.setSelectedIndex(3);
        expandCollapsedCards(dialog);
        this.<RagPipeline.DocumentFormat>comboBox(dialog, "Context format")
                .setValue(RagPipeline.DocumentFormat.TEXT_WITH_SOURCE);
        templateArea(dialog, "{query} {context}").setValue(AUGMENTER_TPL);
        checkbox(dialog, "Allow empty context").setValue(true);
        emptyContextArea(dialog).setValue(EMPTY_CONTEXT_TPL);
        checkbox(dialog, "Run LLM after augment (call ChatModel for the answer)").setValue(true);

        test($(Button.class, dialog).withCondition(b -> "Save".equals(b.getText())).first()).click();
        roundTrip();

        this.saved = this.ragPipelineService.list().stream()
                .filter(pipeline -> "wizard-roundtrip".equals(pipeline.name())).findFirst().orElseThrow();
        RagPipeline.PreRetrievalConfig pre = this.saved.preRetrieval();
        assertThat(pre.rewrite()).isTrue();
        assertThat(pre.rewriteTargetSearchSystem()).isEqualTo("web search");
        assertThat(pre.rewritePromptTemplate()).isEqualTo(REWRITE_TPL);
        assertThat(pre.compression()).isTrue();
        assertThat(pre.compressionPromptTemplate()).isEqualTo(COMPRESSION_TPL);
        assertThat(pre.translation()).isTrue();
        assertThat(pre.translationTargetLanguage()).isEqualTo("korean");
        assertThat(pre.translationPromptTemplate()).isEqualTo(TRANSLATION_TPL);
        assertThat(pre.multiQuery()).isTrue();
        assertThat(pre.multiQueryCount()).isEqualTo(4);
        assertThat(pre.multiQueryIncludeOriginal()).isFalse();
        assertThat(pre.multiQueryPromptTemplate()).isEqualTo(MULTI_QUERY_TPL);
        RagPipeline.RetrievalConfig retrieval = this.saved.retrieval();
        assertThat(retrieval.topK()).isEqualTo(7);
        assertThat(retrieval.similarityThreshold()).isEqualTo(0.25);
        assertThat(retrieval.extraFilterExpression()).isEqualTo("country == 'KR'");
        RagPipeline.PostRetrievalConfig post = this.saved.postRetrieval();
        assertThat(post.reRankByScore()).isTrue();
        assertThat(post.topNTruncate()).isEqualTo(5);
        RagPipeline.GenerationConfig generation = this.saved.generation();
        assertThat(generation.documentFormat()).isEqualTo(RagPipeline.DocumentFormat.TEXT_WITH_SOURCE);
        assertThat(generation.promptTemplate()).isEqualTo(AUGMENTER_TPL);
        assertThat(generation.allowEmptyContext()).isTrue();
        assertThat(generation.emptyContextPromptTemplate()).isEqualTo(EMPTY_CONTEXT_TPL);
        assertThat(generation.runAugmentLLM()).isTrue();

        dialog.openForEdit(this.saved);
        roundTrip();
        Tabs editTabs = $(Tabs.class, dialog).first();
        editTabs.setSelectedIndex(0);
        assertThat(checkbox(dialog, "Rewrite").getValue()).isTrue();
        assertThat(textField(dialog, "Target search system").getValue()).isEqualTo("web search");
        assertThat(this.<String>comboBox(dialog, "Target language").getValue()).isEqualTo("korean");
        assertThat(integerField(dialog, "N").getValue()).isEqualTo(4);
        editTabs.setSelectedIndex(1);
        assertThat(integerField(dialog, "topK").getValue()).isEqualTo(7);
        assertThat(textField(dialog, "Filter expression (metadata)").getValue()).isEqualTo("country == 'KR'");
        editTabs.setSelectedIndex(3);
        assertThat(this.<RagPipeline.DocumentFormat>comboBox(dialog, "Context format").getValue())
                .isEqualTo(RagPipeline.DocumentFormat.TEXT_WITH_SOURCE);
        assertThat(checkbox(dialog, "Run LLM after augment (call ChatModel for the answer)").getValue()).isTrue();
    }

    private void expandCollapsedCards(NewRagPipelineDialog dialog) {
        $(Icon.class, dialog)
                .withCondition(icon -> "vaadin:chevron-down".equals(icon.getElement().getAttribute("icon")))
                .all().forEach(icon -> ComponentUtil.fireEvent(icon, new ClickEvent<>(icon)));
        roundTrip();
    }

    private TextField textField(NewRagPipelineDialog dialog, String label) {
        return $(TextField.class, dialog).withCondition(field -> label.equals(field.getLabel())).first();
    }

    private IntegerField integerField(NewRagPipelineDialog dialog, String label) {
        return $(IntegerField.class, dialog).withCondition(field -> label.equals(field.getLabel())).first();
    }

    private Checkbox checkbox(NewRagPipelineDialog dialog, String label) {
        return $(Checkbox.class, dialog).withCondition(box -> label.equals(box.getLabel())).first();
    }

    @SuppressWarnings("unchecked")
    private <T> ComboBox<T> comboBox(NewRagPipelineDialog dialog, String label) {
        return $(ComboBox.class, dialog).withCondition(combo -> label.equals(combo.getLabel())).first();
    }

    private TextArea templateArea(NewRagPipelineDialog dialog, String helperMarker) {
        return $(TextArea.class, dialog)
                .withCondition(area -> area.getHelperText() != null && area.getHelperText().contains(helperMarker))
                .first();
    }

    private TextArea emptyContextArea(NewRagPipelineDialog dialog) {
        return $(TextArea.class, dialog)
                .withCondition(area -> area.getHelperText() != null
                        && area.getHelperText().startsWith("Used only when context is empty"))
                .first();
    }
}
