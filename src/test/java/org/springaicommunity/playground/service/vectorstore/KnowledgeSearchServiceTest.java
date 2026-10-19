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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService.SearchResult;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    private final RagPipelineService ragPipelineService = mock(RagPipelineService.class);
    private final OfflineEtlPipelineService documents = mock(OfflineEtlPipelineService.class);
    private final RagPipelineExecutor executor = mock(RagPipelineExecutor.class);
    private KnowledgeSearchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OfflineEtlPipelineService> documentsProvider = mock(ObjectProvider.class);
        when(documentsProvider.getIfAvailable()).thenReturn(this.documents);
        ObjectProvider<RagPipelineExecutor> executorProvider = mock(ObjectProvider.class);
        when(executorProvider.getIfAvailable()).thenReturn(this.executor);
        when(this.documents.getVisibleDocumentList()).thenReturn(List.of(document("doc-1", "Handbook")));
        this.service = new KnowledgeSearchService(this.ragPipelineService, documentsProvider, executorProvider);
    }

    @Test
    void renderKeepsTheExcerptHeaderFormatAndTheOutputBudget() {
        Document first = Document.builder().id("c1").text("first excerpt")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1")
                .metadata(KnowledgeSearchService.CHUNK_INDEX_METADATA, 2).score(0.83).build();
        Document second = Document.builder().id("c2").text("x".repeat(KnowledgeSearchService.OUTPUT_CHAR_BUDGET))
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1").score(0.41).build();

        SearchResult result = this.service.render(List.of(first, second), Map.of());

        assertThat(result.asTexts().getFirst()).isEqualTo("[1] Handbook · chunk 3 · score 0.83\nfirst excerpt");
        assertThat(result.excerpts()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.excerpts().get(1).text().length()).isLessThan(KnowledgeSearchService.OUTPUT_CHAR_BUDGET);
        Map<String, Object> structured = result.asStructured();
        assertThat(structured.get("truncated")).isEqualTo(true);
        List<?> results = (List<?>) structured.get("results");
        assertThat(((Map<?, ?>) results.getFirst()).get("chunk")).isEqualTo(3);
        assertThat(((Map<?, ?>) results.get(1)).containsKey("chunk")).isFalse();
        assertThat(this.service.render(List.of(), Map.of()).asTexts()).containsExactly(SearchResult.EMPTY_MESSAGE);
    }

    @Test
    void chunkNumbersFollowTheDocumentWideOrdinalInTheChunkIdNotThePerSectionIndex() {
        Document firstSection = Document.builder().id("0-doc-1").text("intro")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1")
                .metadata(KnowledgeSearchService.CHUNK_INDEX_METADATA, 0).build();
        Document secondSection = Document.builder().id("7-doc-1").text("berths")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1")
                .metadata(KnowledgeSearchService.CHUNK_INDEX_METADATA, 0).build();

        SearchResult result = this.service.render(List.of(firstSection, secondSection), Map.of());

        assertThat(result.excerpts()).extracting(KnowledgeSearchService.Excerpt::chunk).containsExactly(1, 8);
        assertThat(result.asTexts().get(1)).startsWith("[2] Handbook · chunk 8");
    }

    @Test
    void titleOverridesWinOverTheCurrentDocumentList() {
        Document chunk = Document.builder().id("c1").text("t").metadata(VectorStoreService.DOC_INFO_ID, "doc-1").build();

        SearchResult result = this.service.render(List.of(chunk), Map.of("doc-1", "Frozen title"));

        assertThat(result.excerpts().getFirst().document()).isEqualTo("Frozen title");
    }

    @Test
    void aDocumentSourceResolvesToARetrievalOnlyPipelineOverThatDocument() {
        when(this.ragPipelineService.get(anyString())).thenReturn(Optional.empty());

        RagPipeline pipeline = this.service.resolvePipeline("doc:doc-1").orElseThrow();

        assertThat(pipeline.id()).isEqualTo("doc-doc-1");
        assertThat(pipeline.name()).isEqualTo("Handbook");
        assertThat(pipeline.docInfoIds()).containsExactly("doc-1");
        assertThat(pipeline.extraLlmCallCount()).isZero();
        assertThat(this.service.resolvePipeline("doc-doc-1")).isPresent();
        assertThat(this.service.resolvePipeline("doc:missing")).isEmpty();
    }

    @Test
    void searchValidatesItsArgumentsAndAppliesTheTopKOverride() {
        RagPipeline stored = new RagPipeline("p1", "KB", null, List.of("doc-1"), null,
                new RagPipeline.RetrievalConfig(null, 8, null), null, null, 0L, 0L);
        when(this.ragPipelineService.get("p1")).thenReturn(Optional.of(stored));
        RagPipelineExecutor.RunResult run = mock(RagPipelineExecutor.RunResult.class);
        when(run.finalDocs()).thenReturn(List.of(Document.builder().id("c1").text("hit")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1").score(0.7).build()));
        when(this.executor.executeForChat(any(RagPipeline.class), eq("dry dock"), anyList(), any())).thenReturn(run);

        assertThatThrownBy(() -> this.service.search("p1", " ", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("query is required");
        assertThatThrownBy(() -> this.service.search("p1", "q", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("top_k");
        assertThatThrownBy(() -> this.service.search("missing", "q", null, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not available");

        SearchResult result = this.service.search("p1", "dry dock", 3, Map.of());

        ArgumentCaptor<RagPipeline> effective = ArgumentCaptor.forClass(RagPipeline.class);
        verify(this.executor).executeForChat(effective.capture(), eq("dry dock"), anyList(), any());
        assertThat(effective.getValue().retrieval().topK()).isEqualTo(3);
        assertThat(effective.getValue().id()).isEqualTo("p1");
        assertThat(result.excerpts()).singleElement()
                .satisfies(excerpt -> assertThat(excerpt.asText()).isEqualTo("[1] Handbook · score 0.70\nhit"));
    }

    private static VectorStoreDocumentInfo document(String docInfoId, String title) {
        return new VectorStoreDocumentInfo(docInfoId, title, 0L, 0L, title + ".txt", "/tmp/" + title, List::of);
    }

}
