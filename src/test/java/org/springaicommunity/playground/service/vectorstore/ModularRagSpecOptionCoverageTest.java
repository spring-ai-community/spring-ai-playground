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
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-enforced audit that every configurable option Spring AI's Modular RAG components expose is
 * accounted for in our pipeline model: either mapped to a RagPipeline config field (and therefore
 * to the wizard UI) or explicitly runtime-only wiring the executor owns.
 *
 * The option surface is discovered by reflection over each component's Builder, so this test FAILS
 * when a Spring AI upgrade adds or removes an option - forcing a deliberate decision instead of a
 * silent feature gap like the one targetSearchSystem/documentFormatter sat in before 2026-08-05.
 */
class ModularRagSpecOptionCoverageTest {

    private static final String RUNTIME_ONLY = "<runtime>";

    private static final Map<Class<?>, Map<String, String>> SPEC_OPTION_MAPPING = Map.of(
            RewriteQueryTransformer.Builder.class, Map.of(
                    "chatClientBuilder", RUNTIME_ONLY,
                    "promptTemplate", "preRetrieval.rewritePromptTemplate",
                    "targetSearchSystem", "preRetrieval.rewriteTargetSearchSystem"),
            CompressionQueryTransformer.Builder.class, Map.of(
                    "chatClientBuilder", RUNTIME_ONLY,
                    "promptTemplate", "preRetrieval.compressionPromptTemplate"),
            TranslationQueryTransformer.Builder.class, Map.of(
                    "chatClientBuilder", RUNTIME_ONLY,
                    "promptTemplate", "preRetrieval.translationPromptTemplate",
                    "targetLanguage", "preRetrieval.translationTargetLanguage"),
            MultiQueryExpander.Builder.class, Map.of(
                    "chatClientBuilder", RUNTIME_ONLY,
                    "promptTemplate", "preRetrieval.multiQueryPromptTemplate",
                    "numberOfQueries", "preRetrieval.multiQueryCount",
                    "includeOriginal", "preRetrieval.multiQueryIncludeOriginal"),
            VectorStoreDocumentRetriever.Builder.class, Map.of(
                    "vectorStore", RUNTIME_ONLY,
                    "topK", "retrieval.topK",
                    "similarityThreshold", "retrieval.similarityThreshold",
                    "filterExpression", "retrieval.extraFilterExpression"),
            ContextualQueryAugmenter.Builder.class, Map.of(
                    "promptTemplate", "generation.promptTemplate",
                    "emptyContextPromptTemplate", "generation.emptyContextPromptTemplate",
                    "allowEmptyContext", "generation.allowEmptyContext",
                    "documentFormatter", "generation.documentFormat"));

    private static final Map<String, Class<?>> CONFIG_RECORDS = Map.of(
            "preRetrieval", RagPipeline.PreRetrievalConfig.class,
            "retrieval", RagPipeline.RetrievalConfig.class,
            "postRetrieval", RagPipeline.PostRetrievalConfig.class,
            "generation", RagPipeline.GenerationConfig.class);

    private static Set<String> builderOptionNames(Class<?> builder) {
        return Arrays.stream(builder.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                .filter(method -> method.getReturnType().equals(builder))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void everySpecOptionIsMappedToAPipelineFieldOrExplicitlyRuntimeOnly() {
        SPEC_OPTION_MAPPING.forEach((builder, mapping) ->
                assertThat(builderOptionNames(builder))
                        .as("Option surface of %s changed - map new options into RagPipeline (and the wizard) "
                                + "or declare them runtime-only here", builder.getName())
                        .containsExactlyInAnyOrderElementsOf(mapping.keySet()));
    }

    @Test
    void everyMappedOptionPointsToARealPipelineConfigField() {
        SPEC_OPTION_MAPPING.values().stream().flatMap(mapping -> mapping.values().stream())
                .filter(target -> !RUNTIME_ONLY.equals(target)).distinct().forEach(target -> {
                    String[] parts = target.split("\\.");
                    Class<?> record = CONFIG_RECORDS.get(parts[0]);
                    assertThat(record).as("Unknown config record in mapping: %s", target).isNotNull();
                    assertThat(Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName))
                            .as("%s must have a component named %s", record.getSimpleName(), parts[1])
                            .contains(parts[1]);
                });
    }

    @Test
    void ourOwnPostRetrievalOptionsStayDeclaredOnTheConfig() {
        assertThat(Arrays.stream(RagPipeline.PostRetrievalConfig.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactlyInAnyOrder("reRankByScore", "topNTruncate");
    }
}
