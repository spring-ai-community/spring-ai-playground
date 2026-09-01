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
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.SharedDataReader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagPipelineExecutorTest {

    private static Document doc(String id, double score, String text, String docInfoId) {
        Document document = new Document(id, text, Map.of("docInfoId", docInfoId));
        document.getMetadata().put("distance", 1d - score);
        return Document.builder().id(document.getId()).text(document.getText()).metadata(document.getMetadata()).score(score).build();
    }

    private static ObjectProvider<ChatClient.Builder> emptyProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static RagPipeline naivePipeline(int topNTruncate) {
        return new RagPipeline("p1", "naive", null, List.of("doc1", "doc2"),
                RagPipeline.PreRetrievalConfig.defaults(),
                RagPipeline.RetrievalConfig.defaults(),
                new RagPipeline.PostRetrievalConfig(true, topNTruncate),
                RagPipeline.GenerationConfig.defaults(), 0L, 0L);
    }

    private static VectorStoreService mockVectorStoreService(double threshold, int topK) {
        VectorStoreService svc = mock(VectorStoreService.class);
        when(svc.getSearchRequestOption()).thenReturn(new VectorStoreService.SearchRequestOption(threshold, topK));
        return svc;
    }

    @Test
    void naiveExecutionRetrievesJoinedDocsAndBuildsPrompt() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("d1", 0.84, "Spring AI provides a vector store abstraction.", "doc1"),
                doc("d2", 0.78, "Vector stores can be queried for similarity.", "doc2")));

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();

        RagPipelineExecutor.RunResult result = executor.execute(naivePipeline(0), "What is Spring AI?", events::add);

        assertEquals(2, result.joinedDocs().size());
        assertEquals(2, result.finalDocs().size());
        assertNotNull(result.finalPrompt());
        assertTrue(result.finalPrompt().contains("Spring AI provides"));
        assertNull(result.llmAnswer());
        Set<String> stages = events.stream().map(TraceEvent::stage).collect(Collectors.toSet());
        assertTrue(stages.contains("retrieve"));
        assertTrue(stages.contains("joiner"));
        assertTrue(stages.contains("postProcess"));
        assertTrue(stages.contains("augment"));
        assertTrue(stages.contains("done"));
    }

    @Test
    void postProcessTopNTruncatesAndReRanks() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("d1", 0.50, "low", "doc1"),
                doc("d2", 0.84, "high", "doc1"),
                doc("d3", 0.71, "mid-high", "doc2"),
                doc("d4", 0.62, "mid-low", "doc2")));

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        RagPipelineExecutor.RunResult result = executor.execute(naivePipeline(2), "q", null);

        assertEquals(4, result.joinedDocs().size());
        assertEquals(2, result.finalDocs().size());
        assertEquals("d2", result.finalDocs().get(0).getId());
        assertEquals("d3", result.finalDocs().get(1).getId());
    }

    @Test
    void augmentLlmIsSkippedWhenNoChatClientBuilderAvailable() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("d1", 0.84, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p2", "with-llm", null, List.of(),
                RagPipeline.PreRetrievalConfig.defaults(), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(false, null, null, true), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, "q", events::add);

        assertNull(result.llmAnswer());
        TraceEvent llmEvent = events.stream().filter(e -> e.stage().equals("llm")).findFirst().orElseThrow();
        assertEquals(TraceEvent.Level.WARN, llmEvent.level());
    }

    @Test
    void joinerDeduplicatesDocumentsAcrossQueries() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("dup-id", 0.8, "duplicate", "doc1"),
                doc("only-id", 0.7, "unique", "doc1")));

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        RagPipelineExecutor.RunResult result = executor.execute(naivePipeline(0), "q", null);

        assertEquals(2, result.joinedDocs().size());
        Set<String> ids = result.joinedDocs().stream().map(Document::getId).collect(Collectors.toSet());
        assertEquals(Set.of("dup-id", "only-id"), ids);
    }

    @Test
    void preRetrievalRewriteSkipsGracefullyWithoutChatClientBuilder() {
        assertStageSkippedWithoutBuilder("rewrite", new RagPipeline.PreRetrievalConfig(
                true, null, false, null, false, "english", null, false, 3, true, null));
    }

    @Test
    void preRetrievalCompressionSkipsGracefullyWithoutChatClientBuilder() {
        assertStageSkippedWithoutBuilder("compression", new RagPipeline.PreRetrievalConfig(
                false, null, true, null, false, "english", null, false, 3, true, null));
    }

    @Test
    void preRetrievalTranslationSkipsGracefullyWithoutChatClientBuilder() {
        assertStageSkippedWithoutBuilder("translation", new RagPipeline.PreRetrievalConfig(
                false, null, false, null, true, "korean", null, false, 3, true, null));
    }

    @Test
    void preRetrievalMultiQuerySkipsGracefullyWithoutChatClientBuilder() {
        assertStageSkippedWithoutBuilder("multiQuery", new RagPipeline.PreRetrievalConfig(
                false, null, false, null, false, "english", null, true, 3, true, null));
    }

    @Test
    void allFourPreRetrievalStagesEnabledTraceInOrderAndAllSkipWithoutBuilder() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.84, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-all-pre", "all-stages", null, List.of(),
                new RagPipeline.PreRetrievalConfig(true, null, true, null, true, "korean", null, true, 3, true, null),
                RagPipeline.RetrievalConfig.defaults(), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        executor.execute(pipeline, "q", events::add);

        List<String> warnStages = events.stream().filter(e -> e.level() == TraceEvent.Level.WARN)
                .map(TraceEvent::stage).toList();
        assertEquals(List.of("rewrite", "compression", "translation", "multiQuery"), warnStages);
    }

    @Test
    void customPromptTemplatesArePersistedAndReachExecutor() {
        RagPipeline.PreRetrievalConfig pre = new RagPipeline.PreRetrievalConfig(
                true, "Rewrite for {target}: {query}",
                false, null,
                false, "english", null,
                false, 3, true, null);
        RagPipeline pipeline = new RagPipeline("p-tpl", "tpl", null, List.of(), pre,
                RagPipeline.RetrievalConfig.defaults(), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        assertEquals("Rewrite for {target}: {query}", pipeline.preRetrieval().rewritePromptTemplate());

        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.84, "x", "doc1")));
        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, "q", null);
        assertNotNull(result.finalPrompt());
    }

    @Test
    void augmentEmitsTraceEventEvenForEmptyDocs() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagPipeline pipeline = new RagPipeline("p-empty", "empty", null, List.of(),
                RagPipeline.PreRetrievalConfig.defaults(), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(true, null, null, false), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, "q", events::add);

        assertEquals(0, result.joinedDocs().size());
        assertEquals(0, result.finalDocs().size());
        assertNotNull(result.finalPrompt());
        assertTrue(events.stream().anyMatch(e -> e.stage().equals("augment")));
    }

    @Test
    void historyOverloadDelegatesAndKeepsResultIdenticalWhenCompressionOff() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("d1", 0.84, "Spring AI provides a vector store abstraction.", "doc1")));

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<Message> history = List.of(
                new UserMessage("What is Spring AI?"),
                new AssistantMessage("A framework for AI apps."));

        RagPipelineExecutor.RunResult withHistory =
                executor.execute(naivePipeline(0), "And the vector store?", history, null);
        RagPipelineExecutor.RunResult withoutHistory =
                executor.execute(naivePipeline(0), "And the vector store?", null);

        assertEquals(withoutHistory.finalPrompt(), withHistory.finalPrompt());
        assertEquals(withoutHistory.finalDocs().size(), withHistory.finalDocs().size());
    }

    @Test
    void pipelineOwnedTopKAndThresholdOverrideGlobalOption() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-own", "own-settings", null, List.of(),
                RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig(null, 3, 0.8),
                RagPipeline.PostRetrievalConfig.defaults(), RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        executor.execute(pipeline, "q", events::add);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        assertEquals(3, captor.getValue().getTopK());
        assertEquals(0.8, captor.getValue().getSimilarityThreshold());
        assertTrue(events.stream().anyMatch(e -> e.stage().equals("retrieve")
                && e.message().contains("topK=3") && e.message().contains("threshold=0.8")));
    }

    @Test
    void legacyPipelineWithoutRetrievalSettingsFallsBackToGlobalOption() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        executor.execute(naivePipeline(0), "q", null);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        assertEquals(10, captor.getValue().getTopK());
        assertEquals(0.5, captor.getValue().getSimilarityThreshold());
    }

    @Test
    void extraFilterExpressionIsAppliedAlongsideDocumentScope() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-filter", "filtered", null, List.of("doc1"),
                RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig("country == 'KR'", null, null),
                RagPipeline.PostRetrievalConfig.defaults(), RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        executor.execute(pipeline, "q", events::add);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        assertNotNull(captor.getValue().getFilterExpression());
        assertEquals(Filter.ExpressionType.AND, captor.getValue().getFilterExpression().type());
        assertTrue(events.stream().anyMatch(e -> e.stage().equals("retrieve")
                && e.message().contains("filter: country == 'KR'")));
    }

    @Test
    void unscopedPipelineExcludesChatOriginDocuments() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));
        SharedDataReader<List<VectorStoreDocumentInfo>> documentInfos = () -> List.of(
                new VectorStoreDocumentInfo("doc1", "curated", 0L, 0L, "curated.txt", "", List::of),
                new VectorStoreDocumentInfo("chat1", "attached", null, 0L, 0L, "attached.txt", "", true, List::of));

        RagPipelineExecutor executor =
                new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), documentInfos);
        List<TraceEvent> events = new ArrayList<>();
        executor.execute(unscopedPipeline("p-unscoped"), "q", events::add);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        Filter.Expression filter = captor.getValue().getFilterExpression();
        assertNotNull(filter);
        assertEquals(Filter.ExpressionType.NIN, filter.type());
        assertTrue(filter.right().toString().contains("chat1"));
        assertTrue(events.stream().anyMatch(e -> e.message().contains("excluding 1 conversation attachments")));
    }

    @Test
    void unscopedPipelineStaysUnfilteredWithoutChatOriginDocuments() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));
        SharedDataReader<List<VectorStoreDocumentInfo>> documentInfos = () -> List.of(
                new VectorStoreDocumentInfo("doc1", "curated", 0L, 0L, "curated.txt", "", List::of));

        RagPipelineExecutor executor =
                new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), documentInfos);
        executor.execute(unscopedPipeline("p-open"), "q", ignored -> {});

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        assertNull(captor.getValue().getFilterExpression());
    }

    private static RagPipeline unscopedPipeline(String id) {
        return new RagPipeline(id, id, null, List.of(), RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig(null, null, null), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults(), 0L, 0L);
    }

    @Test
    void invalidExtraFilterExpressionIsIgnoredWithWarn() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.9, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-badfilter", "bad-filter", null, List.of(),
                RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig("country ===== 'KR", null, null),
                RagPipeline.PostRetrievalConfig.defaults(), RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, "q", events::add);

        assertEquals(1, result.finalDocs().size());
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(captor.capture());
        assertNull(captor.getValue().getFilterExpression());
        assertTrue(events.stream().anyMatch(e -> e.level() == TraceEvent.Level.WARN
                && e.message().contains("invalid filter expression")));
    }

    @Test
    void executeForChatNeverRunsAugmentLlmEvenWhenEnabled() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.84, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-chat", "chat", null, List.of(),
                RagPipeline.PreRetrievalConfig.defaults(), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(false, null, null, true), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.executeForChat(pipeline, "q", List.of(), events::add);

        assertNull(result.llmAnswer());
        assertNotNull(result.finalPrompt());
        assertTrue(events.stream().noneMatch(e -> e.stage().equals("llm")));
    }

    private void assertStageSkippedWithoutBuilder(String expectedStage, RagPipeline.PreRetrievalConfig pre) {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("d1", 0.84, "x", "doc1")));

        RagPipeline pipeline = new RagPipeline("p-skip-" + expectedStage, expectedStage, null, List.of(), pre,
                RagPipeline.RetrievalConfig.defaults(), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults(), 0L, 0L);

        RagPipelineExecutor executor = new RagPipelineExecutor(vs, mockVectorStoreService(0.5, 10), emptyProvider(), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, "q", events::add);

        assertNotNull(result.finalPrompt());
        TraceEvent skipEvent = events.stream().filter(e -> e.stage().equals(expectedStage)).findFirst()
                .orElseThrow(() -> new AssertionError("No trace event for stage " + expectedStage
                        + " - wiring missing in RagPipelineExecutor.preRetrieval()"));
        assertEquals(TraceEvent.Level.WARN, skipEvent.level(),
                "Expected WARN-level skip event for " + expectedStage + " (no ChatClient.Builder available)");
    }
}
