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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Conformance to Spring AI's Modular RAG contract, plus a standalone exercise of each of the six
 * roles the spec defines. The executor composes these; here each is driven directly so a break in
 * one element is attributed to that element rather than surfacing as a vague pipeline failure.
 *
 * Spec roles (org.springframework.ai.rag): QueryTransformer, QueryExpander, DocumentRetriever,
 * DocumentJoiner, DocumentPostProcessor, QueryAugmenter.
 */
class ModularRagSpecConformanceTest {

    private static ChatModel modelAnswering(String answer) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        return chatModel;
    }

    private static ChatClient.Builder clientAnswering(String answer) {
        return ChatClient.builder(modelAnswering(answer));
    }

    private static Document doc(String id, String text, double score) {
        return Document.builder().id(id).text(text).metadata(Map.of()).score(score).build();
    }

    @Test
    void executorJoinsThroughTheDocumentJoinerContract() throws Exception {
        Field field = RagPipelineExecutor.class.getDeclaredField("documentJoiner");
        field.setAccessible(true);
        RagPipelineExecutor executor = new RagPipelineExecutor(mock(VectorStore.class),
                mock(VectorStoreService.class), null, List::of);

        Object joiner = field.get(executor);

        assertThat(joiner).isInstanceOf(DocumentJoiner.class).isInstanceOf(ConcatenationDocumentJoiner.class);
    }

    @Test
    void postProcessorImplementsTheSpecContract() {
        assertThat(new ScoreOrderingDocumentPostProcessor(true, 3)).isInstanceOf(DocumentPostProcessor.class);
    }

    @Test
    void everyStageTypeTheExecutorBuildsImplementsItsSpecRole() {
        assertThat(RewriteQueryTransformer.builder().chatClientBuilder(clientAnswering("x")).build())
                .isInstanceOf(QueryTransformer.class);
        assertThat(CompressionQueryTransformer.builder().chatClientBuilder(clientAnswering("x")).build())
                .isInstanceOf(QueryTransformer.class);
        assertThat(TranslationQueryTransformer.builder().chatClientBuilder(clientAnswering("x"))
                .targetLanguage("english").build()).isInstanceOf(QueryTransformer.class);
        assertThat(MultiQueryExpander.builder().chatClientBuilder(clientAnswering("x")).build())
                .isInstanceOf(QueryExpander.class);
        assertThat(VectorStoreDocumentRetriever.builder().vectorStore(mock(VectorStore.class)).build())
                .isInstanceOf(DocumentRetriever.class);
        assertThat(ContextualQueryAugmenter.builder().build()).isInstanceOf(QueryAugmenter.class);
    }

    @Test
    void queryTransformerRewriteProducesTheModelsQuery() {
        QueryTransformer transformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(clientAnswering("clean search query")).build();

        assertThat(transformer.transform(new Query("umm so like what about the ledger")).text())
                .isEqualTo("clean search query");
    }

    @Test
    void queryTransformerCompressionFoldsHistoryIntoAStandaloneQuery() {
        QueryTransformer transformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(clientAnswering("who maintains the Aurora Ledger")).build();
        Query withHistory = Query.builder().text("who maintains it?")
                .history(List.of(new org.springframework.ai.chat.messages.UserMessage("What is the Aurora Ledger?"),
                        new AssistantMessage("An accounting system."))).build();

        assertThat(transformer.transform(withHistory).text()).isEqualTo("who maintains the Aurora Ledger");
    }

    @Test
    void queryTransformerTranslationProducesTheTargetLanguageQuery() {
        QueryTransformer transformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(clientAnswering("who maintains the ledger"))
                .targetLanguage("english").build();

        assertThat(transformer.transform(new Query("누가 원장을 관리하나요?")).text())
                .isEqualTo("who maintains the ledger");
    }

    @Test
    void queryExpanderProducesOneQueryPerVariant() {
        QueryExpander expander = MultiQueryExpander.builder()
                .chatClientBuilder(clientAnswering("ledger owner\nledger maintainer\nwho runs the ledger"))
                .numberOfQueries(3).includeOriginal(false).build();

        assertThat(expander.expand(new Query("ledger")).stream().map(Query::text))
                .containsExactly("ledger owner", "ledger maintainer", "who runs the ledger");
    }

    @Test
    void documentRetrieverDelegatesToTheVectorStore() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc("d1", "chunk", 0.9)));
        DocumentRetriever retriever = VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                .topK(4).similarityThreshold(0.5).build();

        assertThat(retriever.retrieve(new Query("ledger"))).extracting(Document::getId).containsExactly("d1");
    }

    @Test
    void documentJoinerConcatenatesAndDedupesById() {
        DocumentJoiner joiner = new ConcatenationDocumentJoiner();
        Map<Query, List<List<Document>>> retrieved = new LinkedHashMap<>();
        retrieved.put(new Query("q1"), List.of(List.of(doc("dup", "same", 0.8), doc("only-a", "a", 0.7))));
        retrieved.put(new Query("q2"), List.of(List.of(doc("dup", "same", 0.8), doc("only-b", "b", 0.6))));

        assertThat(joiner.join(retrieved)).extracting(Document::getId)
                .containsExactlyInAnyOrder("dup", "only-a", "only-b");
    }

    @Test
    void documentPostProcessorReRanksByScoreDescending() {
        DocumentPostProcessor postProcessor = new ScoreOrderingDocumentPostProcessor(true, null);

        assertThat(postProcessor.process(new Query("q"),
                List.of(doc("low", "l", 0.2), doc("high", "h", 0.9), doc("mid", "m", 0.5))))
                .extracting(Document::getId).containsExactly("high", "mid", "low");
    }

    @Test
    void documentPostProcessorTruncatesToTopNAfterReRanking() {
        DocumentPostProcessor postProcessor = new ScoreOrderingDocumentPostProcessor(true, 2);

        assertThat(postProcessor.process(new Query("q"),
                List.of(doc("low", "l", 0.2), doc("high", "h", 0.9), doc("mid", "m", 0.5))))
                .extracting(Document::getId).containsExactly("high", "mid");
    }

    @Test
    void documentPostProcessorIsANoOpWhenNeitherReRankNorTruncateIsConfigured() {
        ScoreOrderingDocumentPostProcessor postProcessor = new ScoreOrderingDocumentPostProcessor(false, null);
        List<Document> documents = List.of(doc("low", "l", 0.2), doc("high", "h", 0.9));

        assertThat(postProcessor.isNoOp()).isTrue();
        assertThat(postProcessor.process(new Query("q"), documents))
                .extracting(Document::getId).containsExactly("low", "high");
    }

    @Test
    void queryAugmenterBuildsAGroundedPromptFromTheRetrievedContext() {
        QueryAugmenter augmenter = ContextualQueryAugmenter.builder().build();

        String prompt = augmenter.augment(new Query("who maintains the ledger"),
                List.of(doc("d1", "Mira Kwon maintains the Aurora Ledger.", 0.9))).text();

        assertThat(prompt).contains("Mira Kwon maintains the Aurora Ledger.");
        assertThat(prompt).contains("who maintains the ledger");
    }

    @Test
    void queryAugmenterAcceptsACustomDocumentFormatter() {
        QueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .documentFormatter(documents -> documents.stream()
                        .map(document -> "<<" + document.getId() + ">> " + document.getText())
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b))
                .build();

        String prompt = augmenter.augment(new Query("who maintains the ledger"),
                List.of(doc("d1", "Mira Kwon maintains the Aurora Ledger.", 0.9))).text();

        assertThat(prompt).contains("<<d1>> Mira Kwon maintains the Aurora Ledger.");
    }

    @Test
    void queryTransformerRewriteTargetsTheConfiguredSearchSystem() {
        ChatModel chatModel = modelAnswering("clean search query");
        QueryTransformer transformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .targetSearchSystem("web search").build();

        transformer.transform(new Query("umm what about the ledger"));

        org.mockito.ArgumentCaptor<Prompt> prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(prompts.capture());
        assertThat(prompts.getValue().getContents()).contains("querying a web search");
    }

    @Test
    void queryAugmenterRefusesEmptyContextUnlessAllowed() {
        QueryAugmenter strict = ContextualQueryAugmenter.builder().allowEmptyContext(false).build();
        QueryAugmenter permissive = ContextualQueryAugmenter.builder().allowEmptyContext(true).build();

        assertThat(strict.augment(new Query("unrelated question"), List.of()).text()).isNotBlank();
        assertThat(permissive.augment(new Query("unrelated question"), List.of()).text()).isNotBlank();
    }
}
