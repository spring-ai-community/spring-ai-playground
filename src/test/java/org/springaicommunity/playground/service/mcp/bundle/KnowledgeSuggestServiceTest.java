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
package org.springaicommunity.playground.service.mcp.bundle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeContribution.Source;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService.Reason;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService.Suggestion;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSuggestServiceTest {

    private final KnowledgeContribution knowledgeContribution = mock(KnowledgeContribution.class);
    private final RagPipelineService ragPipelineService = mock(RagPipelineService.class);
    private final OfflineEtlPipelineService documents = mock(OfflineEtlPipelineService.class);
    private KnowledgeSuggestService service;

    private final VectorStoreDocumentInfo faq = document("d-faq", "Product FAQ");
    private final VectorStoreDocumentInfo handbook = document("d-handbook", "Handbook");
    private final VectorStoreDocumentInfo notes = document("d-notes", "Release notes");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OfflineEtlPipelineService> documentsProvider = mock(ObjectProvider.class);
        when(documentsProvider.getIfAvailable()).thenReturn(this.documents);
        when(this.documents.getVisibleDocumentList()).thenReturn(List.of(this.faq, this.handbook, this.notes));
        when(this.knowledgeContribution.resolve(anyString())).thenReturn(Optional.empty());
        this.service = new KnowledgeSuggestService(this.knowledgeContribution, this.ragPipelineService,
                documentsProvider);
    }

    @Test
    void scopedPipelinesBecomeToolsAndDocumentsOutsideThemBecomeTheirOwnTools() {
        RagPipeline faqPipeline = pipeline("p-faq", "Product FAQ", List.of("d-faq"), true);
        stubPipeline(faqPipeline, List.of(this.faq));
        stubDocument(this.handbook);
        stubDocument(this.notes);
        when(this.ragPipelineService.list()).thenReturn(List.of(faqPipeline));

        List<Suggestion> suggestions = this.service.suggest(List.of(), List.of());

        assertThat(suggestions).extracting(suggestion -> suggestion.knowledge().toolName())
                .containsExactly("search_product_faq", "search_handbook", "search_release_notes");
        assertThat(suggestions).extracting(Suggestion::reason)
                .containsExactly(Reason.PIPELINE, Reason.UNCOVERED_DOCUMENT, Reason.UNCOVERED_DOCUMENT);
        assertThat(suggestions.getFirst().llmStages()).containsExactly("Rewrite");
        assertThat(suggestions.getFirst().documents()).isEqualTo(1);
        assertThat(suggestions.getFirst().knowledge().description()).startsWith("Search the Product FAQ knowledge base");
        assertThat(suggestions.getFirst().knowledge().topK()).isNull();
        assertThat(suggestions.get(1).knowledge().sourceId()).isEqualTo("doc:d-handbook");
    }

    @Test
    void anAllDocumentsPipelineStandsInForTheUncoveredDocuments() {
        RagPipeline faqPipeline = pipeline("p-faq", "Product FAQ", List.of("d-faq"), false);
        RagPipeline everything = pipeline("p-all", "All documents (simple)", List.of(), false);
        stubPipeline(faqPipeline, List.of(this.faq));
        stubPipeline(everything, List.of(this.faq, this.handbook, this.notes));
        when(this.ragPipelineService.list()).thenReturn(List.of(everything, faqPipeline));

        List<Suggestion> suggestions = this.service.suggest(List.of(), List.of());

        assertThat(suggestions).extracting(suggestion -> suggestion.knowledge().toolName())
                .containsExactly("search_product_faq", "search_all_documents_simple");
        assertThat(suggestions.get(1).reason()).isEqualTo(Reason.ALL_DOCUMENTS_PIPELINE);
        assertThat(suggestions.get(1).documents()).isEqualTo(3);
    }

    @Test
    void anAllDocumentsPipelineIsNotSuggestedWhenScopedPipelinesAlreadyCoverEveryDocument() {
        RagPipeline scoped = pipeline("p-scoped", "Everything scoped", List.of("d-faq", "d-handbook", "d-notes"),
                false);
        RagPipeline everything = pipeline("p-all", "All documents (simple)", List.of(), false);
        stubPipeline(scoped, List.of(this.faq, this.handbook, this.notes));
        stubPipeline(everything, List.of(this.faq, this.handbook, this.notes));
        when(this.ragPipelineService.list()).thenReturn(List.of(everything, scoped));

        assertThat(this.service.suggest(List.of(), List.of())).extracting(Suggestion::sourceName)
                .containsExactly("Everything scoped");
    }

    @Test
    void selectedSourcesAreSkippedButStillCoverTheirDocumentsAndTakenNamesGetASuffix() {
        RagPipeline faqPipeline = pipeline("p-faq", "Product FAQ", List.of("d-faq"), false);
        stubPipeline(faqPipeline, List.of(this.faq));
        stubDocument(this.handbook);
        stubDocument(this.notes);
        when(this.ragPipelineService.list()).thenReturn(List.of(faqPipeline));

        List<Suggestion> suggestions = this.service.suggest(List.of("p-faq", "doc:d-notes"),
                List.of("search_product_faq", "search_handbook"));

        assertThat(suggestions).extracting(suggestion -> suggestion.knowledge().toolName())
                .containsExactly("search_handbook_2");
    }

    @Test
    void pipelinesWithMissingOrNoDocumentsAreLeftOut() {
        RagPipeline broken = pipeline("p-broken", "Broken", List.of("d-gone"), false);
        when(this.knowledgeContribution.resolve("p-broken")).thenReturn(Optional.of(
                new Source("p-broken", broken, List.of(), List.of("d-gone"), false)));
        RagPipeline empty = pipeline("p-all", "All documents (simple)", List.of(), false);
        when(this.knowledgeContribution.resolve("p-all")).thenReturn(Optional.of(
                new Source("p-all", empty, List.of(), List.of(), false)));
        when(this.documents.getVisibleDocumentList()).thenReturn(List.of());
        when(this.ragPipelineService.list()).thenReturn(List.of(broken, empty));

        assertThat(this.service.suggest(List.of(), List.of())).isEmpty();
    }

    @Test
    void uniqueToolNamesStayWithinTheLengthLimit() {
        String longest = "search_" + "a".repeat(KnowledgeContribution.MAX_TOOL_NAME_LENGTH - "search_".length());
        Set<String> taken = new LinkedHashSet<>(List.of(longest));

        String unique = KnowledgeSuggestService.uniqueToolName(longest, taken);

        assertThat(unique).hasSize(KnowledgeContribution.MAX_TOOL_NAME_LENGTH).endsWith("_2");
        assertThat(KnowledgeSuggestService.uniqueToolName("search_x", taken)).isEqualTo("search_x");
    }

    private void stubPipeline(RagPipeline pipeline, List<VectorStoreDocumentInfo> scoped) {
        when(this.knowledgeContribution.resolve(pipeline.id())).thenReturn(Optional.of(
                new Source(pipeline.id(), pipeline, scoped, List.of(), false)));
    }

    private void stubDocument(VectorStoreDocumentInfo info) {
        String sourceId = "doc:" + info.docInfoId();
        when(this.knowledgeContribution.resolve(sourceId)).thenReturn(Optional.of(new Source(sourceId,
                KnowledgeSearchService.documentPipeline(info), List.of(info), List.of(), true)));
    }

    private static RagPipeline pipeline(String id, String name, List<String> docInfoIds, boolean rewrite) {
        RagPipeline.PreRetrievalConfig defaults = RagPipeline.PreRetrievalConfig.defaults();
        RagPipeline.PreRetrievalConfig preRetrieval = new RagPipeline.PreRetrievalConfig(rewrite,
                defaults.rewritePromptTemplate(), defaults.compression(), defaults.compressionPromptTemplate(),
                defaults.translation(), defaults.translationTargetLanguage(), defaults.translationPromptTemplate(),
                defaults.multiQuery(), defaults.multiQueryCount(), defaults.multiQueryIncludeOriginal(),
                defaults.multiQueryPromptTemplate(), defaults.rewriteTargetSearchSystem());
        return new RagPipeline(id, name, null, docInfoIds, preRetrieval, null, null, null, 0L, 0L);
    }

    private static VectorStoreDocumentInfo document(String docInfoId, String title) {
        return new VectorStoreDocumentInfo(docInfoId, title, 0L, 0L, title + ".txt", "/x/" + title,
                () -> List.of(new Document("chunk")));
    }

}
