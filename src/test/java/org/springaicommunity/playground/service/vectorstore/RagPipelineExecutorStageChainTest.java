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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sibling RagPipelineExecutorTest deliberately runs without a ChatClient.Builder, so every
 * LLM-backed pre-retrieval stage there only proves the graceful-skip path. These tests wire a real
 * ChatClient over a canned ChatModel so the stages actually execute, and assert on the query that
 * reaches the vector store - the only observable that proves a stage did its job and that chained
 * stages feed each other in order.
 */
class RagPipelineExecutorStageChainTest {

    private static final String REWRITTEN = "rewritten query";
    private static final String COMPRESSED = "compressed standalone query";
    private static final String TRANSLATED = "translated query";
    private static final List<String> VARIANTS = List.of("variant alpha", "variant beta", "variant gamma");

    private static ChatModel cannedChatModel() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String prompt = ((Prompt) invocation.getArgument(0)).getContents();
            String answer;
            if (prompt.contains("Query variants:")) answer = String.join("\n", VARIANTS);
            else if (prompt.contains("Standalone query:")) answer = COMPRESSED;
            else if (prompt.contains("Translated query:")) answer = TRANSLATED;
            else if (prompt.contains("Rewritten query:")) answer = REWRITTEN;
            else answer = "generated answer";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        return chatModel;
    }

    private static ObjectProvider<ChatClient.Builder> chatClientProvider(ChatModel chatModel) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ChatClient.builder(chatModel));
        return provider;
    }

    private static VectorStore stubVectorStore() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(Document.builder().id("d1").text("indexed chunk").metadata(Map.of("docInfoId", "doc1"))
                        .score(0.9).build()));
        return vectorStore;
    }

    private static VectorStoreService globalOptions() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.getSearchRequestOption())
                .thenReturn(new VectorStoreService.SearchRequestOption(0.5, 10));
        return vectorStoreService;
    }

    private static RagPipeline pipelineWith(RagPipeline.PreRetrievalConfig pre) {
        return new RagPipeline("p", "staged", null, List.of(), pre, RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(), RagPipeline.GenerationConfig.defaults(), 0L, 0L);
    }

    private static RagPipeline.PreRetrievalConfig pre(boolean rewrite, boolean compression, boolean translation,
            boolean multiQuery) {
        return new RagPipeline.PreRetrievalConfig(rewrite, null, compression, null, translation, "korean", null,
                multiQuery, VARIANTS.size(), false, null);
    }

    private static List<String> retrievedQueries(VectorStore vectorStore) {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        return captor.getAllValues().stream().map(SearchRequest::getQuery).toList();
    }

    private record Run(RagPipelineExecutor.RunResult result, List<TraceEvent> events, VectorStore vectorStore) {}

    private static Run run(RagPipeline pipeline, String query, List<Message> history) {
        VectorStore vectorStore = stubVectorStore();
        RagPipelineExecutor executor = new RagPipelineExecutor(vectorStore, globalOptions(),
                chatClientProvider(cannedChatModel()), List::of);
        List<TraceEvent> events = new ArrayList<>();
        RagPipelineExecutor.RunResult result = executor.execute(pipeline, query, history, events::add);
        return new Run(result, events, vectorStore);
    }

    private static boolean stageSucceeded(List<TraceEvent> events, String stage) {
        return events.stream().anyMatch(e -> e.stage().equals(stage) && e.level() == TraceEvent.Level.INFO);
    }

    @Test
    void rewriteAloneReplacesTheQueryThatReachesRetrieval() {
        Run run = run(pipelineWith(pre(true, false, false, false)), "tell me about the ledger thing", List.of());

        assertThat(retrievedQueries(run.vectorStore())).containsExactly(REWRITTEN);
        assertThat(stageSucceeded(run.events(), "rewrite")).isTrue();
        assertThat(run.result().finalDocs()).hasSize(1);
    }

    @Test
    void compressionAloneFoldsHistoryIntoAStandaloneQuery() {
        List<Message> history = List.of(new UserMessage("What is the Aurora Ledger?"),
                new AssistantMessage("An internal accounting system."));

        Run run = run(pipelineWith(pre(false, true, false, false)), "who maintains it?", history);

        assertThat(retrievedQueries(run.vectorStore())).containsExactly(COMPRESSED);
        assertThat(run.events()).anyMatch(e -> e.stage().equals("compression") && e.message().contains("history=2"));
    }

    @Test
    void translationAloneReplacesTheQueryAndRecordsTheTargetLanguage() {
        Run run = run(pipelineWith(pre(false, false, true, false)), "누가 관리하나요?", List.of());

        assertThat(retrievedQueries(run.vectorStore())).containsExactly(TRANSLATED);
        assertThat(run.events()).anyMatch(e -> e.stage().equals("translation") && e.message().contains("korean"));
    }

    @Test
    void multiQueryAloneFansOutOneRetrievalPerVariant() {
        Run run = run(pipelineWith(pre(false, false, false, true)), "ledger owner", List.of());

        assertThat(retrievedQueries(run.vectorStore())).containsExactlyInAnyOrderElementsOf(VARIANTS);
        assertThat(run.events()).anyMatch(e -> e.stage().equals("multiQuery")
                && e.message().contains(VARIANTS.size() + " queries"));
    }

    @Test
    void multiQueryResultsAreDedupedByTheJoiner() {
        Run run = run(pipelineWith(pre(false, false, false, true)), "ledger owner", List.of());

        assertThat(retrievedQueries(run.vectorStore())).hasSize(VARIANTS.size());
        assertThat(run.result().joinedDocs()).hasSize(1);
        assertThat(run.events()).anyMatch(e -> e.stage().equals("joiner") && e.message().contains("1 unique docs"));
    }

    @Test
    void allFourStagesChainInOrderSoTheLastStageSeesTheEarlierRewrites() {
        List<Message> history = List.of(new UserMessage("What is the Aurora Ledger?"),
                new AssistantMessage("An internal accounting system."));

        Run run = run(pipelineWith(pre(true, true, true, true)), "who maintains it?", history);

        assertThat(retrievedQueries(run.vectorStore())).containsExactlyInAnyOrderElementsOf(VARIANTS);
        assertThat(run.events().stream().filter(e -> e.level() == TraceEvent.Level.INFO).map(TraceEvent::stage)
                .filter(List.of("rewrite", "compression", "translation", "multiQuery")::contains))
                .containsExactly("rewrite", "compression", "translation", "multiQuery");
        assertThat(run.events()).noneMatch(e -> e.level() == TraceEvent.Level.WARN);
    }

    @Test
    void partialSubsetRunsOnlyTheEnabledStages() {
        Run run = run(pipelineWith(pre(true, false, false, true)), "tell me about the ledger thing", List.of());

        assertThat(retrievedQueries(run.vectorStore())).containsExactlyInAnyOrderElementsOf(VARIANTS);
        assertThat(stageSucceeded(run.events(), "rewrite")).isTrue();
        assertThat(stageSucceeded(run.events(), "multiQuery")).isTrue();
        assertThat(run.events()).noneMatch(e -> e.stage().equals("compression"));
        assertThat(run.events()).noneMatch(e -> e.stage().equals("translation"));
    }

    @Test
    void compressionWithoutHistoryStillProducesAUsableQuery() {
        Run run = run(pipelineWith(pre(false, true, false, false)), "who maintains it?", List.of());

        assertThat(retrievedQueries(run.vectorStore())).containsExactly(COMPRESSED);
        assertThat(run.events()).anyMatch(e -> e.stage().equals("compression") && e.message().contains("history=0"));
    }

    @Test
    void noStagesEnabledSendsTheRawQueryAndCallsNoLlm() {
        ChatModel chatModel = cannedChatModel();
        VectorStore vectorStore = stubVectorStore();
        RagPipelineExecutor executor = new RagPipelineExecutor(vectorStore, globalOptions(),
                chatClientProvider(chatModel), List::of);
        List<TraceEvent> events = new ArrayList<>();

        executor.execute(pipelineWith(pre(false, false, false, false)), "raw query", List.of(), events::add);

        assertThat(retrievedQueries(vectorStore)).containsExactly("raw query");
        assertThat(events).noneMatch(e -> e.level() == TraceEvent.Level.WARN);
    }

    @Test
    void rewriteTargetSearchSystemFillsTheTargetPlaceholderInTheRewritePrompt() {
        ChatModel chatModel = cannedChatModel();
        VectorStore vectorStore = stubVectorStore();
        RagPipelineExecutor executor = new RagPipelineExecutor(vectorStore, globalOptions(),
                chatClientProvider(chatModel), List::of);
        RagPipeline pipeline = pipelineWith(new RagPipeline.PreRetrievalConfig(true, null, false, null,
                false, "english", null, false, 3, false, null, "web search"));
        List<TraceEvent> events = new ArrayList<>();

        executor.execute(pipeline, "tell me about the ledger", List.of(), events::add);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(prompts.capture());
        assertThat(prompts.getAllValues()).anyMatch(p -> p.getContents().contains("querying a web search"));
        assertThat(events).anyMatch(e -> e.stage().equals("rewrite") && e.message().contains("target=web search"));
    }

    @Test
    void textWithSourceFormatPrefixesEachChunkWithItsSourceInTheFinalPrompt() {
        RagPipeline pipeline = new RagPipeline("p-fmt", "with-source", null, List.of(),
                pre(false, false, false, false), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(false, null, null, false,
                        RagPipeline.DocumentFormat.TEXT_WITH_SOURCE), 0L, 0L);

        Run run = run(pipeline, "ledger owner", List.of());

        assertThat(run.result().finalPrompt()).contains("[source: doc1]");
        assertThat(run.result().finalPrompt()).contains("indexed chunk");
        assertThat(run.events()).anyMatch(e -> e.stage().equals("augment")
                && e.message().contains("format=text+source"));
    }

    @Test
    void generationStageRunsTheLlmOnlyWhenRunAugmentLlmIsOn() {
        RagPipeline withLlm = new RagPipeline("p-llm", "with-llm", null, List.of(),
                pre(false, false, false, false), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(false, null, null, true), 0L, 0L);

        Run enabled = run(withLlm, "ledger owner", List.of());
        Run disabled = run(pipelineWith(pre(false, false, false, false)), "ledger owner", List.of());

        assertThat(enabled.result().llmAnswer()).isEqualTo("generated answer");
        assertThat(disabled.result().llmAnswer()).isNull();
    }

    @Test
    void executeForChatSkipsTheGenerationLlmEvenWithEveryStageEnabled() {
        RagPipeline everything = new RagPipeline("p-all", "everything", null, List.of(),
                pre(true, true, true, true), RagPipeline.RetrievalConfig.defaults(),
                RagPipeline.PostRetrievalConfig.defaults(),
                new RagPipeline.GenerationConfig(false, null, null, true), 0L, 0L);
        VectorStore vectorStore = stubVectorStore();
        RagPipelineExecutor executor = new RagPipelineExecutor(vectorStore, globalOptions(),
                chatClientProvider(cannedChatModel()), List::of);
        List<TraceEvent> events = new ArrayList<>();

        RagPipelineExecutor.RunResult result =
                executor.executeForChat(everything, "who maintains it?", List.of(), events::add);

        assertThat(result.llmAnswer()).isNull();
        assertThat(result.finalPrompt()).isNotBlank();
        assertThat(events).noneMatch(e -> e.stage().equals("llm"));
        assertThat(stageSucceeded(events, "multiQuery")).isTrue();
    }
}
