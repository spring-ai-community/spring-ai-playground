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
package org.springaicommunity.playground.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.ChatDocumentAttachment;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Grade;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Status;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

class AttachedDocumentRagAdvisorTest {

    private final ChatDocumentIntakeService intakeService = mock(ChatDocumentIntakeService.class);
    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);
    private final AttachedDocumentRagAdvisor advisor =
            new AttachedDocumentRagAdvisor(intakeService, vectorStoreService);

    private static ChatDocumentAttachment attachment(Status status, Grade grade, String docInfoId,
            String inlineText, String summary) {
        return new ChatDocumentAttachment("a1", "doc.pdf", "application/pdf", 10, grade, status, null, docInfoId,
                "a1-doc.pdf", summary, inlineText, 100, 3, null, false, 1L, 1L);
    }

    private ChatClientRequest requestWith(String userText) {
        return ChatClientRequest.builder().prompt(new Prompt(List.of(new UserMessage(userText))))
                .context(Map.of(CONVERSATION_ID, "c1")).build();
    }

    private static String userTextOf(ChatClientRequest request) {
        return request.prompt().getInstructions().stream().filter(message -> message instanceof UserMessage)
                .reduce((first, second) -> second).map(message -> ((UserMessage) message).getText()).orElse("");
    }

    @Test
    void noAttachmentsPassesRequestThrough() {
        when(intakeService.list("c1")).thenReturn(List.of());
        ChatClientRequest request = requestWith("hello");

        assertThat(advisor.before(request, null)).isSameAs(request);
        verify(vectorStoreService, never()).search(any(SearchRequest.class));
    }

    @Test
    void smallDocumentInjectsFullTextWithoutSearch() {
        when(intakeService.list("c1")).thenReturn(List.of(
                attachment(Status.READY, Grade.SMALL, null, "quarterly revenue was 12.5M", null)));

        ChatClientRequest result = advisor.before(requestWith("요약해줘"), null);

        String userText = userTextOf(result);
        assertThat(userText).contains("quarterly revenue was 12.5M").contains("요약해줘");
        verify(vectorStoreService, never()).search(any(SearchRequest.class));
    }

    @Test
    void overviewInjectedAndExcerptsSearchedOnContentTerms() {
        when(intakeService.list("c1")).thenReturn(List.of(
                attachment(Status.READY, Grade.MEDIUM, "docInfoId-1", null, "the contract overview")));
        when(vectorStoreService.search(any(SearchRequest.class))).thenReturn(List.of(
                new Document("penalty is three percent", Map.of("source", "doc.pdf"))));

        ChatClientRequest result = advisor.before(requestWith("위약금 조항 어디 있어"), null);

        String userText = userTextOf(result);
        assertThat(userText).contains("the contract overview").contains("penalty is three percent");
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStoreService).search(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(AttachedDocumentRagAdvisor.EXCERPT_TOP_K);
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.0);
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void directiveOnlyQuestionStillSearchesExcerpts() {
        when(intakeService.list("c1")).thenReturn(List.of(
                attachment(Status.READY, Grade.LARGE, "docInfoId-1", null, "the contract overview")));
        when(vectorStoreService.search(any(SearchRequest.class))).thenReturn(List.of());

        ChatClientRequest result = advisor.before(requestWith("요약해줘"), null);

        assertThat(userTextOf(result)).contains("the contract overview");
        verify(vectorStoreService, times(1)).search(any(SearchRequest.class));
    }

    @Test
    void secondPassInSameRequestIsIdempotent() {
        when(intakeService.list("c1")).thenReturn(List.of(
                attachment(Status.READY, Grade.MEDIUM, "docInfoId-1", null, "the contract overview")));
        when(vectorStoreService.search(any(SearchRequest.class))).thenReturn(List.of(
                new Document("penalty is three percent", Map.of("source", "doc.pdf"))));

        ChatClientRequest first = advisor.before(requestWith("위약금 조항 어디 있어"), null);
        ChatClientRequest second = advisor.before(first, null);

        assertThat(second).isSameAs(first);
        verify(vectorStoreService, times(1)).search(any(SearchRequest.class));
    }

    @Test
    void processingDocumentInjectsHonestNote() {
        when(intakeService.list("c1")).thenReturn(List.of(
                attachment(Status.SUMMARIZING, Grade.MEDIUM, "docInfoId-1", null, null)));

        ChatClientRequest result = advisor.before(requestWith("결제 조건 알려줘"), null);

        assertThat(userTextOf(result)).contains("Still being processed");
        verify(vectorStoreService, never()).search(any(SearchRequest.class));
    }
}
