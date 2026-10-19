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
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService.IngestProgress;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService.ResolvedSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConfiguredKnowledgeLoader implements ApplicationListener<ApplicationReadyEvent> {

    public static final String DOCUMENTS_PROPERTY = "spring.ai.playground.rag.documents";
    public static final String PIPELINES_PROPERTY = "spring.ai.playground.rag.pipelines";

    private static final Logger logger = LoggerFactory.getLogger(ConfiguredKnowledgeLoader.class);

    private final List<DocumentSpec> documents;
    private final List<PipelineSpec> pipelines;
    private final KnowledgeIngestService ingestService;
    private final RagPipelineService pipelineService;
    private final Set<String> declaredTitles;
    private final Set<String> declaredPipelineNames;
    private final Map<String, String> problems = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();

    public ConfiguredKnowledgeLoader(SpringAiPlaygroundOptions playgroundOptions,
            KnowledgeIngestService ingestService, RagPipelineService pipelineService) {
        this.documents = playgroundOptions.rag().documents();
        this.pipelines = playgroundOptions.rag().pipelines();
        this.ingestService = ingestService;
        this.pipelineService = pipelineService;
        Set<String> titles = new LinkedHashSet<>();
        for (DocumentSpec spec : this.documents) {
            try {
                titles.add(ingestService.declaredTitle(spec));
            } catch (RuntimeException e) {
                logger.warn("Declared document without a usable title: {}", e.getMessage());
            }
        }
        this.declaredTitles = Set.copyOf(titles);
        Set<String> names = new LinkedHashSet<>();
        for (PipelineSpec spec : this.pipelines) {
            if (spec.name() != null && !spec.name().isBlank()) names.add(spec.name().trim());
        }
        this.declaredPipelineNames = Set.copyOf(names);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (this.documents.isEmpty() && this.pipelines.isEmpty()) {
            this.loaded.complete(null);
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                load();
            } finally {
                this.loaded.complete(null);
            }
        }, "knowledge-loader");
        loader.setDaemon(true);
        loader.start();
    }

    public CompletableFuture<Void> whenLoaded() {
        return this.loaded;
    }

    public void load() {
        this.documents.forEach(this::reconcileDocument);
        this.pipelines.forEach(this::reconcilePipeline);
    }

    public Map<String, String> problems() {
        return new LinkedHashMap<>(this.problems);
    }

    public boolean managesDocument(String title) {
        return title != null && this.declaredTitles.contains(title);
    }

    public boolean managesPipeline(String name) {
        return name != null && this.declaredPipelineNames.contains(name);
    }

    void reconcileDocument(DocumentSpec spec) {
        String key = problemKey(spec);
        this.problems.remove(key);
        try {
            String title = this.ingestService.declaredTitle(spec);
            ResolvedSource source = this.ingestService.resolve(spec.source());
            String fingerprint = KnowledgeIngestService.fingerprint(source.bytes(), spec);
            Optional<VectorStoreDocumentInfo> existing = this.ingestService.find(title);
            if (existing.isPresent() && fingerprint.equals(existing.get().sourceFingerprint())) {
                logger.info("Declared document '{}' is up to date", title);
                return;
            }
            existing.ifPresent(info -> {
                logger.info("Declared document '{}' changed since it was ingested; re-ingesting", title);
                this.ingestService.delete(info.docInfoId());
            });
            VectorStoreDocumentInfo info = this.ingestService.commit(
                    this.ingestService.prepare(spec, IngestProgress.NONE, source));
            logger.info("Loaded declared document '{}' from {} ({} chunks)", title, spec.source().describe(),
                    info.documentListSupplier().get().size());
        } catch (RuntimeException e) {
            logger.error("Declared document '{}' was not loaded: {}", key, e.getMessage());
            this.problems.put(key, "not loaded: " + e.getMessage());
        }
    }

    void reconcilePipeline(PipelineSpec spec) {
        String name = spec.name() == null || spec.name().isBlank() ? "pipeline" : spec.name().trim();
        this.problems.remove(name);
        try {
            RagPipeline applied = this.pipelineService.apply(spec);
            logger.info("Applied declared RAG pipeline '{}' ({})", applied.name(), applied.id());
        } catch (RuntimeException e) {
            logger.error("Declared RAG pipeline '{}' was not applied: {}", name, e.getMessage());
            this.problems.put(name, "not applied: " + e.getMessage());
        }
    }

    private String problemKey(DocumentSpec spec) {
        try {
            return this.ingestService.declaredTitle(spec);
        } catch (RuntimeException e) {
            return spec.title() != null && !spec.title().isBlank() ? spec.title().trim() : "document";
        }
    }

}
