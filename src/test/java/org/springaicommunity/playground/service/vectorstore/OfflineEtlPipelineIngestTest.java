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
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.EnrichOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.IngestRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfflineEtlPipelineIngestTest {

    @TempDir
    Path homeDir;

    private OfflineEtlPipelineService service;
    private VectorStoreService vectorStoreService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider = mock(ObjectProvider.class);
        when(persistenceProvider.getObject()).thenReturn(mock(VectorStoreDocumentPersistenceService.class));
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ObjectProvider<VectorStoreService> vectorStoreServiceProvider = mock(ObjectProvider.class);
        this.vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreServiceProvider.getObject()).thenReturn(this.vectorStoreService);
        this.service = new OfflineEtlPipelineService(this.homeDir, DataSize.ofMegabytes(20),
                new DefaultResourceLoader(), persistenceProvider, chatModelProvider, vectorStoreServiceProvider);
    }

    @Test
    void ingestCreatesOneDocumentPerFileWithChunksAndAddsTheVectors() {
        Map<String, List<Document>> chunksByFile = new LinkedHashMap<>();
        chunksByFile.put("a.txt", List.of(new Document("alpha one"), new Document("alpha two")));
        chunksByFile.put("empty.txt", List.of());
        chunksByFile.put("b.txt", List.of(new Document("bravo")));

        List<VectorStoreDocumentInfo> documents = this.service.ingest(
                new IngestRequest(List.of("a.txt", "empty.txt", "b.txt"), chunksByFile, " Batch ", "desc"));

        assertThat(documents).extracting(VectorStoreDocumentInfo::getDocumentFileName)
                .containsExactly("a.txt", "b.txt");
        assertThat(documents).extracting(VectorStoreDocumentInfo::title).containsOnly("Batch");
        assertThat(documents.getFirst().documentListSupplier().get()).hasSize(2)
                .allSatisfy(chunk -> assertThat(chunk.getMetadata())
                        .containsEntry(VectorStoreService.DOC_INFO_ID, documents.getFirst().docInfoId()));
        ArgumentCaptor<VectorStoreDocumentInfo> added = ArgumentCaptor.forClass(VectorStoreDocumentInfo.class);
        verify(this.vectorStoreService, times(2)).add(added.capture());
        assertThat(added.getAllValues()).containsExactlyElementsOf(documents);
        assertThat(this.service.getDocumentList()).hasSize(2);
    }

    @Test
    void aDocumentWhoseEmbeddingFailsIsNotLeftRegistered() {
        Map<String, List<Document>> chunksByFile = new LinkedHashMap<>();
        chunksByFile.put("a.txt", List.of(new Document("alpha one")));
        doThrow(new IllegalStateException("Connection refused: localhost/127.0.0.1:11434"))
                .when(this.vectorStoreService).add(any(VectorStoreDocumentInfo.class));

        assertThatThrownBy(() -> this.service.ingest(
                new IngestRequest(List.of("a.txt"), chunksByFile, "Handbook", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Connection refused");

        assertThat(this.service.getDocumentList()).isEmpty();
    }

    @Test
    void enrichByFileKeepsTheFileGroupingWhenNothingIsEnabled() {
        Map<String, List<Document>> chunksByFile = new LinkedHashMap<>();
        chunksByFile.put("a.txt", List.of(new Document("a1"), new Document("a2")));
        chunksByFile.put("b.txt", List.of(new Document("b1")));

        Map<String, List<Document>> enriched = this.service.enrichByFile(chunksByFile, EnrichOptions.NONE,
                (done, total) -> { }, () -> false);

        assertThat(enriched.keySet()).containsExactly("a.txt", "b.txt");
        assertThat(enriched.get("a.txt")).extracting(Document::getText).containsExactly("a1", "a2");
        assertThat(enriched.get("b.txt")).extracting(Document::getText).containsExactly("b1");
    }

    @Test
    void deleteDocumentRemovesTheVectorsAndTheInfo() {
        VectorStoreDocumentInfo info = this.service.loadDocument("c.txt", List.of(new Document("c1"),
                new Document("c2")));
        List<String> chunkIds = info.documentListSupplier().get().stream().map(Document::getId).toList();

        VectorStoreDocumentInfo deleted = this.service.deleteDocument(info.docInfoId());

        assertThat(deleted.docInfoId()).isEqualTo(info.docInfoId());
        verify(this.vectorStoreService).delete(chunkIds);
        assertThat(this.service.getDocumentList()).isEmpty();
        assertThatThrownBy(() -> this.service.deleteDocument("missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Document not found: missing");
        verify(this.vectorStoreService, never()).delete(List.of());
    }
}
