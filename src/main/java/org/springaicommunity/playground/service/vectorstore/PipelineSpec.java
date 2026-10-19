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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springaicommunity.playground.service.vectorstore.RagPipeline.DocumentFormat;
import org.springaicommunity.playground.service.vectorstore.RagPipeline.GenerationConfig;
import org.springaicommunity.playground.service.vectorstore.RagPipeline.PostRetrievalConfig;
import org.springaicommunity.playground.service.vectorstore.RagPipeline.PreRetrievalConfig;
import org.springaicommunity.playground.service.vectorstore.RagPipeline.RetrievalConfig;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

public record PipelineSpec(
        @JsonPropertyDescription("Pipeline name, the key the app matches on; an existing pipeline with this name is "
                + "updated in place.") String name,
        @Nullable @JsonPropertyDescription("What the pipeline is for.") String description,
        @Nullable @JsonPropertyDescription("Document titles or ids the pipeline searches; empty means every "
                + "knowledge-base document.") List<String> documents,
        @Nullable @JsonPropertyDescription("Query transformations before retrieval; each enabled one is a "
                + "chat-model call per query.") PreRetrieval preRetrieval,
        @Nullable @JsonPropertyDescription("Retrieval settings; unset values fall back to the global "
                + "vectorstore settings.") Retrieval retrieval,
        @Nullable PostRetrieval postRetrieval,
        @Nullable @JsonPropertyDescription("Prompt augmentation; only used when a chat model answers, not by the "
                + "retrieval-only search tools.") Generation generation) {

    public PipelineSpec {
        documents = documents == null ? List.of() : List.copyOf(documents);
        preRetrieval = preRetrieval == null ? PreRetrieval.defaults() : preRetrieval;
        retrieval = retrieval == null ? Retrieval.defaults() : retrieval;
        postRetrieval = postRetrieval == null ? PostRetrieval.defaults() : postRetrieval;
        generation = generation == null ? Generation.defaults() : generation;
    }

    public static PipelineSpec of(RagPipeline pipeline, List<String> documents) {
        return new PipelineSpec(pipeline.name(), pipeline.description(), documents,
                PreRetrieval.of(pipeline.preRetrieval()), Retrieval.of(pipeline.retrieval()),
                PostRetrieval.of(pipeline.postRetrieval()), Generation.of(pipeline.generation()));
    }

    public String resolvedName() {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("pipeline name is required");
        return name.trim();
    }

    public record PreRetrieval(
            @Nullable @JsonPropertyDescription("Rewrite the query for the search system, default false.")
            Boolean rewrite,
            @Nullable @JsonPropertyDescription("Rewrite prompt with {query} and {target}; null = Spring AI default.")
            String rewritePromptTemplate,
            @Nullable @JsonPropertyDescription("Name of the search system in the rewrite prompt, default "
                    + "'vector store'.") String rewriteTargetSearchSystem,
            @Nullable @JsonPropertyDescription("Compress the conversation history into a standalone query, "
                    + "default false.") Boolean compression,
            @Nullable String compressionPromptTemplate,
            @Nullable @JsonPropertyDescription("Translate the query, default false.") Boolean translation,
            @Nullable @JsonPropertyDescription("Target language of the translation, default english.")
            String translationTargetLanguage,
            @Nullable String translationPromptTemplate,
            @Nullable @JsonPropertyDescription("Expand the query into several variants, default false.")
            Boolean multiQuery,
            @Nullable @JsonPropertyDescription("Number of variants, default 3.") Integer multiQueryCount,
            @Nullable @JsonPropertyDescription("Keep the original query among the variants, default true.")
            Boolean multiQueryIncludeOriginal,
            @Nullable String multiQueryPromptTemplate) {

        public static PreRetrieval defaults() {
            return new PreRetrieval(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public static PreRetrieval of(PreRetrievalConfig config) {
            return new PreRetrieval(config.rewrite(), config.rewritePromptTemplate(),
                    config.rewriteTargetSearchSystem(), config.compression(), config.compressionPromptTemplate(),
                    config.translation(), config.translationTargetLanguage(), config.translationPromptTemplate(),
                    config.multiQuery(), config.multiQueryCount(), config.multiQueryIncludeOriginal(),
                    config.multiQueryPromptTemplate());
        }

        public PreRetrievalConfig toConfig() {
            PreRetrievalConfig base = PreRetrievalConfig.defaults();
            return new PreRetrievalConfig(orDefault(rewrite, base.rewrite()), blankToNull(rewritePromptTemplate),
                    orDefault(compression, base.compression()), blankToNull(compressionPromptTemplate),
                    orDefault(translation, base.translation()),
                    orDefault(blankToNull(translationTargetLanguage), base.translationTargetLanguage()),
                    blankToNull(translationPromptTemplate), orDefault(multiQuery, base.multiQuery()),
                    orDefault(multiQueryCount, base.multiQueryCount()),
                    orDefault(multiQueryIncludeOriginal, base.multiQueryIncludeOriginal()),
                    blankToNull(multiQueryPromptTemplate), blankToNull(rewriteTargetSearchSystem));
        }
    }

    public record Retrieval(
            @Nullable @JsonPropertyDescription("Additional metadata filter expression.") String extraFilterExpression,
            @Nullable @JsonPropertyDescription("Chunks returned per query; default the global vectorstore.top-k.")
            Integer topK,
            @Nullable @JsonPropertyDescription("Minimum cosine score; default the global "
                    + "vectorstore.similarity-threshold.") Double similarityThreshold) {

        public static Retrieval defaults() {
            return new Retrieval(null, null, null);
        }

        public static Retrieval of(RetrievalConfig config) {
            return new Retrieval(config.extraFilterExpression(), config.topK(), config.similarityThreshold());
        }

        public RetrievalConfig toConfig() {
            return new RetrievalConfig(blankToNull(extraFilterExpression), topK, similarityThreshold);
        }
    }

    public record PostRetrieval(
            @Nullable @JsonPropertyDescription("Order chunks by score, default true.") Boolean reRankByScore,
            @Nullable @JsonPropertyDescription("Keep only the first N chunks, default all.") Integer topNTruncate) {

        public static PostRetrieval defaults() {
            return new PostRetrieval(null, null);
        }

        public static PostRetrieval of(PostRetrievalConfig config) {
            return new PostRetrieval(config.reRankByScore(), config.topNTruncate());
        }

        public PostRetrievalConfig toConfig() {
            return new PostRetrievalConfig(orDefault(reRankByScore, PostRetrievalConfig.defaults().reRankByScore()),
                    topNTruncate);
        }
    }

    public record Generation(
            @Nullable @JsonPropertyDescription("Answer even when nothing was retrieved, default false.")
            Boolean allowEmptyContext,
            @Nullable @JsonPropertyDescription("Augmentation prompt with {context} and {query}; null = default.")
            String promptTemplate,
            @Nullable String emptyContextPromptTemplate,
            @Nullable @JsonPropertyDescription("Run the chat model on the augmented prompt in Pipeline Studio "
                    + "tests, default false.") Boolean runAugmentLlm,
            @Nullable @JsonPropertyDescription("TEXT or TEXT_WITH_SOURCE, default TEXT.")
            DocumentFormat documentFormat) {

        public static Generation defaults() {
            return new Generation(null, null, null, null, null);
        }

        public static Generation of(GenerationConfig config) {
            return new Generation(config.allowEmptyContext(), config.promptTemplate(),
                    config.emptyContextPromptTemplate(), config.runAugmentLLM(), config.documentFormat());
        }

        public GenerationConfig toConfig() {
            GenerationConfig base = GenerationConfig.defaults();
            return new GenerationConfig(orDefault(allowEmptyContext, base.allowEmptyContext()),
                    blankToNull(promptTemplate), blankToNull(emptyContextPromptTemplate),
                    orDefault(runAugmentLlm, base.runAugmentLLM()), orDefault(documentFormat, base.documentFormat()));
        }
    }

    private static <T> T orDefault(T value, T fallback) {
        return Objects.requireNonNullElse(value, fallback);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
