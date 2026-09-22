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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RagPipelineService implements PersistenceServiceInterface<RagPipeline> {
    public static final String DEFAULT_PIPELINE_NAME = "All documents (simple)";

    private static final Logger logger = LoggerFactory.getLogger(RagPipelineService.class);

    private final Path saveDir;
    private final PersistenceExecutor persistenceExecutor;
    private final ConcurrentMap<String, RagPipeline> pipelines = new ConcurrentHashMap<>();

    public RagPipelineService(Path springAiPlaygroundHomeDir, PersistenceExecutor persistenceExecutor)
            throws IOException {
        this.saveDir = springAiPlaygroundHomeDir.resolve("vectorstore").resolve("pipelines");
        Files.createDirectories(this.saveDir);
        this.persistenceExecutor = persistenceExecutor;
    }

    public List<RagPipeline> list() {
        return this.pipelines.values().stream()
                .sorted(Comparator.comparingLong(RagPipeline::updatedAt).reversed()).toList();
    }

    public Optional<RagPipeline> get(String id) {
        return Optional.ofNullable(this.pipelines.get(id));
    }

    public List<RagPipeline> referencing(Collection<String> docInfoIds) {
        return list().stream()
                .filter(pipeline -> pipeline.docInfoIds().stream().anyMatch(docInfoIds::contains))
                .toList();
    }

    public RagPipeline create(String name, String description, List<String> docInfoIds,
            RagPipeline.PreRetrievalConfig preRetrieval, RagPipeline.RetrievalConfig retrieval,
            RagPipeline.PostRetrievalConfig postRetrieval, RagPipeline.GenerationConfig generation) {
        long now = System.currentTimeMillis();
        RagPipeline pipeline = new RagPipeline(UUID.randomUUID().toString(), name, description, docInfoIds,
                preRetrieval, retrieval, postRetrieval, generation, now, now);
        this.pipelines.put(pipeline.id(), pipeline);
        saveAsync(pipeline);
        return pipeline;
    }

    public synchronized Optional<RagPipeline> ensureDefaultPipeline(Integer topK, Double similarityThreshold) {
        if (!this.pipelines.isEmpty()) return Optional.empty();
        RagPipeline created = create(DEFAULT_PIPELINE_NAME,
                "Retrieval-only pipeline over every knowledge-base document, created with the first embed.",
                List.of(), RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig(null, topK, similarityThreshold),
                RagPipeline.PostRetrievalConfig.defaults(), RagPipeline.GenerationConfig.defaults());
        logger.info("Created default RAG pipeline '{}'", created.name());
        return Optional.of(created);
    }

    public RagPipeline update(RagPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        long now = System.currentTimeMillis();
        RagPipeline updated = pipeline.withTimestamps(pipeline.createdAt(), now);
        this.pipelines.put(updated.id(), updated);
        saveAsync(updated);
        return updated;
    }

    public void deleteById(String id) {
        RagPipeline removed = this.pipelines.remove(id);
        if (removed != null)
            this.persistenceExecutor.submit(() -> delete(removed));
    }

    private void saveAsync(RagPipeline pipeline) {
        this.persistenceExecutor.submit(() -> {
            try {
                save(pipeline);
            } catch (IOException e) {
                logger.error("Failed to save RagPipeline {}", pipeline.id(), e);
            }
        });
    }

    @Override
    public Path getSaveDir() {
        return this.saveDir;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public String buildSaveFileName(RagPipeline pipeline) {
        return pipeline.id();
    }

    @Override
    public RagPipeline convertTo(Map<String, Object> map) {
        return OBJECT_MAPPER.convertValue(map, RagPipeline.class);
    }

    @Override
    public void onStart() throws IOException {
        loads().forEach(pipeline -> this.pipelines.put(pipeline.id(), pipeline));
        logger.info("Loaded {} RagPipeline(s) from {}", this.pipelines.size(), this.saveDir);
    }
}
