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
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfiguredKnowledgeLoaderTest {

    private static final String HANDBOOK = "The dry dock is out of service for annual survey every February. "
            + "Berth allocation follows the harbour master's weekly plan. ".repeat(16);

    @TempDir
    Path homeDir;
    @TempDir
    Path inbox;
    @TempDir
    Path elsewhere;

    private OfflineEtlPipelineService etl;
    private VectorStoreService vectorStoreService;
    private PersistenceExecutor persistenceExecutor;
    private RagPipelineService pipelineService;
    private ObjectProvider<ChatModel> chatModelProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider = mock(ObjectProvider.class);
        when(persistenceProvider.getObject()).thenReturn(mock(VectorStoreDocumentPersistenceService.class));
        this.chatModelProvider = mock(ObjectProvider.class);
        ObjectProvider<VectorStoreService> vectorStoreServiceProvider = mock(ObjectProvider.class);
        this.vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreServiceProvider.getObject()).thenReturn(this.vectorStoreService);
        this.etl = new OfflineEtlPipelineService(this.homeDir, DataSize.ofMegabytes(20), new DefaultResourceLoader(),
                persistenceProvider, this.chatModelProvider, vectorStoreServiceProvider);
        ObjectProvider<OfflineEtlPipelineService> documentsProvider = mock(ObjectProvider.class);
        when(documentsProvider.getIfAvailable()).thenReturn(this.etl);
        this.persistenceExecutor = new PersistenceExecutor();
        this.pipelineService = new RagPipelineService(this.homeDir, this.persistenceExecutor, documentsProvider);
        Files.writeString(this.inbox.resolve("handbook.txt"), HANDBOOK, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        this.persistenceExecutor.flushAndShutdown();
    }

    private ConfiguredKnowledgeLoader loader(Map<String, Object> extraProperties) throws IOException {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.ai.playground.rag.ingest-roots[0]", this.inbox.toString(),
                "spring.ai.playground.rag.documents[0].source.path", this.inbox.resolve("handbook.txt").toString(),
                "spring.ai.playground.rag.documents[0].title", "Handbook",
                "spring.ai.playground.rag.documents[0].split.chunk-size", "60",
                "spring.ai.playground.rag.documents[0].split.min-chunk-size-chars", "20",
                "spring.ai.playground.rag.pipelines[0].name", "KB",
                "spring.ai.playground.rag.pipelines[0].documents[0]", "Handbook",
                "spring.ai.playground.rag.pipelines[0].retrieval.top-k", "3"));
        properties.putAll(extraProperties);
        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);
        KnowledgeIngestService ingestService = new KnowledgeIngestService(this.homeDir, this.etl,
                this.chatModelProvider, this.persistenceExecutor, options.rag().ingestRoots());
        return new ConfiguredKnowledgeLoader(options, ingestService, this.pipelineService);
    }

    @Test
    void theFirstLoadIngestsDeclaredDocumentsAndAppliesPipelinesOverThem() throws IOException {
        ConfiguredKnowledgeLoader loader = loader(Map.of());

        loader.load();

        VectorStoreDocumentInfo handbook = this.etl.getVisibleDocumentList().getFirst();
        assertThat(handbook.title()).isEqualTo("Handbook");
        assertThat(handbook.sourceFingerprint()).hasSize(64);
        assertThat(handbook.documentListSupplier().get()).hasSizeGreaterThan(1);
        verify(this.vectorStoreService, times(1)).add(any(VectorStoreDocumentInfo.class));
        RagPipeline kb = this.pipelineService.findByName("KB").orElseThrow();
        assertThat(kb.docInfoIds()).containsExactly(handbook.docInfoId());
        assertThat(kb.retrieval().topK()).isEqualTo(3);
        assertThat(loader.problems()).isEmpty();
        assertThat(loader.managesDocument("Handbook")).isTrue();
        assertThat(loader.managesDocument("Other")).isFalse();
        assertThat(loader.managesPipeline("KB")).isTrue();
        assertThat(loader.managesPipeline("Mine")).isFalse();
    }

    @Test
    void anUnchangedDocumentIsLeftAloneOnTheNextStart() throws IOException {
        ConfiguredKnowledgeLoader loader = loader(Map.of());
        loader.load();
        String docInfoId = this.etl.getVisibleDocumentList().getFirst().docInfoId();

        loader(Map.of()).load();

        verify(this.vectorStoreService, times(1)).add(any(VectorStoreDocumentInfo.class));
        verify(this.vectorStoreService, never()).delete(anyList());
        assertThat(this.etl.getVisibleDocumentList()).hasSize(1);
        assertThat(this.etl.getVisibleDocumentList().getFirst().docInfoId()).isEqualTo(docInfoId);
        assertThat(this.pipelineService.list()).hasSize(1);
    }

    @Test
    void aChangedSourceOrOptionIsDeletedAndIngestedAgain() throws IOException {
        loader(Map.of()).load();
        String firstId = this.etl.getVisibleDocumentList().getFirst().docInfoId();
        String firstFingerprint = this.etl.getVisibleDocumentList().getFirst().sourceFingerprint();
        Files.writeString(this.inbox.resolve("handbook.txt"), HANDBOOK + " Amended in March.", StandardCharsets.UTF_8);

        loader(Map.of()).load();

        verify(this.vectorStoreService, times(1)).delete(anyList());
        verify(this.vectorStoreService, times(2)).add(any(VectorStoreDocumentInfo.class));
        VectorStoreDocumentInfo reloaded = this.etl.getVisibleDocumentList().getFirst();
        assertThat(this.etl.getVisibleDocumentList()).hasSize(1);
        assertThat(reloaded.docInfoId()).isNotEqualTo(firstId);
        assertThat(reloaded.sourceFingerprint()).isNotEqualTo(firstFingerprint);

        loader(Map.of("spring.ai.playground.rag.documents[0].split.chunk-size", "120")).load();

        verify(this.vectorStoreService, times(3)).add(any(VectorStoreDocumentInfo.class));
        assertThat(this.etl.getVisibleDocumentList()).hasSize(1);
    }

    @Test
    void aFailingDeclarationIsRecordedAndTheOthersStillLoad() throws IOException {
        Files.writeString(this.elsewhere.resolve("secret.txt"), "classified", StandardCharsets.UTF_8);
        ConfiguredKnowledgeLoader loader = loader(Map.of(
                "spring.ai.playground.rag.documents[1].source.path", this.elsewhere.resolve("secret.txt").toString(),
                "spring.ai.playground.rag.documents[2].source.text", "no title here",
                "spring.ai.playground.rag.pipelines[1].name", "Broken",
                "spring.ai.playground.rag.pipelines[1].documents[0]", "Missing doc"));

        loader.load();

        assertThat(this.etl.getVisibleDocumentList()).extracting(VectorStoreDocumentInfo::title)
                .containsExactly("Handbook");
        assertThat(loader.problems()).containsKeys("secret.txt", "document", "Broken");
        assertThat(loader.problems().get("secret.txt")).contains("outside the ingest roots");
        assertThat(loader.problems().get("document")).contains("title is required");
        assertThat(loader.problems().get("Broken")).contains("unknown document 'Missing doc'");
        assertThat(this.pipelineService.findByName("KB")).isPresent();
        assertThat(this.pipelineService.findByName("Broken")).isEmpty();
    }

    @Test
    void theReadySignalCompletesAfterTheBackgroundLoad() throws Exception {
        ConfiguredKnowledgeLoader loader = loader(Map.of());

        loader.onApplicationEvent(mock(ApplicationReadyEvent.class));
        loader.whenLoaded().get(30, TimeUnit.SECONDS);

        assertThat(this.etl.getVisibleDocumentList()).extracting(VectorStoreDocumentInfo::title)
                .containsExactly("Handbook");
    }

    @Test
    void nothingDeclaredCompletesImmediately() {
        SpringAiPlaygroundOptions options = new SpringAiPlaygroundOptions(null, false, null, null, null, null, null,
                null, null, null);
        ConfiguredKnowledgeLoader loader = new ConfiguredKnowledgeLoader(options,
                mock(KnowledgeIngestService.class), this.pipelineService);

        loader.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertThat(loader.whenLoaded()).isDone();
        assertThat(loader.managesDocument("Handbook")).isFalse();
        assertThat(List.copyOf(loader.problems().keySet())).isEmpty();
    }

}
