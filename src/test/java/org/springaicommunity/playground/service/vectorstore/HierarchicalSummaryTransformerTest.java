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
package org.springaicommunity.playground.service.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalSummaryTransformerTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final HierarchicalSummaryTransformer transformer = new HierarchicalSummaryTransformer(chatModel);

    private void stubSummary() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("stub summary")))));
    }

    private static List<Document> chunksOf(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Document("chunk " + i + " text", Map.of("source", "doc.pdf"))).toList();
    }

    @Test
    void singleWindowSummarizesInOneCall() {
        stubSummary();

        String summary = transformer.summarize("doc.pdf", chunksOf(3), () -> false, ignored -> {});

        assertThat(summary).isEqualTo("stub summary");
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void multiWindowReducesThenSummarizes() {
        stubSummary();

        String summary = transformer.summarize("doc.pdf", chunksOf(13), () -> false, ignored -> {});

        assertThat(summary).isEqualTo("stub summary");
        verify(chatModel, times(4)).call(any(Prompt.class));
        assertThat(transformer.plannedCalls(13)).isEqualTo(4);
    }

    @Test
    void cancellationStopsWithoutSummary() {
        String summary = transformer.summarize("doc.pdf", chunksOf(13), () -> true, ignored -> {});

        assertThat(summary).isNull();
        verify(chatModel, times(0)).call(any(Prompt.class));
    }

    @Test
    void applyAppendsSectionAndDocumentLevels() {
        stubSummary();

        List<Document> result = transformer.apply(chunksOf(13));

        assertThat(result).hasSize(17);
        assertThat(result.stream()
                .filter(document -> Integer.valueOf(2).equals(
                        document.getMetadata().get(HierarchicalSummaryTransformer.LEVEL)))).hasSize(3);
        assertThat(result.stream()
                .filter(document -> Integer.valueOf(3).equals(
                        document.getMetadata().get(HierarchicalSummaryTransformer.LEVEL)))).hasSize(1);
    }
}
