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
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiPlaygroundRagAdvisorTest {

    private final RagPipelineService ragPipelineService = mock(RagPipelineService.class);
    private final RagPipelineExecutor ragPipelineExecutor = mock(RagPipelineExecutor.class);
    private final SpringAiPlaygroundRagAdvisor advisor =
            new SpringAiPlaygroundRagAdvisor(ragPipelineService, ragPipelineExecutor, List::of);

    private final RagPipeline pipeline = new RagPipeline("pipeline-x", "test-pipeline", null, List.of(),
            null, null, null, null, 0L, 0L);

    private ChatClientRequest requestWith(String userText) {
        return requestWith(userText, "pipeline-x");
    }

    private ChatClientRequest requestWith(String userText, String sourceId) {
        return ChatClientRequest.builder().prompt(new Prompt(List.of(new UserMessage(userText))))
                .context(Map.of(ChatService.RAG_SOURCE_ID, sourceId)).build();
    }

    @Test
    void blankQuerySkipsPipelineExecutionInsteadOfThrowing() {
        ChatClientRequest request = requestWith("   ");

        ChatClientRequest result = advisor.before(request, null);

        assertThat(result).isSameAs(request);
        verify(ragPipelineExecutor, never()).executeForChat(any(), any(), anyList(), any());
    }

    @Test
    void unknownPipelineIdSkipsPipelineExecution() {
        when(ragPipelineService.get("pipeline-x")).thenReturn(Optional.empty());
        ChatClientRequest request = requestWith("weather in seoul");

        ChatClientRequest result = advisor.before(request, null);

        assertThat(result).isSameAs(request);
        verify(ragPipelineExecutor, never()).executeForChat(any(), any(), anyList(), any());
    }

    @Test
    void pipelineResultAugmentsUserMessageAndSetsDocumentContext() {
        when(ragPipelineService.get("pipeline-x")).thenReturn(Optional.of(pipeline));
        List<Document> finalDocs = List.of(new Document("indexed chunk", Map.of("source", "advisor-test")));
        when(ragPipelineExecutor.executeForChat(eq(pipeline), eq("weather in seoul"), anyList(), any()))
                .thenReturn(new RagPipelineExecutor.RunResult(finalDocs, finalDocs, "augmented prompt", null, 3L));
        ChatClientRequest request = requestWith("weather in seoul");

        ChatClientRequest result = advisor.before(request, null);

        assertThat(result.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)).isEqualTo(finalDocs);
        assertThat(result.prompt().getUserMessage().getText()).isEqualTo("augmented prompt");
    }

    @Test
    void documentSourceRunsAdHocRetrievalOnlyPipelineScopedToThatDocument() {
        SpringAiPlaygroundRagAdvisor docAdvisor = new SpringAiPlaygroundRagAdvisor(ragPipelineService,
                ragPipelineExecutor, () -> List.of(new VectorStoreDocumentInfo("doc-1", "manual.pdf",
                        0L, 0L, "manual.pdf", null, List::of)));
        List<Document> finalDocs = List.of(new Document("chunk", Map.of("docInfoId", "doc-1")));
        ArgumentCaptor<RagPipeline> captor = ArgumentCaptor.forClass(RagPipeline.class);
        when(ragPipelineExecutor.executeForChat(captor.capture(), eq("what does the manual say"), anyList(), any()))
                .thenReturn(new RagPipelineExecutor.RunResult(finalDocs, finalDocs, "augmented prompt", null, 3L));
        ChatClientRequest request = requestWith("what does the manual say", "doc:doc-1");

        ChatClientRequest result = docAdvisor.before(request, null);

        assertThat(captor.getValue().name()).isEqualTo("manual.pdf");
        assertThat(captor.getValue().docInfoIds()).containsExactly("doc-1");
        assertThat(captor.getValue().extraLlmCallCount()).isZero();
        assertThat(result.prompt().getUserMessage().getText()).isEqualTo("augmented prompt");
        verify(ragPipelineService, never()).get(any());
    }

    @Test
    void emptyRetrievalStillAugmentsWithEmptyContextPrompt() {
        when(ragPipelineService.get("pipeline-x")).thenReturn(Optional.of(pipeline));
        when(ragPipelineExecutor.executeForChat(eq(pipeline), eq("weather in seoul"), anyList(), any()))
                .thenReturn(new RagPipelineExecutor.RunResult(List.of(), List.of(), "refusal prompt", null, 2L));
        ChatClientRequest request = requestWith("weather in seoul");

        ChatClientRequest result = advisor.before(request, null);

        assertThat(result.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)).isEqualTo(List.of());
        assertThat(result.prompt().getUserMessage().getText()).isEqualTo("refusal prompt");
    }
}
