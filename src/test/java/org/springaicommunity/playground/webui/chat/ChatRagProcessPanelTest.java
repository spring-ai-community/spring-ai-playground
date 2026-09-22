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
package org.springaicommunity.playground.webui.chat;

import tools.jackson.databind.node.StringNode;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ChatToolPreferences;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ChatRagProcessPanelTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private RagPipelineService ragPipelineService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    private RagPipeline pipeline;
    private RagPipeline stagedPipeline;

    @BeforeEach
    void stubStreamAndSearch() {
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("grounded reply"))))));
        lenient().when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = ((Prompt) invocation.getArgument(0)).getContents();
            String answer = prompt.contains("Query variants:") ? "variant one\nvariant two"
                    : (prompt.contains("Rewritten query:") ? "rewritten chat query" : "stage answer");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        lenient().when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("indexed chunk", Map.of("source", "rag-panel-test"))));
        this.pipeline = this.ragPipelineService.create("rag-panel-pipeline", null, List.of(), null, null, null, null);
    }

    @AfterEach
    void deletePipelines() {
        if (this.pipeline != null) this.ragPipelineService.deleteById(this.pipeline.id());
        if (this.stagedPipeline != null) this.ragPipelineService.deleteById(this.stagedPipeline.id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectingPipelineRendersRagProcessPanelDuringStream() throws InterruptedException {
        ChatView view = navigate(ChatView.class);
        ComboBox<ChatService.RagSource> sources = ragSourceCombo(view);
        sources.setValue(sources.getListDataView().getItems()
                .filter(source -> this.pipeline.id().equals(source.sourceId())).findFirst().orElseThrow());

        sendPrompt(view, "what does the document say?");

        assertThat(awaitMarkdownContaining(view, "grounded reply")).isTrue();
        assertThat($(Markdown.class, view).all().stream()
                .map(Markdown::getContent)
                .anyMatch(content -> content != null && content.contains("Running RAG pipeline"))).isTrue();
        List<Details> ragPanels = $(Details.class, view).all().stream()
                .filter(details -> details.getSummary() instanceof Span summary
                        && summary.getText().startsWith("RAG DOCUMENTS")).toList();
        assertThat(ragPanels).as("UI-03 the RAG panel is present").hasSize(1);
        assertThat(ragPanels.getFirst().isOpened())
                .as("UI-03 the finished RAG panel is collapsed").isFalse();
    }

    @Test
    void stagedPipelineRunsItsPreRetrievalStagesThroughTheChatPath() throws InterruptedException {
        this.stagedPipeline = this.ragPipelineService.create("staged-chat-pipeline", null, List.of(),
                new RagPipeline.PreRetrievalConfig(true, null, false, null, false, "english", null,
                        true, 2, false, null),
                null, null, null);

        ChatView view = navigate(ChatView.class);
        ComboBox<ChatService.RagSource> sources = ragSourceCombo(view);
        sources.setValue(sources.getListDataView().getItems()
                .filter(source -> this.stagedPipeline.id().equals(source.sourceId())).findFirst().orElseThrow());

        sendPrompt(view, "who maintains it?");

        assertThat(awaitMarkdownContaining(view, "grounded reply")).isTrue();
        String ragPanel = $(Markdown.class, view).all().stream().map(Markdown::getContent)
                .filter(content -> content != null && content.contains("Running RAG pipeline"))
                .findFirst().orElseThrow();
        assertThat(ragPanel).contains("Stages: Rewrite");
        assertThat(ragPanel).contains("`rewrite` done");
        assertThat(ragPanel).contains("`multiQuery` done");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        assertThat(captor.getAllValues()).extracting(SearchRequest::getQuery)
                .containsExactlyInAnyOrder("variant one", "variant two");
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedPipelineSelectionIsRestoredWhenReturningToConversation() {
        long now = System.currentTimeMillis();
        ChatToolPreferences prefs = new ChatToolPreferences(false, Set.of(), this.pipeline.id(), Map.of(),
                null, false);
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory("rag-restore-conv", "Rag restore", now, now,
                "sys", (DefaultChatOptions) ChatOptions.builder().build(),
                () -> List.of(new UserMessage("earlier question"), new AssistantMessage("earlier answer")))
                .withToolPreferences(prefs));

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", "rag-restore-conv"));
        roundTrip();

        ComboBox<ChatService.RagSource> sources = ragSourceCombo((ChatView) getCurrentView());
        assertThat(sources.getValue()).isNotNull();
        assertThat(sources.getValue().sourceId()).isEqualTo(this.pipeline.id());
    }

    @SuppressWarnings("unchecked")
    private ComboBox<ChatService.RagSource> ragSourceCombo(ChatView view) {
        return $(ComboBox.class, view)
                .withCondition(combo -> ((ComboBox<Object>) combo).getListDataView()
                        .getItems().anyMatch(ChatService.RagSource.class::isInstance))
                .single();
    }

    private void sendPrompt(ChatView view, String text) {
        TextArea prompt = $(TextArea.class, view)
                .withCondition(area -> "Ask Spring AI Playground".equals(area.getPlaceholder()))
                .single();
        test(prompt).setValue(text);
        test($(Button.class, view)
                .withCondition(button -> "Submit".equals(button.getTooltip().getText()))
                .single()).click();
        completePendingPromptValueJs(text);
    }

    private void completePendingPromptValueJs(String typedValue) {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(invocation -> invocation.getInvocation().getExpression().contains("return this.value"))
                .forEach(invocation -> invocation.complete(StringNode.valueOf(typedValue)));
    }

    private boolean awaitMarkdownContaining(ChatView view, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            roundTrip();
            boolean rendered = $(Markdown.class, view).all().stream()
                    .anyMatch(markdown -> markdown.getContent() != null && markdown.getContent().contains(expected));
            if (rendered) {
                return true;
            }
            Thread.sleep(50L);
        }
        return false;
    }

}
