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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@SpringBootTest
class ChatViewDocumentAttachTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private ChatDocumentIntakeService documentIntakeService;

    @Autowired
    private VectorStoreDocumentService vectorStoreDocumentService;

    @BeforeEach
    void stubModels() {
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("stub summary")))));
        lenient().when(embeddingModel.embed(any(Document.class))).thenReturn(new float[]{1f, 0f, 0f});
        lenient().when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation ->
                new EmbeddingResponse(IntStream.range(0,
                                ((EmbeddingRequest) invocation.getArgument(0)).getInstructions().size())
                        .mapToObj(i -> new Embedding(new float[]{1f, 0f, 0f}, i)).toList()));
    }

    private static String base64Of(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String mediumText() {
        return IntStream.range(0, 5000).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
    }

    private ChatAttach chatAttach(ChatView view) {
        return $(ChatAttach.class, view).first();
    }

    private List<Div> removeControls(ChatView view, String fileName) {
        return $(Div.class, view)
                .withCondition(div -> ("Remove " + fileName).equals(div.getElement().getAttribute("title"))).all();
    }

    private List<Icon> iconsWithTooltip(ChatView view, Predicate<String> tooltipText) {
        return $(Icon.class, view).withCondition(icon -> icon.getTooltip() != null
                && icon.getTooltip().getText() != null && tooltipText.test(icon.getTooltip().getText())).all();
    }

    private boolean awaitBadge(ChatView view, String fileName, String badge) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            roundTrip();
            boolean chipPresent = !removeControls(view, fileName).isEmpty();
            boolean badgePresent = !$(Span.class, view)
                    .withCondition(span -> badge.equals(span.getText())).all().isEmpty();
            if (chipPresent && badgePresent) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void smallAttachmentRendersFullTextChipAndRemoveClearsIt() throws InterruptedException {
        ChatView view = navigate(ChatView.class);
        String conversationId = $(ChatContentView.class, view).first().getConversationId();

        chatAttach(view).receiveDocument("notes.md", base64Of("The wifi password rotates monthly."),
                "text/markdown");

        assertThat(awaitBadge(view, "notes.md", "Full text")).isTrue();
        test(removeControls(view, "notes.md").getFirst()).click();
        assertThat(removeControls(view, "notes.md")).isEmpty();
        assertThat(this.documentIntakeService.list(conversationId)).isEmpty();
    }

    @Test
    void indexedChipPromotesIntoVisibleDocuments() throws InterruptedException {
        String fileName = "contract-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        ChatView view = navigate(ChatView.class);
        String conversationId = $(ChatContentView.class, view).first().getConversationId();

        chatAttach(view).receiveDocument(fileName, base64Of(mediumText()), "text/plain");

        assertThat(awaitBadge(view, fileName, "Indexed")).isTrue();
        assertThat(visibleTitles()).doesNotContain(fileName);
        List<Icon> promote = iconsWithTooltip(view, "Register in Vector Database"::equals);
        assertThat(promote).hasSize(1);

        test(promote.getFirst()).click();

        assertThat(visibleTitles()).contains(fileName);
        assertThat(iconsWithTooltip(view, "Register in Vector Database"::equals)).isEmpty();
        assertThat(iconsWithTooltip(view, tooltip -> tooltip.startsWith("Registered in Vector Database")))
                .hasSize(1);
        assertThat(this.documentIntakeService.list(conversationId)).singleElement()
                .satisfies(attachment -> assertThat(attachment.promoted()).isTrue());

        test(removeControls(view, fileName).getFirst()).click();

        assertThat(visibleTitles()).contains(fileName);
        assertThat(this.documentIntakeService.list(conversationId)).isEmpty();
    }

    private List<String> visibleTitles() {
        return this.vectorStoreDocumentService.getVisibleDocumentList().stream()
                .map(VectorStoreDocumentInfo::title).toList();
    }
}
