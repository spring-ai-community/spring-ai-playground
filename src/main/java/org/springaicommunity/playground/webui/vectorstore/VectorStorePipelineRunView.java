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
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.TraceEvent;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class VectorStorePipelineRunView extends VerticalLayout {

    private final RagPipelineExecutor ragPipelineExecutor;
    private final Div summary;
    private final Div placeholder;
    private final Div body;
    private final Div configCard = new Div();
    private final Div messagesArea = new Div();
    private final TextField queryField = new TextField();
    private final Button runButton = new Button("Run", VaadinIcon.PLAY.create());
    private final Button clearButton = new Button(VaadinIcon.ERASER.create());
    private final List<TraceEvent> currentRunEvents = new CopyOnWriteArrayList<>();
    private final List<Message> conversationHistory = new ArrayList<>();

    private RagPipeline currentPipeline;
    private Consumer<RagPipeline> onEditRequest;

    public VectorStorePipelineRunView(RagPipelineExecutor ragPipelineExecutor) {
        this.ragPipelineExecutor = ragPipelineExecutor;
        addClassName("vstore-pipeline-run");
        setSizeFull();
        setSpacing(false);
        setPadding(false);
        setMargin(false);

        this.summary = new Div();
        this.summary.addClassName("vstore-pipeline-summary");
        this.summary.setWidthFull();
        this.summary.setVisible(false);

        this.placeholder = buildPlaceholder();

        this.body = new Div();
        this.body.addClassName("vstore-pipeline-body");
        this.body.setWidthFull();
        this.body.getStyle().set("flex", "1 1 auto").set("min-height", "0");
        this.body.add(this.placeholder);

        this.queryField.setPlaceholder("Ask a test question…");
        this.queryField.setWidthFull();
        this.queryField.addClassName("vstore-pipeline-query");
        this.queryField.setEnabled(false);
        this.queryField.addKeyDownListener(Key.ENTER, e -> runPipeline());

        this.runButton.setIconAfterText(false);
        this.runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        this.runButton.setEnabled(false);
        this.runButton.addClickListener(e -> runPipeline());

        this.clearButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        this.clearButton.setTooltipText("Clear test conversation");
        this.clearButton.setEnabled(false);
        this.clearButton.addClickListener(e -> clearConversation());

        this.messagesArea.addClassName("vstore-pipeline-messages");

        add(this.summary, this.body);
    }

    public void setOnEditRequest(Consumer<RagPipeline> onEditRequest) {
        this.onEditRequest = onEditRequest;
    }

    public void showPlaceholder() {
        this.currentPipeline = null;
        this.queryField.setEnabled(false);
        this.runButton.setEnabled(false);
        this.clearButton.setEnabled(false);
        this.summary.setVisible(false);
        this.conversationHistory.clear();
        this.messagesArea.removeAll();
        this.body.removeAll();
        this.body.add(this.placeholder);
    }

    public void showPipeline(RagPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        boolean samePipeline = this.currentPipeline != null && Objects.equals(this.currentPipeline.id(), pipeline.id());
        this.currentPipeline = pipeline;
        this.summary.removeAll();
        this.summary.setVisible(true);
        this.summary.add(buildSummaryContent(pipeline));

        this.configCard.removeAll();
        this.configCard.add(buildConfigCard(pipeline));

        this.queryField.setEnabled(true);
        this.runButton.setEnabled(true);
        this.clearButton.setEnabled(true);
        if (!samePipeline) {
            this.conversationHistory.clear();
            this.messagesArea.removeAll();
        }

        this.body.removeAll();
        this.body.add(buildChatPanel());
    }

    private Div buildPlaceholder() {
        Div wrapper = new Div();
        wrapper.addClassName("vstore-pipeline-placeholder");
        H4 title = new H4("No pipeline selected");
        Div hint = new Div();
        hint.setText("Use the [+ New RAG Pipeline] header button to create a pipeline, "
                + "then pick it from the left Pipelines section.");
        hint.addClassName("vstore-pipeline-placeholder-hint");
        wrapper.add(title, hint);
        return wrapper;
    }

    private Div buildSummaryContent(RagPipeline pipeline) {
        Div container = new Div();
        container.addClassName("vstore-pipeline-summary-row");

        Span name = new Span(pipeline.name());
        name.addClassName("vstore-pipeline-summary-name");
        container.add(name);

        if (pipeline.description() != null && !pipeline.description().isBlank()) {
            Span desc = new Span(pipeline.description());
            desc.addClassName("vstore-pipeline-summary-desc");
            container.add(desc);
        }

        String docsLabel = pipeline.docInfoIds().isEmpty() ? "all docs"
                : pipeline.docInfoIds().size() + " docs";
        container.add(buildChip("docs: " + docsLabel));

        String stagesLabel = String.join(" → ", activeStages(pipeline));
        if (!stagesLabel.isBlank()) container.add(buildChip("stages: " + stagesLabel));

        Button editButton = new Button("Edit", VaadinIcon.AUTOMATION.create());
        editButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        editButton.getStyle().set("margin-left", "auto");
        editButton.addClickListener(e -> {
            if (this.onEditRequest != null && this.currentPipeline != null)
                this.onEditRequest.accept(this.currentPipeline);
        });
        container.add(editButton);
        return container;
    }

    private static Span buildChip(String text) {
        Span chip = new Span(text);
        chip.addClassName("vstore-pipeline-summary-chip");
        return chip;
    }

    private static List<String> activeStages(RagPipeline pipeline) {
        List<String> labels = new ArrayList<>(pipeline.stageLabels());
        if (pipeline.generation().runAugmentLLM()) labels.add("LLM");
        return labels;
    }

    private HorizontalLayout buildChatPanel() {
        HorizontalLayout panel = new HorizontalLayout();
        panel.setSizeFull();
        panel.setSpacing(false);
        panel.setPadding(false);
        panel.addClassName("vstore-pipeline-chat-panel");

        this.configCard.addClassName("vstore-pipeline-config-card");
        panel.add(this.configCard);

        HorizontalLayout inputRow = new HorizontalLayout(this.queryField, this.runButton, this.clearButton);
        inputRow.setWidthFull();
        inputRow.setFlexGrow(1, this.queryField);
        inputRow.setAlignItems(FlexComponent.Alignment.CENTER);
        inputRow.addClassName("vstore-pipeline-query-row");

        VerticalLayout chatColumn = new VerticalLayout(this.messagesArea, inputRow);
        chatColumn.setPadding(false);
        chatColumn.setSpacing(false);
        chatColumn.setSizeFull();
        chatColumn.setFlexGrow(1, this.messagesArea);
        chatColumn.addClassName("vstore-pipeline-chat-column");

        panel.add(chatColumn);
        panel.setFlexGrow(1, chatColumn);
        return panel;
    }

    private Div buildConfigCard(RagPipeline pipeline) {
        Div card = new Div();
        card.addClassName("vstore-pipeline-config-content");

        card.add(configSection("Pre-Retrieval", preRetrievalSummary(pipeline.preRetrieval())));
        card.add(configSection("Retrieval", retrievalSummary(pipeline)));
        card.add(configSection("Post-Retrieval", postRetrievalSummary(pipeline.postRetrieval())));
        card.add(configSection("Generation", generationSummary(pipeline.generation())));
        return card;
    }

    private Div configSection(String title, List<String> lines) {
        Div section = new Div();
        section.addClassName("vstore-pipeline-config-section");
        Span heading = new Span(title);
        heading.addClassName("vstore-pipeline-config-heading");
        section.add(heading);
        for (String line : lines) {
            Div item = new Div();
            item.setText(line);
            item.addClassName("vstore-pipeline-config-line");
            section.add(item);
        }
        return section;
    }

    private static List<String> preRetrievalSummary(RagPipeline.PreRetrievalConfig pre) {
        List<String> lines = new ArrayList<>();
        if (pre.rewrite()) lines.add("Rewrite: on" + (pre.rewriteTargetSearchSystem() != null
                ? " → " + pre.rewriteTargetSearchSystem() : ""));
        if (pre.compression()) lines.add("Compression: on");
        if (pre.translation()) lines.add("Translation → " + pre.translationTargetLanguage());
        if (pre.multiQuery()) lines.add("Multi-Query ×" + pre.multiQueryCount()
                + (pre.multiQueryIncludeOriginal() ? " (+original)" : ""));
        if (lines.isEmpty()) lines.add("(none, raw query)");
        return lines;
    }

    private List<String> retrievalSummary(RagPipeline pipeline) {
        List<String> lines = new ArrayList<>();
        lines.add(pipeline.docInfoIds().isEmpty() ? "Scope: all documents"
                : "Scope: " + pipeline.docInfoIds().size() + " selected docs");
        RagPipeline.RetrievalConfig retrieval = pipeline.retrieval();
        if (retrieval.topK() != null) lines.add("topK: " + retrieval.topK());
        if (retrieval.similarityThreshold() != null) lines.add("threshold: " + retrieval.similarityThreshold());
        String filter = retrieval.extraFilterExpression();
        if (filter != null && !filter.isBlank()) lines.add("Filter: " + filter);
        return lines;
    }

    private static List<String> postRetrievalSummary(RagPipeline.PostRetrievalConfig post) {
        List<String> lines = new ArrayList<>();
        if (post.reRankByScore()) lines.add("Re-rank by score");
        if (post.topNTruncate() != null && post.topNTruncate() > 0) lines.add("Top-N: " + post.topNTruncate());
        if (lines.isEmpty()) lines.add("(none)");
        return lines;
    }

    private static List<String> generationSummary(RagPipeline.GenerationConfig gen) {
        List<String> lines = new ArrayList<>();
        lines.add("Empty context: " + (gen.allowEmptyContext() ? "allowed" : "refused"));
        if (RagPipeline.DocumentFormat.TEXT_WITH_SOURCE.equals(gen.documentFormat()))
            lines.add("Context: text + source label");
        lines.add(gen.runAugmentLLM() ? "Run LLM: on" : "Run LLM: off (prompt only)");
        return lines;
    }

    private void clearConversation() {
        this.conversationHistory.clear();
        this.messagesArea.removeAll();
    }

    private void runPipeline() {
        if (this.currentPipeline == null) return;
        String query = this.queryField.getValue() == null ? "" : this.queryField.getValue().trim();
        if (query.isEmpty()) {
            VaadinUtils.showErrorNotification("Enter a query first");
            return;
        }
        this.runButton.setEnabled(false);
        this.queryField.setEnabled(false);
        this.queryField.clear();
        this.currentRunEvents.clear();

        appendUserBubble(query);
        Div pending = appendPendingBubble();
        List<Message> historySnapshot = List.copyOf(this.conversationHistory);

        UI ui = UI.getCurrent();
        new Thread(() -> {
            try {
                RagPipelineExecutor.RunResult result = this.ragPipelineExecutor.execute(
                        this.currentPipeline, query, historySnapshot, this.currentRunEvents::add);
                ui.access(() -> {
                    this.messagesArea.remove(pending);
                    appendAssistantBubble(result);
                    this.conversationHistory.add(new UserMessage(query));
                    if (result.llmAnswer() != null)
                        this.conversationHistory.add(new AssistantMessage(result.llmAnswer()));
                });
            } catch (Exception ex) {
                ui.access(() -> {
                    this.messagesArea.remove(pending);
                    appendErrorBubble(ex);
                });
            } finally {
                ui.access(() -> {
                    this.runButton.setEnabled(true);
                    this.queryField.setEnabled(true);
                    this.queryField.focus();
                });
            }
        }, "rag-pipeline-run").start();
    }

    private void appendUserBubble(String query) {
        Div bubble = new Div();
        bubble.addClassName("vstore-chat-user");
        bubble.setText(query);
        this.messagesArea.add(bubble);
        scrollToBottom();
    }

    private Div appendPendingBubble() {
        Div bubble = new Div();
        bubble.addClassNames("vstore-chat-assistant", "vstore-chat-pending");
        bubble.setText("Running pipeline…");
        this.messagesArea.add(bubble);
        scrollToBottom();
        return bubble;
    }

    private void appendAssistantBubble(RagPipelineExecutor.RunResult result) {
        Div bubble = new Div();
        bubble.addClassName("vstore-chat-assistant");

        Div headline = new Div();
        headline.addClassName("vstore-chat-headline");
        headline.setText(String.format("Retrieved %d docs → %d final · %d ms",
                result.joinedDocs().size(), result.finalDocs().size(), result.totalMs()));
        bubble.add(headline);

        if (result.llmAnswer() != null) {
            Div answer = new Div();
            answer.addClassName("vstore-chat-answer");
            answer.setText(result.llmAnswer());
            bubble.add(answer);
        }

        bubble.add(collapsible("Documents (" + result.finalDocs().size() + ")", buildDocsList(result.finalDocs()),
                result.llmAnswer() == null && !result.finalDocs().isEmpty()));
        bubble.add(collapsible("Final prompt (" + promptLength(result) + " chars)",
                monospacePre(result.finalPrompt() == null ? "(empty)" : result.finalPrompt()),
                result.llmAnswer() == null && result.finalDocs().isEmpty()));
        bubble.add(collapsible("Trace (" + this.currentRunEvents.size() + " events)",
                buildTraceList(List.copyOf(this.currentRunEvents)), false));

        this.messagesArea.add(bubble);
        scrollToBottom();
    }

    private static int promptLength(RagPipelineExecutor.RunResult result) {
        return result.finalPrompt() == null ? 0 : result.finalPrompt().length();
    }

    private void appendErrorBubble(Exception ex) {
        Div bubble = new Div();
        bubble.addClassNames("vstore-chat-assistant", "vstore-chat-error");
        bubble.setText("Run failed: " + ex.getMessage());
        this.messagesArea.add(bubble);
        scrollToBottom();
    }

    private Details collapsible(String summaryText, Component content, boolean opened) {
        Details details = new Details(summaryText, content);
        details.setOpened(opened);
        details.addClassName("vstore-chat-details");
        return details;
    }

    private Div buildDocsList(List<Document> docs) {
        Div docsList = new Div();
        docsList.addClassName("vstore-chat-docs");
        for (Document document : docs) {
            Div row = new Div();
            row.addClassName("vstore-chat-doc-row");
            Span score = new Span(String.format("%.3f", document.getScore() == null ? 0.0 : document.getScore()));
            score.addClassName("vstore-chat-doc-score");
            Span text = new Span(document.getText() == null ? "" : document.getText().substring(0,
                    Math.min(200, document.getText().length())) + (document.getText() != null && document.getText().length() > 200 ? "…" : ""));
            row.add(score, text);
            docsList.add(row);
        }
        if (docs.isEmpty()) {
            Span none = new Span("(no documents retrieved)");
            none.addClassName("vstore-chat-doc-empty");
            docsList.add(none);
        }
        return docsList;
    }

    private static Pre monospacePre(String text) {
        Pre pre = new Pre(text);
        pre.addClassName("vstore-chat-pre");
        return pre;
    }

    private Div buildTraceList(List<TraceEvent> events) {
        Div box = new Div();
        box.addClassName("vstore-chat-trace");
        for (TraceEvent event : events) {
            Div line = new Div();
            String levelTag = event.level() == TraceEvent.Level.WARN ? "[WARN] "
                    : (event.level() == TraceEvent.Level.ERROR ? "[ERR] " : "");
            line.setText(String.format("%s%s · %s", levelTag, event.stage(), event.message()));
            if (event.level() != TraceEvent.Level.INFO) line.addClassName("vstore-chat-trace-warn");
            box.add(line);
        }
        return box;
    }

    private void scrollToBottom() {
        this.messagesArea.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }
}
