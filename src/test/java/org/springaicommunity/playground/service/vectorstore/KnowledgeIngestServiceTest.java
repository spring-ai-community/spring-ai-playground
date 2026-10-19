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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService.IngestProgress;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestServiceTest {

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
    private KnowledgeIngestService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider = mock(ObjectProvider.class);
        when(persistenceProvider.getObject()).thenReturn(mock(VectorStoreDocumentPersistenceService.class));
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ObjectProvider<VectorStoreService> vectorStoreServiceProvider = mock(ObjectProvider.class);
        this.vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreServiceProvider.getObject()).thenReturn(this.vectorStoreService);
        this.etl = new OfflineEtlPipelineService(this.homeDir, DataSize.ofMegabytes(20), new DefaultResourceLoader(),
                persistenceProvider, chatModelProvider, vectorStoreServiceProvider);
        this.service = new KnowledgeIngestService(this.homeDir, this.etl, chatModelProvider,
                new PersistenceExecutor(), List.of(this.inbox.toString()));
    }

    @Test
    void inlineTextBecomesADocumentWithChunksAndAFingerprint() {
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.text(HANDBOOK), "Harbour notes", "ops", null,
                new DocumentSpec.Split(60, 20, 5, 100, true, null), null);

        VectorStoreDocumentInfo info = this.service.ingest(spec, IngestProgress.NONE);

        assertThat(info.title()).isEqualTo("Harbour notes");
        assertThat(info.description()).isEqualTo("ops");
        assertThat(info.documentListSupplier().get()).hasSizeGreaterThan(1);
        assertThat(info.sourceFingerprint()).hasSize(64);
        assertThat(info.getDocumentFileName()).endsWith(".txt");
        verify(this.vectorStoreService, times(1)).add(info);
        assertThat(this.etl.getVisibleDocumentList()).containsExactly(info);
    }

    @Test
    void ingestReportsReadingSplitAndEmbeddingStagesInOrder() {
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.text(HANDBOOK), "Harbour stages", null, null,
                new DocumentSpec.Split(60, 20, 5, 100, true, null), null);
        List<String> stages = new ArrayList<>();
        IngestProgress progress = new IngestProgress(null, null,
                (stage, chunks) -> stages.add(stage + ":" + chunks));

        VectorStoreDocumentInfo info = this.service.ingest(spec, progress);

        int chunks = info.documentListSupplier().get().size();
        assertThat(stages).containsExactly("READING:0", "SPLIT:" + chunks, "EMBEDDING:" + chunks);
        assertThat(new IngestProgress(null, null).stage()).isNotNull();
    }

    @Test
    void aFileInsideAnIngestRootIsIngestedUnderItsFileName() throws IOException {
        Path file = this.inbox.resolve("handbook.txt");
        Files.writeString(file, HANDBOOK, StandardCharsets.UTF_8);

        VectorStoreDocumentInfo info = this.service.ingest(
                new DocumentSpec(DocumentSpec.Source.path(file.toString()), null, null, null, null, null),
                IngestProgress.NONE);

        assertThat(info.title()).isEqualTo("handbook.txt");
        assertThat(this.etl.buildUploadFilePath("handbook.txt")).exists();
        assertThat(this.service.find("handbook.txt")).contains(info);
        assertThat(this.service.find(info.docInfoId())).contains(info);
    }

    @Test
    void aPathOutsideEveryIngestRootIsRefusedBeforeAnythingIsStaged() throws IOException {
        Path file = this.elsewhere.resolve("secret.txt");
        Files.writeString(file, HANDBOOK, StandardCharsets.UTF_8);
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.path(file.toString()), null, null, null, null, null);

        assertThatThrownBy(() -> this.service.ingest(spec, IngestProgress.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the ingest roots")
                .hasMessageContaining(KnowledgeIngestService.INGEST_ROOTS_PROPERTY);
        assertThat(this.etl.buildUploadFilePath("secret.txt")).doesNotExist();
        assertThat(this.etl.getVisibleDocumentList()).isEmpty();
    }

    @Test
    void aRelativePathIsRefusedWithTheAbsolutePathToUseInstead() throws IOException {
        Files.writeString(this.inbox.resolve("handbook.txt"), HANDBOOK, StandardCharsets.UTF_8);
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.path("handbook.txt"), null, null, null, null, null);

        assertThatThrownBy(() -> this.service.ingest(spec, IngestProgress.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'handbook.txt' is a relative path")
                .hasMessageContaining(this.inbox.toAbsolutePath().normalize().resolve("handbook.txt").toString());
        assertThat(this.etl.getVisibleDocumentList()).isEmpty();
    }

    @Test
    void aMissingFileInsideAnIngestRootIsNamedAsMissing() {
        String missing = this.inbox.resolve("ingest").resolve("handbook.txt").toString();
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.path(missing), null, null, null, null, null);

        assertThatThrownBy(() -> this.service.ingest(spec, IngestProgress.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + missing + "' does not exist; the ingest roots are");
        assertThat(this.etl.getVisibleDocumentList()).isEmpty();
    }

    @Test
    void enrichmentWithoutAChatModelFailsBeforeTheFileIsTouched() throws IOException {
        Path file = this.inbox.resolve("handbook.txt");
        Files.writeString(file, HANDBOOK, StandardCharsets.UTF_8);
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.path(file.toString()), null, null, null, null,
                new DocumentSpec.Enrich(true, null, null, null, null));

        assertThatThrownBy(() -> this.service.prepare(spec, IngestProgress.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chat model");
        assertThat(this.etl.buildUploadFilePath("handbook.txt")).doesNotExist();
    }

    @Test
    void aFileNameAlreadyIndexedUnderAnotherDocumentIsRefused() throws IOException {
        Path file = this.inbox.resolve("handbook.txt");
        Files.writeString(file, HANDBOOK, StandardCharsets.UTF_8);
        DocumentSpec first = new DocumentSpec(DocumentSpec.Source.path(file.toString()), "First", null, null, null,
                null);
        this.service.ingest(first, IngestProgress.NONE);

        assertThatThrownBy(() -> this.service.ingest(first, IngestProgress.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already indexed as 'First'");

        this.service.delete("First");
        assertThat(this.etl.getVisibleDocumentList()).isEmpty();
        assertThatThrownBy(() -> this.service.delete("First")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFingerprintFollowsSourceBytesAndProcessingOptionsOnly() {
        byte[] bytes = HANDBOOK.getBytes(StandardCharsets.UTF_8);
        DocumentSpec base = new DocumentSpec(DocumentSpec.Source.text(HANDBOOK), "A", "x", null, null, null);
        DocumentSpec renamed = new DocumentSpec(DocumentSpec.Source.text(HANDBOOK), "B", "y", null, null, null);
        DocumentSpec resplit = new DocumentSpec(DocumentSpec.Source.text(HANDBOOK), "A", "x", null,
                new DocumentSpec.Split(600, null, null, null, null, null), null);

        String fingerprint = KnowledgeIngestService.fingerprint(bytes, base);

        assertThat(fingerprint).isEqualTo(KnowledgeIngestService.fingerprint(bytes, renamed));
        assertThat(fingerprint).isNotEqualTo(KnowledgeIngestService.fingerprint(bytes, resplit));
        assertThat(fingerprint).isNotEqualTo(KnowledgeIngestService.fingerprint("other".getBytes(), base));
        assertThat(this.service.fingerprint(base)).isEqualTo(fingerprint);
    }

    @Test
    void theDefaultIngestRootIsTheInboxUnderTheHome() throws IOException {
        KnowledgeIngestService defaults = new KnowledgeIngestService(this.homeDir, this.etl,
                mock(ObjectProvider.class), new PersistenceExecutor(), List.of());

        assertThat(defaults.ingestRoots()).containsExactly(
                this.homeDir.resolve("vectorstore").resolve("inbox").toAbsolutePath().normalize());
        assertThat(defaults.ingestRoots().getFirst()).isDirectory();
        assertThat(KnowledgeIngestService.expandHome("~/inbox"))
                .isEqualTo(Path.of(System.getProperty("user.home")).resolve("inbox"));
    }

}
