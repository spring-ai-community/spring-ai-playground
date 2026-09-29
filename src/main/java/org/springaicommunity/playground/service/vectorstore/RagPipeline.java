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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record RagPipeline(String id, String name, String description, List<String> docInfoIds,
                          PreRetrievalConfig preRetrieval, RetrievalConfig retrieval,
                          PostRetrievalConfig postRetrieval, GenerationConfig generation,
                          long createdAt, long updatedAt) {

    public RagPipeline {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        docInfoIds = docInfoIds == null ? List.of() : List.copyOf(docInfoIds);
        preRetrieval = preRetrieval == null ? PreRetrievalConfig.defaults() : preRetrieval;
        retrieval = retrieval == null ? RetrievalConfig.defaults() : retrieval;
        postRetrieval = postRetrieval == null ? PostRetrievalConfig.defaults() : postRetrieval;
        generation = generation == null ? GenerationConfig.defaults() : generation;
    }

    public RagPipeline withTimestamps(long createdAt, long updatedAt) {
        return new RagPipeline(id, name, description, docInfoIds, preRetrieval, retrieval, postRetrieval, generation,
                createdAt, updatedAt);
    }

    public RagPipeline rename(String newName, long updatedAt) {
        return new RagPipeline(id, newName, description, docInfoIds, preRetrieval, retrieval, postRetrieval, generation,
                createdAt, updatedAt);
    }

    public List<String> stageLabels() {
        return Stream.of(preRetrieval.rewrite() ? "Rewrite" : null,
                        preRetrieval.compression() ? "Compress" : null,
                        preRetrieval.translation() ? "Translate" : null,
                        preRetrieval.multiQuery() ? "Multi-Q×" + preRetrieval.multiQueryCount() : null, "Retrieve",
                        postRetrieval.reRankByScore() ? "Re-rank" : null,
                        postRetrieval.topNTruncate() != null && postRetrieval.topNTruncate() > 0
                                ? "Top-" + postRetrieval.topNTruncate() : null, "Augment")
                .filter(Objects::nonNull).toList();
    }

    public static String templateOrNull(String value, String defaultTemplate) {
        if (value == null || value.isBlank()) return null;
        if (Objects.equals(value, defaultTemplate)) return null;
        return value;
    }

    public int extraLlmCallCount() {
        return (preRetrieval.rewrite() ? 1 : 0) + (preRetrieval.compression() ? 1 : 0)
                + (preRetrieval.translation() ? 1 : 0) + (preRetrieval.multiQuery() ? 1 : 0);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreRetrievalConfig(boolean rewrite, String rewritePromptTemplate,
                                     boolean compression, String compressionPromptTemplate,
                                     boolean translation, String translationTargetLanguage,
                                     String translationPromptTemplate,
                                     boolean multiQuery, int multiQueryCount,
                                     boolean multiQueryIncludeOriginal, String multiQueryPromptTemplate,
                                     String rewriteTargetSearchSystem) {
        public PreRetrievalConfig(boolean rewrite, String rewritePromptTemplate,
                boolean compression, String compressionPromptTemplate,
                boolean translation, String translationTargetLanguage, String translationPromptTemplate,
                boolean multiQuery, int multiQueryCount,
                boolean multiQueryIncludeOriginal, String multiQueryPromptTemplate) {
            this(rewrite, rewritePromptTemplate, compression, compressionPromptTemplate, translation,
                    translationTargetLanguage, translationPromptTemplate, multiQuery, multiQueryCount,
                    multiQueryIncludeOriginal, multiQueryPromptTemplate, null);
        }

        public static final String DEFAULT_REWRITE_TARGET = "vector store";

        public static final String DEFAULT_REWRITE_TEMPLATE =
                "Given a user query, rewrite it to provide better results when querying a {target}.\n"
                        + "Remove any irrelevant information, and ensure the query is concise and specific.\n\n"
                        + "Original query:\n{query}\n\nRewritten query:\n";

        public static final String DEFAULT_COMPRESSION_TEMPLATE =
                "Given the following conversation history and a follow-up query, your task is to synthesize\n"
                        + "a concise, standalone query that incorporates the context from the history.\n"
                        + "Ensure the standalone query is clear, specific, and maintains the user's intent.\n\n"
                        + "Conversation history:\n{history}\n\nFollow-up query:\n{query}\n\nStandalone query:\n";

        public static final String DEFAULT_TRANSLATION_TEMPLATE =
                "Given a user query, translate it to {targetLanguage}.\n"
                        + "If the query is already in {targetLanguage}, return it unchanged.\n"
                        + "If you don't know the language of the query, return it unchanged.\n"
                        + "Do not add explanations nor any other text.\n\n"
                        + "Original query: {query}\n\nTranslated query:\n";

        public static final String DEFAULT_MULTI_QUERY_TEMPLATE =
                "You are an expert at information retrieval and search optimization.\n"
                        + "Your task is to generate {number} different versions of the given query.\n\n"
                        + "Each variant must cover different perspectives or aspects of the topic,\n"
                        + "while maintaining the core intent of the original query. The goal is to\n"
                        + "expand the search space and improve the chances of finding relevant information.\n\n"
                        + "Do not explain your choices or add any other text.\n"
                        + "Provide the query variants separated by newlines.\n\n"
                        + "Original query: {query}\n\nQuery variants:\n";

        public static PreRetrievalConfig defaults() {
            return new PreRetrievalConfig(false, null, false, null, false, "english", null,
                    false, 3, true, null, null);
        }

        public String effectiveRewritePromptTemplate() {
            return this.rewritePromptTemplate == null ? DEFAULT_REWRITE_TEMPLATE : this.rewritePromptTemplate;
        }

        public String effectiveCompressionPromptTemplate() {
            return this.compressionPromptTemplate == null
                    ? DEFAULT_COMPRESSION_TEMPLATE : this.compressionPromptTemplate;
        }

        public String effectiveTranslationPromptTemplate() {
            return this.translationPromptTemplate == null
                    ? DEFAULT_TRANSLATION_TEMPLATE : this.translationPromptTemplate;
        }

        public String effectiveMultiQueryPromptTemplate() {
            return this.multiQueryPromptTemplate == null ? DEFAULT_MULTI_QUERY_TEMPLATE : this.multiQueryPromptTemplate;
        }

        public String effectiveRewriteTargetSearchSystem() {
            return this.rewriteTargetSearchSystem == null ? DEFAULT_REWRITE_TARGET : this.rewriteTargetSearchSystem;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrievalConfig(String extraFilterExpression, Integer topK, Double similarityThreshold) {
        public RetrievalConfig(String extraFilterExpression) {
            this(extraFilterExpression, null, null);
        }

        public static RetrievalConfig defaults() {
            return new RetrievalConfig(null, null, null);
        }
    }

    public record PostRetrievalConfig(boolean reRankByScore, Integer topNTruncate) {
        public static PostRetrievalConfig defaults() {
            return new PostRetrievalConfig(true, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationConfig(boolean allowEmptyContext, String promptTemplate,
                                   String emptyContextPromptTemplate, boolean runAugmentLLM,
                                   DocumentFormat documentFormat) {
        public GenerationConfig {
            documentFormat = documentFormat == null ? DocumentFormat.TEXT : documentFormat;
        }

        public GenerationConfig(boolean allowEmptyContext, String promptTemplate,
                String emptyContextPromptTemplate, boolean runAugmentLLM) {
            this(allowEmptyContext, promptTemplate, emptyContextPromptTemplate, runAugmentLLM, null);
        }

        public static final String DEFAULT_AUGMENTER_TEMPLATE =
                "Context information is below.\n\n---------------------\n{context}\n---------------------\n\n"
                        + "Given the context information and no prior knowledge, answer the query.\n\n"
                        + "Follow these rules:\n\n"
                        + "1. If the answer is not in the context, just say that you don't know.\n"
                        + "2. Avoid statements like \"Based on the context...\" or \"The provided information...\".\n\n"
                        + "Query: {query}\n\nAnswer:\n";

        public static final String DEFAULT_EMPTY_CONTEXT_TEMPLATE =
                "The user query is outside your knowledge base.\n"
                        + "Politely inform the user that you can't answer it.\n";

        public static GenerationConfig defaults() {
            return new GenerationConfig(false, null, null, false, null);
        }

        public String effectivePromptTemplate() {
            return this.promptTemplate == null ? DEFAULT_AUGMENTER_TEMPLATE : this.promptTemplate;
        }

        public String effectiveEmptyContextPromptTemplate() {
            return this.emptyContextPromptTemplate == null
                    ? DEFAULT_EMPTY_CONTEXT_TEMPLATE : this.emptyContextPromptTemplate;
        }
    }

    public enum DocumentFormat { TEXT, TEXT_WITH_SOURCE }
}
