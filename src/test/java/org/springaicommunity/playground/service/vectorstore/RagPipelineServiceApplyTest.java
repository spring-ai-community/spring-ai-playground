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
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagPipelineServiceApplyTest {

    @TempDir
    Path homeDir;

    private PersistenceExecutor persistenceExecutor;
    private RagPipelineService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        OfflineEtlPipelineService documents = mock(OfflineEtlPipelineService.class);
        when(documents.getVisibleDocumentList()).thenReturn(List.of(
                new VectorStoreDocumentInfo("d1", "Handbook", 0L, 0L, "handbook.pdf", "/x/handbook.pdf", List::of),
                new VectorStoreDocumentInfo("d2", "Policies", 0L, 0L, "policies.md", "/x/policies.md", List::of)));
        ObjectProvider<OfflineEtlPipelineService> documentsProvider = mock(ObjectProvider.class);
        when(documentsProvider.getIfAvailable()).thenReturn(documents);
        this.persistenceExecutor = new PersistenceExecutor();
        this.service = new RagPipelineService(this.homeDir, this.persistenceExecutor, documentsProvider);
    }

    @AfterEach
    void tearDown() {
        this.persistenceExecutor.flushAndShutdown();
    }

    @Test
    void applyCreatesAPipelineAndResolvesDocumentTitlesToIds() {
        PipelineSpec spec = new PipelineSpec("Advanced RAG", "desc", List.of("Handbook", "d2", "Handbook"),
                new PipelineSpec.PreRetrieval(true, null, null, null, null, null, null, null, null, null, null, null),
                new PipelineSpec.Retrieval(null, 6, null), null, null);

        RagPipeline created = this.service.apply(spec);

        assertThat(created.name()).isEqualTo("Advanced RAG");
        assertThat(created.docInfoIds()).containsExactly("d1", "d2");
        assertThat(created.preRetrieval().rewrite()).isTrue();
        assertThat(created.preRetrieval().multiQueryCount()).isEqualTo(3);
        assertThat(created.retrieval().topK()).isEqualTo(6);
        assertThat(created.postRetrieval().reRankByScore()).isTrue();
        assertThat(this.service.findByName("Advanced RAG")).contains(created);
    }

    @Test
    void applyWithTheSameNameUpdatesInPlaceAndKeepsTheId() throws InterruptedException {
        RagPipeline created = this.service.apply(new PipelineSpec("KB", null, List.of(), null, null, null, null));
        Thread.sleep(2);

        RagPipeline updated = this.service.apply(new PipelineSpec("KB", "now scoped", List.of("Policies"),
                null, new PipelineSpec.Retrieval(null, 2, 0.4), null, null));

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isGreaterThan(created.updatedAt());
        assertThat(updated.docInfoIds()).containsExactly("d2");
        assertThat(updated.retrieval().similarityThreshold()).isEqualTo(0.4);
        assertThat(this.service.list()).hasSize(1);
    }

    @Test
    void applyWithAnExistingIdRenamesThatPipeline() {
        RagPipeline created = this.service.apply(new PipelineSpec("Old", null, List.of(), null, null, null, null));

        RagPipeline renamed = this.service.apply(new PipelineSpec("New", null, List.of(), null, null, null, null),
                created.id());

        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(this.service.findByName("Old")).isEmpty();
        assertThat(this.service.findByName("New")).contains(renamed);
    }

    @Test
    void unknownDocumentsAndBlankNamesAreRefused() {
        assertThatThrownBy(() -> this.service.apply(new PipelineSpec("X", null, List.of("Nope"), null, null, null,
                null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown document 'Nope'");
        assertThatThrownBy(() -> this.service.apply(new PipelineSpec(" ", null, List.of(), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThat(this.service.list()).isEmpty();
    }

    @Test
    void specOfReportsDocumentTitlesAndRoundTripsThroughApply() {
        RagPipeline created = this.service.apply(new PipelineSpec("KB", "d", List.of("d1"),
                new PipelineSpec.PreRetrieval(null, null, null, null, null, true, "korean", null, true, 5, false,
                        "variants {query}"),
                null, new PipelineSpec.PostRetrieval(false, 2), new PipelineSpec.Generation(true, null, null, true,
                RagPipeline.DocumentFormat.TEXT_WITH_SOURCE)));

        PipelineSpec spec = this.service.specOf(created);
        RagPipeline reapplied = this.service.apply(spec);

        assertThat(spec.documents()).containsExactly("Handbook");
        assertThat(reapplied.id()).isEqualTo(created.id());
        assertThat(reapplied.preRetrieval()).isEqualTo(created.preRetrieval());
        assertThat(reapplied.postRetrieval()).isEqualTo(created.postRetrieval());
        assertThat(reapplied.generation()).isEqualTo(created.generation());
        assertThat(reapplied.docInfoIds()).isEqualTo(created.docInfoIds());
    }

}
