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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.PersistenceExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineServiceTest {

    private Path tempHome;
    private PersistenceExecutor persistenceExecutor;
    private RagPipelineService service;

    @BeforeEach
    void setUp() throws IOException {
        this.tempHome = Files.createTempDirectory("rag-pipeline-svc-test-");
        this.persistenceExecutor = new PersistenceExecutor();
        this.service = new RagPipelineService(this.tempHome, this.persistenceExecutor);
    }

    @AfterEach
    void tearDown() throws IOException {
        this.persistenceExecutor.flushAndShutdown();
        deleteRecursively(this.tempHome);
    }

    @Test
    void ensureDefaultPipelineCreatesRetrievalOnlyPipelineWithSnapshottedSettings() {
        RagPipeline created = this.service.ensureDefaultPipeline(4, 0.6).orElseThrow();

        assertEquals(RagPipelineService.DEFAULT_PIPELINE_NAME, created.name());
        assertTrue(created.docInfoIds().isEmpty());
        assertEquals(RagPipeline.PreRetrievalConfig.defaults(), created.preRetrieval());
        assertEquals(0, created.extraLlmCallCount());
        assertEquals(4, created.retrieval().topK());
        assertEquals(0.6, created.retrieval().similarityThreshold());
        assertEquals(1, this.service.list().size());
    }

    @Test
    void ensureDefaultPipelineIsNoOpWhenPipelinesExist() {
        this.service.create("custom", null, List.of(), null, null, null, null);

        assertTrue(this.service.ensureDefaultPipeline(4, 0.6).isEmpty());
        assertEquals(1, this.service.list().size());
    }

    @Test
    void createPersistsAndAppearsInList() throws Exception {
        RagPipeline created = this.service.create("legal-kr", "desc", List.of("doc1"), null, null, null, null);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));

        assertTrue(this.service.list().stream().anyMatch(p -> p.id().equals(created.id())));
        assertTrue(Files.exists(pipelineFile(created.id())));
    }

    @Test
    void onStartLoadsExistingPipelines() throws Exception {
        RagPipeline created = this.service.create("legal-kr", null, List.of(), null, null, null, null);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));

        RagPipelineService secondService = new RagPipelineService(this.tempHome, this.persistenceExecutor);
        secondService.onStart();

        assertEquals(1, secondService.list().size());
        assertEquals(created.id(), secondService.list().getFirst().id());
        assertEquals(created, secondService.get(created.id()).orElseThrow());
    }

    @Test
    void deleteByIdRemovesFile() throws Exception {
        RagPipeline created = this.service.create("temp", null, List.of(), null, null, null, null);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));
        Path file = pipelineFile(created.id());
        assertTrue(Files.exists(file));

        this.service.deleteById(created.id());
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));

        assertFalse(this.service.list().stream().anyMatch(p -> p.id().equals(created.id())));
        assertFalse(Files.exists(file));
    }

    @Test
    void updateBumpsUpdatedAtAndPersists() throws Exception {
        RagPipeline created = this.service.create("init", null, List.of(), null, null, null, null);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));
        Thread.sleep(2);

        RagPipeline renamed = created.rename("renamed", System.currentTimeMillis());
        RagPipeline saved = this.service.update(renamed);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));

        assertEquals("renamed", saved.name());
        assertTrue(saved.updatedAt() >= renamed.updatedAt());
        assertEquals(created.createdAt(), saved.createdAt());

        RagPipelineService secondService = new RagPipelineService(this.tempHome, this.persistenceExecutor);
        secondService.onStart();
        assertEquals("renamed", secondService.get(created.id()).orElseThrow().name());
    }

    @Test
    void listSortsByUpdatedAtDescending() throws Exception {
        RagPipeline a = this.service.create("a", null, List.of(), null, null, null, null);
        Thread.sleep(2);
        RagPipeline b = this.service.create("b", null, List.of(), null, null, null, null);
        Thread.sleep(2);
        RagPipeline c = this.service.create("c", null, List.of(), null, null, null, null);
        this.persistenceExecutor.awaitCompletion(Duration.ofSeconds(2));

        List<String> names = this.service.list().stream().map(RagPipeline::name).toList();
        assertEquals(List.of("c", "b", "a"), names);

        List<Long> updates = this.service.list().stream().map(RagPipeline::updatedAt).toList();
        List<Long> sortedDesc = updates.stream().sorted(Comparator.reverseOrder()).toList();
        assertEquals(sortedDesc, updates);

        assertEquals(List.of(a.id(), b.id(), c.id()).size(), 3);
    }

    private Path pipelineFile(String id) {
        return this.tempHome.resolve("vectorstore").resolve("pipelines").resolve(id + ".json");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
