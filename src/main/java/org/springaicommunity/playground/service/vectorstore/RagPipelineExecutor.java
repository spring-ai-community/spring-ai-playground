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

import org.springaicommunity.playground.service.SharedDataReader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.springaicommunity.playground.service.vectorstore.VectorStoreService.DOC_INFO_ID;

@Service
public class RagPipelineExecutor {

    public record RunResult(List<Document> joinedDocs, List<Document> finalDocs, String finalPrompt, String llmAnswer,
                            long totalMs) {}

    private final VectorStore vectorStore;
    private final VectorStoreService vectorStoreService;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final SharedDataReader<List<VectorStoreDocumentInfo>> documentInfosReader;
    private final DocumentJoiner documentJoiner = new ConcatenationDocumentJoiner();

    public RagPipelineExecutor(VectorStore vectorStore, VectorStoreService vectorStoreService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            SharedDataReader<List<VectorStoreDocumentInfo>> documentInfosReader) {
        this.vectorStore = vectorStore;
        this.vectorStoreService = vectorStoreService;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.documentInfosReader = documentInfosReader;
    }

    public RunResult execute(RagPipeline pipeline, String userQuery, Consumer<TraceEvent> traceConsumer) {
        return execute(pipeline, userQuery, List.of(), traceConsumer);
    }

    public RunResult execute(RagPipeline pipeline, String userQuery, List<Message> history,
            Consumer<TraceEvent> traceConsumer) {
        return execute(pipeline, userQuery, history, traceConsumer, true);
    }

    public RunResult executeForChat(RagPipeline pipeline, String userQuery, List<Message> history,
            Consumer<TraceEvent> traceConsumer) {
        return execute(pipeline, userQuery, history, traceConsumer, false);
    }

    private RunResult execute(RagPipeline pipeline, String userQuery, List<Message> history,
            Consumer<TraceEvent> traceConsumer, boolean allowAugmentLlm) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(userQuery, "userQuery");
        Consumer<TraceEvent> trace = traceConsumer == null ? event -> {} : traceConsumer;
        long startNs = System.nanoTime();

        List<String> queries = preRetrieval(pipeline, userQuery, history, trace);
        List<Document> joinedDocs = retrieveAndJoin(pipeline, queries, trace);
        List<Document> finalDocs = postRetrieval(pipeline, userQuery, joinedDocs, trace);
        String finalPrompt = augment(pipeline, userQuery, finalDocs, trace);
        String llmAnswer = allowAugmentLlm && pipeline.generation().runAugmentLLM() ? runLlm(finalPrompt, trace) : null;

        long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
        trace.accept(TraceEvent.info("done", "total " + totalMs + "ms"));
        return new RunResult(joinedDocs, finalDocs, finalPrompt, llmAnswer, totalMs);
    }

    private List<String> preRetrieval(RagPipeline pipeline, String userQuery, List<Message> history,
            Consumer<TraceEvent> trace) {
        RagPipeline.PreRetrievalConfig pre = pipeline.preRetrieval();
        String currentQuery = userQuery;

        if (pre.rewrite()) {
            ChatClient.Builder builder = this.chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                trace.accept(TraceEvent.warn("rewrite", "skipped (no ChatClient.Builder bean)"));
            } else {
                long startNanos = System.nanoTime();
                RewriteQueryTransformer.Builder rewriteBuilder = RewriteQueryTransformer.builder()
                        .chatClientBuilder(builder);
                PromptTemplate rewriteTpl = toPromptTemplate(pre.rewritePromptTemplate());
                if (rewriteTpl != null) rewriteBuilder.promptTemplate(rewriteTpl);
                boolean customTarget = StringUtils.hasText(pre.rewriteTargetSearchSystem());
                if (customTarget) rewriteBuilder.targetSearchSystem(pre.rewriteTargetSearchSystem());
                Query rewritten = rewriteBuilder.build().transform(new Query(currentQuery));
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                String previous = currentQuery;
                currentQuery = rewritten.text();
                trace.accept(TraceEvent.info("rewrite", "done " + ms + "ms"
                                + (customTarget ? " · target=" + pre.rewriteTargetSearchSystem() : ""),
                        Map.of("in", previous, "out", currentQuery)));
            }
        }

        if (pre.compression()) {
            ChatClient.Builder builder = this.chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                trace.accept(TraceEvent.warn("compression", "skipped (no ChatClient.Builder bean)"));
            } else {
                long startNanos = System.nanoTime();
                CompressionQueryTransformer.Builder compBuilder = CompressionQueryTransformer.builder()
                        .chatClientBuilder(builder);
                PromptTemplate compTpl = toPromptTemplate(pre.compressionPromptTemplate());
                if (compTpl != null) compBuilder.promptTemplate(compTpl);
                Query withHistory = Query.builder().text(currentQuery)
                        .history(history == null ? List.of() : history).build();
                Query compressed = compBuilder.build().transform(withHistory);
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                String previous = currentQuery;
                currentQuery = compressed.text();
                trace.accept(TraceEvent.info("compression",
                        "done " + ms + "ms · history=" + (history == null ? 0 : history.size()),
                        Map.of("in", previous, "out", currentQuery)));
            }
        }

        if (pre.translation()) {
            ChatClient.Builder builder = this.chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                trace.accept(TraceEvent.warn("translation", "skipped (no ChatClient.Builder bean)"));
            } else {
                long startNanos = System.nanoTime();
                TranslationQueryTransformer.Builder transBuilder = TranslationQueryTransformer.builder()
                        .chatClientBuilder(builder)
                        .targetLanguage(Objects.requireNonNullElse(pre.translationTargetLanguage(), "english"));
                PromptTemplate transTpl = toPromptTemplate(pre.translationPromptTemplate());
                if (transTpl != null) transBuilder.promptTemplate(transTpl);
                Query translated = transBuilder.build().transform(new Query(currentQuery));
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                String previous = currentQuery;
                currentQuery = translated.text();
                trace.accept(TraceEvent.info("translation",
                        "done " + ms + "ms · target=" + pre.translationTargetLanguage(),
                        Map.of("in", previous, "out", currentQuery)));
            }
        }

        if (pre.multiQuery()) {
            ChatClient.Builder builder = this.chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                trace.accept(TraceEvent.warn("multiQuery", "skipped (no ChatClient.Builder bean)"));
            } else {
                long startNanos = System.nanoTime();
                MultiQueryExpander.Builder mqBuilder = MultiQueryExpander.builder().chatClientBuilder(builder)
                        .numberOfQueries(pre.multiQueryCount())
                        .includeOriginal(pre.multiQueryIncludeOriginal());
                PromptTemplate mqTpl = toPromptTemplate(pre.multiQueryPromptTemplate());
                if (mqTpl != null) mqBuilder.promptTemplate(mqTpl);
                List<Query> expanded = mqBuilder.build().expand(new Query(currentQuery));
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                List<String> queries = expanded.stream().map(Query::text).toList();
                trace.accept(TraceEvent.info("multiQuery", "done " + ms + "ms · " + queries.size() + " queries",
                        queries));
                return queries;
            }
        }

        return List.of(currentQuery);
    }

    private List<Document> retrieveAndJoin(RagPipeline pipeline, List<String> queries, Consumer<TraceEvent> trace) {
        long startNanos = System.nanoTime();
        VectorStoreService.SearchRequestOption opt = this.vectorStoreService.getSearchRequestOption();
        RagPipeline.RetrievalConfig retrieval = pipeline.retrieval();
        int topK = retrieval.topK() != null ? retrieval.topK() : opt.topK();
        double similarityThreshold = retrieval.similarityThreshold() != null ? retrieval.similarityThreshold()
                : opt.similarityThreshold();
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder().vectorStore(this.vectorStore)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(buildFilterExpression(pipeline, trace))
                .build();
        trace.accept(TraceEvent.info("retrieve", "scope: " + (pipeline.docInfoIds().isEmpty() ? "(all docs)"
                : String.join(", ", pipeline.docInfoIds())) + " · topK=" + topK + " · threshold="
                + similarityThreshold));

        Map<Query, List<List<Document>>> retrieved = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            Query query = new Query(queries.get(i));
            List<Document> results = retriever.retrieve(query);
            retrieved.computeIfAbsent(query, ignored -> new ArrayList<>()).add(results);
            trace.accept(TraceEvent.info("retrieve", "q" + (i + 1) + " → " + results.size() + " docs"));
        }
        List<Document> joined = this.documentJoiner.join(retrieved);
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        trace.accept(TraceEvent.info("joiner", "done " + ms + "ms · " + joined.size() + " unique docs"));
        return joined;
    }

    private Filter.Expression buildFilterExpression(RagPipeline pipeline, Consumer<TraceEvent> trace) {
        Filter.Expression scope = scopeExpression(pipeline, trace);
        String extra = pipeline.retrieval().extraFilterExpression();
        if (extra == null || extra.isBlank()) return scope;
        Filter.Expression extraExpression;
        try {
            extraExpression = new FilterExpressionTextParser().parse(extra);
        } catch (RuntimeException e) {
            trace.accept(TraceEvent.warn("retrieve", "invalid filter expression ignored: " + extra));
            return scope;
        }
        trace.accept(TraceEvent.info("retrieve", "filter: " + extra));
        return scope == null ? extraExpression
                : new Filter.Expression(Filter.ExpressionType.AND, scope, extraExpression);
    }

    private Filter.Expression scopeExpression(RagPipeline pipeline, Consumer<TraceEvent> trace) {
        if (!pipeline.docInfoIds().isEmpty())
            return new FilterExpressionBuilder().in(DOC_INFO_ID, pipeline.docInfoIds().toArray()).build();
        List<String> chatOriginIds = this.documentInfosReader.read().stream()
                .filter(VectorStoreDocumentInfo::chatOrigin).map(VectorStoreDocumentInfo::docInfoId).toList();
        if (chatOriginIds.isEmpty()) return null;
        trace.accept(TraceEvent.info("retrieve",
                "excluding " + chatOriginIds.size() + " conversation attachments"));
        return new FilterExpressionBuilder().nin(DOC_INFO_ID, chatOriginIds.toArray()).build();
    }

    private List<Document> postRetrieval(RagPipeline pipeline, String userQuery, List<Document> joinedDocs,
            Consumer<TraceEvent> trace) {
        RagPipeline.PostRetrievalConfig post = pipeline.postRetrieval();
        ScoreOrderingDocumentPostProcessor postProcessor =
                new ScoreOrderingDocumentPostProcessor(post.reRankByScore(), post.topNTruncate());
        if (postProcessor.isNoOp()) return joinedDocs;
        int before = joinedDocs.size();
        List<Document> result = postProcessor.process(new Query(userQuery), joinedDocs);
        if (post.reRankByScore()) trace.accept(TraceEvent.info("postProcess", "re-ranked by score"));
        if (result.size() < before)
            trace.accept(TraceEvent.info("postProcess", "truncated " + before + " → " + result.size()));
        return result;
    }

    private String augment(RagPipeline pipeline, String userQuery, List<Document> finalDocs,
            Consumer<TraceEvent> trace) {
        RagPipeline.GenerationConfig gen = pipeline.generation();
        long startNanos = System.nanoTime();
        ContextualQueryAugmenter.Builder augBuilder = ContextualQueryAugmenter.builder()
                .allowEmptyContext(gen.allowEmptyContext());
        PromptTemplate promptTpl = toPromptTemplate(gen.promptTemplate());
        if (promptTpl != null) augBuilder.promptTemplate(promptTpl);
        PromptTemplate emptyTpl = toPromptTemplate(gen.emptyContextPromptTemplate());
        if (emptyTpl != null) augBuilder.emptyContextPromptTemplate(emptyTpl);
        boolean withSource = RagPipeline.DocumentFormat.TEXT_WITH_SOURCE.equals(gen.documentFormat());
        if (withSource) augBuilder.documentFormatter(RagPipelineExecutor::formatDocumentsWithSource);
        Query augmented = augBuilder.build().augment(new Query(userQuery), finalDocs);
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        String prompt = augmented.text();
        trace.accept(TraceEvent.info("augment", "done " + ms + "ms · prompt " + prompt.length() + " chars"
                + (withSource ? " · format=text+source" : "")));
        return prompt;
    }

    private static String formatDocumentsWithSource(List<Document> documents) {
        return documents.stream()
                .map(document -> "[source: " + sourceLabel(document) + "]\n" + document.getText())
                .collect(Collectors.joining("\n\n"));
    }

    private static String sourceLabel(Document document) {
        Object source = document.getMetadata().getOrDefault("source", document.getMetadata().get(DOC_INFO_ID));
        return Objects.toString(source, Objects.toString(document.getId(), "document"));
    }

    private String runLlm(String prompt, Consumer<TraceEvent> trace) {
        ChatClient.Builder builder = this.chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            trace.accept(TraceEvent.warn("llm", "skipped (no ChatClient.Builder bean)"));
            return null;
        }
        long startNanos = System.nanoTime();
        String answer = builder.build().prompt().user(prompt).call().content();
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        trace.accept(TraceEvent.info("llm", "done " + ms + "ms · " + (answer == null ? 0 : answer.length()) + " chars"));
        return answer;
    }

    private static PromptTemplate toPromptTemplate(String template) {
        if (template == null || template.isBlank()) return null;
        return PromptTemplate.builder().template(template).build();
    }
}
