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

import org.springaicommunity.playground.service.chat.ChatService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeSearchService {

    public static final int MAX_TOP_K = 20;
    public static final int OUTPUT_CHAR_BUDGET = 24_000;
    public static final String CHUNK_INDEX_METADATA = "chunk_index";
    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile("(\\d+)-.+");
    public static final String DOCUMENT_PIPELINE_ID_PREFIX = "doc-";

    public record Excerpt(int rank, String documentId, String document, Integer chunk, Double score, String text) {

        public String header() {
            return "[" + rank + "] " + document + (chunk == null ? "" : " · chunk " + chunk)
                    + (score == null ? "" : " · score " + String.format(Locale.ROOT, "%.2f", score));
        }

        public String asText() {
            return header() + "\n" + text;
        }
    }

    public record SearchResult(List<Excerpt> excerpts, boolean truncated) {

        public static final String EMPTY_MESSAGE = "No matching excerpts.";

        public List<String> asTexts() {
            return this.excerpts.isEmpty() ? List.of(EMPTY_MESSAGE)
                    : this.excerpts.stream().map(Excerpt::asText).toList();
        }

        public Map<String, Object> asStructured() {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Excerpt excerpt : this.excerpts) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", excerpt.rank());
                entry.put("documentId", excerpt.documentId());
                entry.put("document", excerpt.document());
                if (excerpt.chunk() != null) entry.put("chunk", excerpt.chunk());
                if (excerpt.score() != null) entry.put("score", excerpt.score());
                entry.put("text", excerpt.text());
                results.add(entry);
            }
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("results", results);
            structured.put("truncated", this.truncated);
            return structured;
        }
    }

    private final RagPipelineService ragPipelineService;
    private final ObjectProvider<OfflineEtlPipelineService> documentsProvider;
    private final ObjectProvider<RagPipelineExecutor> executorProvider;

    public KnowledgeSearchService(RagPipelineService ragPipelineService,
            ObjectProvider<OfflineEtlPipelineService> documentsProvider,
            ObjectProvider<RagPipelineExecutor> executorProvider) {
        this.ragPipelineService = ragPipelineService;
        this.documentsProvider = documentsProvider;
        this.executorProvider = executorProvider;
    }

    public static RagPipeline documentPipeline(VectorStoreDocumentInfo info) {
        return new RagPipeline(DOCUMENT_PIPELINE_ID_PREFIX + info.docInfoId(), info.title(), info.description(),
                List.of(info.docInfoId()), null, null, null,
                new RagPipeline.GenerationConfig(true, null, null, false), 0L, 0L);
    }

    public static RagPipeline withTopK(RagPipeline pipeline, int topK) {
        RagPipeline.RetrievalConfig retrieval = pipeline.retrieval();
        return new RagPipeline(pipeline.id(), pipeline.name(), pipeline.description(), pipeline.docInfoIds(),
                pipeline.preRetrieval(),
                new RagPipeline.RetrievalConfig(retrieval.extraFilterExpression(), topK,
                        retrieval.similarityThreshold()),
                pipeline.postRetrieval(), pipeline.generation(), pipeline.createdAt(), pipeline.updatedAt());
    }

    public record Snapshot(int documents, int chunks, List<String> llmStages) {}

    public Optional<Snapshot> snapshot(String sourceId) {
        return resolvePipeline(sourceId).map(pipeline -> {
            List<VectorStoreDocumentInfo> all = knowledgeDocuments();
            List<VectorStoreDocumentInfo> scoped = pipeline.docInfoIds().isEmpty() ? all
                    : all.stream().filter(info -> pipeline.docInfoIds().contains(info.docInfoId())).toList();
            int chunks = scoped.stream()
                    .mapToInt(info -> info.documentListSupplier() == null ? 0 : info.documentListSupplier().get().size())
                    .sum();
            List<String> llmStages = pipeline.stageLabels().stream()
                    .filter(label -> !label.equals("Retrieve") && !label.equals("Re-rank") && !label.equals("Augment")
                            && !label.startsWith("Top-")).toList();
            return new Snapshot(scoped.size(), chunks, llmStages);
        });
    }

    public Optional<RagPipeline> resolvePipeline(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return Optional.empty();
        Optional<RagPipeline> stored = this.ragPipelineService.get(sourceId);
        if (stored.isPresent()) return stored;
        String docInfoId = documentIdOf(sourceId);
        if (docInfoId == null) return Optional.empty();
        return knowledgeDocuments().stream().filter(info -> docInfoId.equals(info.docInfoId())).findFirst()
                .map(KnowledgeSearchService::documentPipeline);
    }

    public SearchResult search(String sourceId, String query, Integer topK, Map<String, String> titleOverrides) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        if (topK != null && (topK < 1 || topK > MAX_TOP_K)) {
            throw new IllegalArgumentException("top_k must be between 1 and " + MAX_TOP_K);
        }
        RagPipelineExecutor executor = this.executorProvider.getIfAvailable();
        Optional<RagPipeline> pipeline = resolvePipeline(sourceId);
        if (executor == null || pipeline.isEmpty()) {
            throw new IllegalStateException("knowledge source '" + sourceId + "' is not available");
        }
        RagPipeline effective = topK == null ? pipeline.get() : withTopK(pipeline.get(), topK);
        List<Document> documents = executor.executeForChat(effective, query, List.of(), null).finalDocs();
        return render(documents, titleOverrides);
    }

    public SearchResult render(List<Document> documents, Map<String, String> titleOverrides) {
        Map<String, String> titles = new LinkedHashMap<>();
        if (titleOverrides != null) titles.putAll(titleOverrides);
        knowledgeDocuments().forEach(info -> titles.putIfAbsent(info.docInfoId(), info.title()));
        List<Excerpt> excerpts = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        int rank = 0;
        for (Document document : documents) {
            rank++;
            String docInfoId = String.valueOf(document.getMetadata().get(VectorStoreService.DOC_INFO_ID));
            String title = titles.getOrDefault(docInfoId, docInfoId);
            Integer chunk = chunkNumber(document);
            String text = Objects.requireNonNullElse(document.getText(), "");
            Excerpt excerpt = new Excerpt(rank, docInfoId, title, chunk, document.getScore(), text);
            int remaining = OUTPUT_CHAR_BUDGET - used - excerpt.header().length() - 1;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (text.length() > remaining) {
                excerpt = new Excerpt(rank, docInfoId, title, chunk, document.getScore(),
                        text.substring(0, remaining));
                truncated = true;
            }
            excerpts.add(excerpt);
            used += excerpt.header().length() + 1 + excerpt.text().length();
            if (truncated) break;
        }
        return new SearchResult(excerpts, truncated);
    }

    private static String documentIdOf(String sourceId) {
        if (sourceId.startsWith(ChatService.RAG_DOCUMENT_SOURCE_PREFIX)) {
            return sourceId.substring(ChatService.RAG_DOCUMENT_SOURCE_PREFIX.length());
        }
        if (sourceId.startsWith(DOCUMENT_PIPELINE_ID_PREFIX)) {
            return sourceId.substring(DOCUMENT_PIPELINE_ID_PREFIX.length());
        }
        return null;
    }

    private static Integer chunkNumber(Document document) {
        Matcher ordinal = CHUNK_ID_PATTERN.matcher(Objects.requireNonNullElse(document.getId(), ""));
        if (ordinal.matches()) return Integer.parseInt(ordinal.group(1)) + 1;
        Object index = document.getMetadata().get(CHUNK_INDEX_METADATA);
        return index instanceof Number number ? number.intValue() + 1 : null;
    }

    private List<VectorStoreDocumentInfo> knowledgeDocuments() {
        OfflineEtlPipelineService documents = this.documentsProvider.getIfAvailable();
        return documents == null ? List.of() : documents.getVisibleDocumentList();
    }

}
