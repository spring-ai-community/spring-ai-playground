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
package org.springaicommunity.playground.service.management;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.DocumentsData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.IngestData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.PipelineData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.PipelinesData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.SearchData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.StatusData;
import org.springaicommunity.playground.service.management.KnowledgeManagementContribution.SuggestionsData;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.Change;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.SourceChange;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerSummary;
import org.springaicommunity.playground.service.vectorstore.ConfiguredKnowledgeLoader;
import org.springaicommunity.playground.service.vectorstore.DocumentSpec;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService;
import org.springaicommunity.playground.service.vectorstore.KnowledgeIngestService.IngestProgress;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService.Excerpt;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService.SearchResult;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.PipelineSpec;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeManagementContributionTest {

    private final KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
    private final RagPipelineService pipelineService = mock(RagPipelineService.class);
    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final ConfiguredKnowledgeLoader knowledgeLoader = mock(ConfiguredKnowledgeLoader.class);
    private final OfflineEtlPipelineService documents = mock(OfflineEtlPipelineService.class);
    private final ShipyardService shipyardService = mock(ShipyardService.class);
    private final BundleFreshnessService freshnessService = mock(BundleFreshnessService.class);
    private final KnowledgeSuggestService suggestService = mock(KnowledgeSuggestService.class);
    private final KnowledgeManagementContribution contribution = new KnowledgeManagementContribution(ingestService,
            pipelineService, searchService, knowledgeLoader, documents, shipyardService, freshnessService,
            suggestService);

    private final VectorStoreDocumentInfo handbook = new VectorStoreDocumentInfo("d1", "Handbook", "ops", 1L, 2L,
            "handbook.pdf", "/x/handbook.pdf", false, "fp-1", () -> List.of(new Document("a"), new Document("b")));

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void ingestTakesTheDocumentSpecAsItsInputAndSummarizesTheCost() {
        when(ingestService.declaredTitle(any())).thenReturn("Harbour notes");
        when(ingestService.find("Harbour notes")).thenReturn(Optional.empty());
        when(ingestService.ingest(any(), any(IngestProgress.class))).thenReturn(handbook);
        Map<String, Object> arguments = Map.of("source", Map.of("text", "Berths are allocated on Monday."),
                "title", "Harbour notes", "split", Map.of("chunkSize", 400),
                "enrich", Map.of("keyword", true, "summary", true));

        ManagementApproval approval = tool("saip_knowledge_ingest_document").approvalFor(arguments);
        ManagementResult result = tool("saip_knowledge_ingest_document").handle(arguments, ManagementCaller.ANONYMOUS);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.WRITE);
        assertThat(approval.summary()).isEqualTo("Ingest 'Harbour notes' from inline text (31 chars) (chunks of 400 "
                + "tokens, 2 chat-model call(s) per chunk)");
        ArgumentCaptor<DocumentSpec> spec = ArgumentCaptor.forClass(DocumentSpec.class);
        verify(ingestService).ingest(spec.capture(), any(IngestProgress.class));
        assertThat(spec.getValue().source().kind()).isEqualTo(DocumentSpec.Source.Kind.TEXT);
        assertThat(spec.getValue().split().toInfo().chunkSize()).isEqualTo(400);
        assertThat(spec.getValue().extract().toOptions(null).charset()).isEqualTo("UTF-8");
        assertThat(result.persisted()).isTrue();
        assertThat(((IngestData) result.data()).document().chunks()).isEqualTo(2);
        assertThat(((IngestData) result.data()).nextStep()).contains("saip_knowledge_search");
    }

    @Test
    void ingestRefusesExistingAndDeclaredTitlesBeforeApproval() {
        when(ingestService.declaredTitle(any())).thenReturn("Handbook");
        when(ingestService.find("Handbook")).thenReturn(Optional.of(handbook));
        Map<String, Object> arguments = Map.of("source", Map.of("path", "~/inbox/handbook.pdf"), "title", "Handbook");

        assertThatThrownBy(() -> tool("saip_knowledge_ingest_document").approvalFor(arguments))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");

        when(knowledgeLoader.managesDocument("Handbook")).thenReturn(true);
        assertThatThrownBy(() -> tool("saip_knowledge_ingest_document").approvalFor(arguments))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("managed by configuration");
        verify(ingestService, never()).ingest(any(), any());
    }

    @Test
    void unknownArgumentsAreRejectedByTheSchema() {
        assertThatThrownBy(() -> tool("saip_knowledge_ingest_document").approvalFor(Map.of("source",
                Map.of("text", "x"), "chunkSize", 400)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid arguments");
    }

    @Test
    void deleteNamesTheScopedPipelinesAndGoesThroughTheIngestService() {
        when(ingestService.find("Handbook")).thenReturn(Optional.of(handbook));
        when(pipelineService.referencing(List.of("d1"))).thenReturn(List.of(pipeline("KB", List.of("d1"))));

        ManagementApproval approval = tool("saip_knowledge_delete_document").approvalFor(Map.of("document", "Handbook"));
        tool("saip_knowledge_delete_document").handle(Map.of("document", "Handbook"), ManagementCaller.ANONYMOUS);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.summary()).isEqualTo("Delete document 'Handbook' (2 chunks); scoped by pipeline(s) KB");
        verify(ingestService).delete("d1");
    }

    @Test
    void listDocumentsReportsFingerprintsRootsAndDeclaredProblems() {
        when(documents.getVisibleDocumentList()).thenReturn(List.of(handbook));
        when(ingestService.ingestRoots()).thenReturn(List.of(Path.of("/srv/inbox")));
        when(knowledgeLoader.problems()).thenReturn(Map.of("Missing", "not loaded: outside the ingest roots"));
        when(knowledgeLoader.managesDocument("Handbook")).thenReturn(true);

        DocumentsData data = (DocumentsData) tool("saip_knowledge_list_documents")
                .handle(Map.of(), ManagementCaller.ANONYMOUS).data();

        assertThat(data.documents()).singleElement().satisfies(document -> {
            assertThat(document.sourceFingerprint()).isEqualTo("fp-1");
            assertThat(document.chunks()).isEqualTo(2);
            assertThat(document.managedByConfiguration()).isTrue();
        });
        assertThat(data.ingestRoots()).containsExactly("/srv/inbox");
        assertThat(data.problems()).containsKey("Missing");
    }

    @Test
    void applyPipelineTakesThePipelineSpecAndDescribesTheStages() {
        RagPipeline applied = pipeline("Advanced RAG", List.of("d1"));
        when(pipelineService.apply(any(PipelineSpec.class))).thenReturn(applied);
        when(pipelineService.specOf(applied)).thenReturn(PipelineSpec.of(applied, List.of("Handbook")));
        Map<String, Object> arguments = Map.of("name", "Advanced RAG", "documents", List.of("Handbook"),
                "preRetrieval", Map.of("rewrite", true, "multiQuery", true, "multiQueryCount", 4),
                "retrieval", Map.of("topK", 6));

        ManagementApproval approval = tool("saip_knowledge_apply_pipeline").approvalFor(arguments);
        ManagementResult result = tool("saip_knowledge_apply_pipeline").handle(arguments, ManagementCaller.ANONYMOUS);

        assertThat(approval.summary()).isEqualTo("Create pipeline 'Advanced RAG' over Handbook with stages "
                + "Rewrite > Multi-Q×4 > Retrieve > Re-rank > Augment");
        ArgumentCaptor<PipelineSpec> spec = ArgumentCaptor.forClass(PipelineSpec.class);
        verify(pipelineService).apply(spec.capture());
        assertThat(spec.getValue().retrieval().toConfig().topK()).isEqualTo(6);
        assertThat(spec.getValue().preRetrieval().toConfig().multiQueryIncludeOriginal()).isTrue();
        PipelineData data = (PipelineData) result.data();
        assertThat(data.spec().documents()).containsExactly("Handbook");
        assertThat(data.stages()).contains("Retrieve");

        when(knowledgeLoader.managesPipeline("Advanced RAG")).thenReturn(true);
        assertThatThrownBy(() -> tool("saip_knowledge_apply_pipeline").approvalFor(arguments))
                .hasMessageContaining("managed by configuration");
    }

    @Test
    void listPipelinesReturnsEachPipelineWithStagesAndSettings() {
        RagPipeline kb = pipeline("KB", List.of("d1"));
        when(pipelineService.list()).thenReturn(List.of(kb));
        when(pipelineService.specOf(kb)).thenReturn(PipelineSpec.of(kb, List.of("Handbook")));

        ManagementResult result = tool("saip_knowledge_list_pipelines").handle(Map.of(), ManagementCaller.ANONYMOUS);

        assertThat(result.message()).isEqualTo("1 pipeline(s).");
        PipelinesData data = (PipelinesData) result.data();
        assertThat(data.pipelines()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(kb.id());
            assertThat(entry.name()).isEqualTo("KB");
            assertThat(entry.stages()).contains("Retrieve");
            assertThat(entry.spec().documents()).containsExactly("Handbook");
        });
    }

    @Test
    void deletePipelineResolvesNameOrIdAndRefusesDeclaredPipelines() {
        RagPipeline kb = pipeline("KB", List.of("d1"));
        when(pipelineService.get("KB")).thenReturn(Optional.empty());
        when(pipelineService.findByName("KB")).thenReturn(Optional.of(kb));
        when(pipelineService.get(kb.id())).thenReturn(Optional.of(kb));

        ManagementApproval approval = tool("saip_knowledge_delete_pipeline").approvalFor(Map.of("pipeline", "KB"));
        ManagementResult result = tool("saip_knowledge_delete_pipeline").handle(Map.of("pipeline", kb.id()),
                ManagementCaller.ANONYMOUS);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.summary()).isEqualTo("Delete pipeline 'KB'");
        assertThat(result.message()).isEqualTo("Deleted pipeline 'KB'.");
        verify(pipelineService).deleteById(kb.id());
        verify(ingestService, never()).delete(anyString());

        assertThatThrownBy(() -> tool("saip_knowledge_delete_pipeline").approvalFor(Map.of("pipeline", "missing")))
                .hasMessageContaining("unknown pipeline 'missing'");
        when(knowledgeLoader.managesPipeline("KB")).thenReturn(true);
        assertThatThrownBy(() -> tool("saip_knowledge_delete_pipeline").approvalFor(Map.of("pipeline", "KB")))
                .hasMessageContaining("managed by configuration");
    }

    @Test
    void searchResolvesTitlesAndReturnsTheShippedToolsExcerpts() {
        RagPipeline documentPipeline = KnowledgeSearchService.documentPipeline(handbook);
        when(searchService.resolvePipeline("Handbook")).thenReturn(Optional.empty());
        when(pipelineService.findByName("Handbook")).thenReturn(Optional.empty());
        when(ingestService.find("Handbook")).thenReturn(Optional.of(handbook));
        when(searchService.resolvePipeline("doc:d1")).thenReturn(Optional.of(documentPipeline));
        when(searchService.search(eq("doc:d1"), eq("dry dock"), isNull(), any())).thenReturn(new SearchResult(
                List.of(new Excerpt(1, "d1", "Handbook", 3, 0.63, "out of service every February")), false));

        ManagementResult result = tool("saip_knowledge_search").handle(Map.of("source", "Handbook",
                "query", "dry dock"), ManagementCaller.ANONYMOUS);

        SearchData data = (SearchData) result.data();
        assertThat(data.pipeline()).isEqualTo("Handbook");
        assertThat(data.stages()).containsExactly("Retrieve", "Re-rank", "Augment");
        assertThat(data.results()).singleElement().satisfies(excerpt -> {
            assertThat(excerpt.chunk()).isEqualTo(3);
            assertThat(excerpt.text()).contains("February");
        });
        assertThat(result.message()).isEqualTo("1 excerpt(s).");
        assertThatThrownBy(() -> tool("saip_knowledge_search").handle(Map.of("source", "nope", "query", "q"),
                ManagementCaller.ANONYMOUS)).hasMessageContaining("unknown knowledge source");
    }

    @Test
    void statusListsOnlyKnowledgeServersWithTheirKnowledgeChanges() {
        BundleManifest withKnowledge = mock(BundleManifest.class, RETURNS_DEEP_STUBS);
        when(withKnowledge.contents().ragPipelines()).thenReturn(List.of(new BundleManifest.RagPipelineRef("p1",
                new BundleManifest.Expose(true, false), "search_handbook", "d", "KB", 4, List.of())));
        BundleManifest toolsOnly = mock(BundleManifest.class, RETURNS_DEEP_STUBS);
        when(toolsOnly.contents().ragPipelines()).thenReturn(List.of());
        BundleEntry knowledgeEntry = new BundleEntry("field-kit", withKnowledge, Path.of("/b/field-kit"), null);
        BundleEntry toolsEntry = new BundleEntry("helpers", toolsOnly, Path.of("/b/helpers"), null);
        when(shipyardService.servers()).thenReturn(List.of(
                new ServerSummary("field-kit", Optional.of(knowledgeEntry), true, null),
                new ServerSummary("helpers", Optional.of(toolsEntry), false, null),
                new ServerSummary("broken", Optional.empty(), false, "not exported")));
        when(freshnessService.changes(knowledgeEntry)).thenReturn(List.of(
                new SourceChange("knowledge:search_handbook/doc:Handbook", Change.CHANGED, "Handbook"),
                new SourceChange("knowledge:search_handbook/doc:Policies", Change.ADDED, "Policies"),
                new SourceChange("tool:uuid", Change.REMOVED)));

        ManagementResult result = tool("saip_knowledge_status").handle(Map.of(), ManagementCaller.ANONYMOUS);

        StatusData data = (StatusData) result.data();
        assertThat(data.servers()).singleElement().satisfies(server -> {
            assertThat(server.server()).isEqualTo("field-kit");
            assertThat(server.knowledgeTools()).containsExactly("search_handbook");
            assertThat(server.changes()).hasSize(2);
            assertThat(server.changes().getFirst().tool()).isEqualTo("search_handbook");
            assertThat(server.changes().getFirst().part()).isEqualTo("document Handbook");
            assertThat(server.changes().getFirst().change()).isEqualTo("CHANGED");
            assertThat(server.changes().get(1).change()).isEqualTo("ADDED");
        });
        assertThat(result.message()).isEqualTo("1 knowledge server(s), 1 out of date.");
    }

    @Test
    void ingestForwardsEachStageAndEnrichedChunkAsProgressToTheCaller() {
        when(ingestService.declaredTitle(any())).thenReturn("Harbour notes");
        when(ingestService.find("Harbour notes")).thenReturn(Optional.empty());
        when(ingestService.ingest(any(), any(IngestProgress.class))).thenAnswer(invocation -> {
            IngestProgress progress = invocation.getArgument(1);
            progress.stage().accept(KnowledgeIngestService.Stage.READING, 0);
            progress.stage().accept(KnowledgeIngestService.Stage.SPLIT, 4);
            progress.progress().accept(2, 4);
            progress.stage().accept(KnowledgeIngestService.Stage.EMBEDDING, 4);
            return handbook;
        });
        List<String> reports = new ArrayList<>();
        ManagementCaller caller = new ManagementCaller("cursor", "1", false, true, false,
                (progress, total, message) -> reports.add(progress + "/" + total + " " + message));

        tool("saip_knowledge_ingest_document").handle(Map.of("source", Map.of("text", "Berths."),
                "title", "Harbour notes"), caller);

        assertThat(reports).containsExactly("0.0/3.0 Reading inline text (7 chars)", "1.0/3.0 Split into 4 chunk(s)",
                "1.5/3.0 Enriched chunk 2 of 4", "2.0/3.0 Embedding 4 chunk(s)", "3.0/3.0 Ingested 2 chunk(s)");
    }

    @Test
    void suggestResolvesTheChosenSourcesAndReturnsShipReadyKnowledgeEntries() {
        RagPipeline faq = pipeline("Product FAQ", List.of("d1"));
        when(pipelineService.findByName("Product FAQ")).thenReturn(Optional.of(faq));
        when(ingestService.find("Handbook")).thenReturn(Optional.of(handbook));
        when(suggestService.suggest(List.of(faq.id(), "doc:d1"), List.of("search_product_faq"))).thenReturn(List.of(
                new KnowledgeSuggestService.Suggestion(new ShipRequest.Knowledge("doc:d2", "search_release_notes",
                        "Search the Release notes knowledge base", null), "Release notes",
                        KnowledgeSuggestService.Reason.UNCOVERED_DOCUMENT, 1, List.of())));

        ManagementResult result = tool("saip_knowledge_suggest_tools").handle(Map.of("selected",
                List.of("Product FAQ", "Handbook"), "takenToolNames", List.of("search_product_faq")),
                ManagementCaller.ANONYMOUS);

        assertThat(result.message()).startsWith("1 knowledge tool(s) suggested");
        SuggestionsData data = (SuggestionsData) result.data();
        assertThat(data.knowledge()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.source()).isEqualTo("Release notes");
            assertThat(suggestion.sourceId()).isEqualTo("doc:d2");
            assertThat(suggestion.name()).isEqualTo("search_release_notes");
            assertThat(suggestion.reason()).isEqualTo("UNCOVERED_DOCUMENT");
            assertThat(suggestion.documents()).isEqualTo(1);
        });
        assertThat(tool("saip_knowledge_suggest_tools").level()).isEqualTo(ApprovalLevel.READ);
    }

    @Test
    void theGuideAndPromptCoverTheWholeChain() {
        String guide = ((TextResourceContents) contribution.resources().getFirst().readHandler()
                .apply(null, new ReadResourceRequest(KnowledgeManagementContribution.GUIDE_URI)).contents().getFirst())
                .text();

        assertThat(guide).contains("saip_knowledge_ingest_document", "saip_knowledge_apply_pipeline",
                "saip_knowledge_search", "saip_knowledge_suggest_tools", "saip_shipyard_ship", "ingest-roots",
                "qwen3-embedding:0.6b");
        assertThat(contribution.prompts().getFirst().prompt().name())
                .isEqualTo(KnowledgeManagementContribution.PROMPT_NAME);
        assertThat(contribution.tools()).extracting(ManagementTool::name)
                .allMatch(name -> name.startsWith("saip_knowledge_")).hasSize(9);
        assertThat(ManagementSchemas.inputSchema(DocumentSpec.class).get("required")).isEqualTo(List.of("source"));
        verify(searchService, never()).search(anyString(), anyString(), any(), any());
    }

    private static RagPipeline pipeline(String name, List<String> docInfoIds) {
        return new RagPipeline("p-" + name, name, null, docInfoIds,
                new RagPipeline.PreRetrievalConfig(true, null, false, null, false, "english", null, true, 4, true,
                        null, null), null, null, null, 1L, 2L);
    }

}
