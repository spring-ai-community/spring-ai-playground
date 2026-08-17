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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripsThroughJson() throws Exception {
        RagPipeline original = new RagPipeline("id-1", "legal-kr", "test desc", List.of("doc1", "doc2"),
                new RagPipeline.PreRetrievalConfig(
                        true, "Rewrite: {target} {query}",
                        true, "Compress: {history} {query}",
                        true, "korean", "Translate to {targetLanguage}: {query}",
                        true, 5, false, "MultiQ: {number} {query}"),
                new RagPipeline.RetrievalConfig("country == 'KR'"),
                new RagPipeline.PostRetrievalConfig(true, 5),
                new RagPipeline.GenerationConfig(true, "Augment: {context} {query}", "Empty fallback", true),
                1700000000000L, 1700000001000L);
        String json = MAPPER.writeValueAsString(original);
        RagPipeline back = MAPPER.readValue(json, RagPipeline.class);
        assertEquals(original, back);
        assertEquals("Rewrite: {target} {query}", back.preRetrieval().rewritePromptTemplate());
        assertEquals("Compress: {history} {query}", back.preRetrieval().compressionPromptTemplate());
        assertEquals("Translate to {targetLanguage}: {query}", back.preRetrieval().translationPromptTemplate());
        assertEquals("MultiQ: {number} {query}", back.preRetrieval().multiQueryPromptTemplate());
        assertEquals("Empty fallback", back.generation().emptyContextPromptTemplate());
    }

    @Test
    void defaultsAreNonNull() {
        assertNotNull(RagPipeline.PreRetrievalConfig.defaults());
        assertNotNull(RagPipeline.RetrievalConfig.defaults());
        assertNotNull(RagPipeline.PostRetrievalConfig.defaults());
        assertNotNull(RagPipeline.GenerationConfig.defaults());
    }

    @Test
    void canonicalConstructorReplacesNullSubConfigsWithDefaults() {
        RagPipeline pipeline = new RagPipeline("id", "n", null, null, null, null, null, null, 0L, 0L);
        assertEquals(RagPipeline.PreRetrievalConfig.defaults(), pipeline.preRetrieval());
        assertEquals(RagPipeline.RetrievalConfig.defaults(), pipeline.retrieval());
        assertEquals(RagPipeline.PostRetrievalConfig.defaults(), pipeline.postRetrieval());
        assertEquals(RagPipeline.GenerationConfig.defaults(), pipeline.generation());
        assertEquals(List.of(), pipeline.docInfoIds());
    }

    @Test
    void canonicalConstructorRequiresIdAndName() {
        assertThrows(NullPointerException.class, () -> new RagPipeline(null, "n", null, null, null, null, null, null,
                0L, 0L));
        assertThrows(NullPointerException.class, () -> new RagPipeline("id", null, null, null, null, null, null, null,
                0L, 0L));
    }

    @Test
    void docInfoIdsListIsImmutableCopy() {
        List<String> input = new ArrayList<>(List.of("a", "b"));
        RagPipeline pipeline = new RagPipeline("id", "n", null, input, null, null, null, null, 0L, 0L);
        input.add("c");
        assertEquals(List.of("a", "b"), pipeline.docInfoIds());
        assertThrows(UnsupportedOperationException.class, () -> pipeline.docInfoIds().add("d"));
    }

    @Test
    void renameProducesNewInstanceWithUpdatedNameAndTimestamp() {
        RagPipeline pipeline = new RagPipeline("id", "old", null, List.of(), null, null, null, null, 100L, 100L);
        RagPipeline renamed = pipeline.rename("new", 200L);
        assertEquals("new", renamed.name());
        assertEquals(200L, renamed.updatedAt());
        assertEquals(100L, renamed.createdAt());
        assertEquals("old", pipeline.name());
    }

    @Test
    void withTimestampsKeepsOtherFields() {
        RagPipeline pipeline = new RagPipeline("id", "n", "d", List.of("doc1"), null, null, null, null, 100L, 100L);
        RagPipeline updated = pipeline.withTimestamps(50L, 300L);
        assertEquals(50L, updated.createdAt());
        assertEquals(300L, updated.updatedAt());
        assertEquals(pipeline.name(), updated.name());
        assertEquals(pipeline.docInfoIds(), updated.docInfoIds());
    }

    @Test
    void roundTripPreservesNullableFields() throws Exception {
        RagPipeline pipeline = new RagPipeline("id", "n", null, List.of(),
                new RagPipeline.PreRetrievalConfig(false, null, false, null, false, null, null, false, 3, true, null),
                new RagPipeline.RetrievalConfig(null),
                new RagPipeline.PostRetrievalConfig(false, null),
                new RagPipeline.GenerationConfig(false, null, null, false), 0L, 0L);
        String json = MAPPER.writeValueAsString(pipeline);
        RagPipeline back = MAPPER.readValue(json, RagPipeline.class);
        assertEquals(pipeline, back);
        assertTrue(back.docInfoIds().isEmpty());
    }

    @Test
    void legacyRetrievalConfigJsonWithoutSearchSettingsDeserializesToNulls() throws Exception {
        RagPipeline.RetrievalConfig back = MAPPER.readValue("{\"extraFilterExpression\":\"country == 'KR'\"}",
                RagPipeline.RetrievalConfig.class);
        assertEquals("country == 'KR'", back.extraFilterExpression());
        assertEquals(null, back.topK());
        assertEquals(null, back.similarityThreshold());
    }

    @Test
    void stageLabelsReflectEnabledStages() {
        RagPipeline simple = new RagPipeline("id", "n", null, List.of(), null, null, null, null, 0L, 0L);
        assertEquals(List.of("Retrieve", "Re-rank", "Augment"), simple.stageLabels());
        RagPipeline full = new RagPipeline("id", "n", null, List.of(),
                new RagPipeline.PreRetrievalConfig(true, null, true, null, true, "english", null, true, 3, true, null),
                null, new RagPipeline.PostRetrievalConfig(true, 5), null, 0L, 0L);
        assertEquals(List.of("Rewrite", "Compress", "Translate", "Multi-Q×3", "Retrieve", "Re-rank", "Top-5",
                "Augment"), full.stageLabels());
    }

    @Test
    void extraLlmCallCountCountsEnabledPreRetrievalStages() {
        RagPipeline simple = new RagPipeline("id", "n", null, List.of(), null, null, null, null, 0L, 0L);
        assertEquals(0, simple.extraLlmCallCount());
        RagPipeline allPre = new RagPipeline("id", "n", null, List.of(),
                new RagPipeline.PreRetrievalConfig(true, null, true, null, true, "english", null, true, 3, true, null),
                null, null, null, 0L, 0L);
        assertEquals(4, allPre.extraLlmCallCount());
    }
}
