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

        public static PreRetrievalConfig defaults() {
            return new PreRetrievalConfig(false, null, false, null, false, "english", null,
                    false, 3, true, null, null);
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

        public static GenerationConfig defaults() {
            return new GenerationConfig(false, null, null, false, null);
        }
    }

    public enum DocumentFormat { TEXT, TEXT_WITH_SOURCE }
}
