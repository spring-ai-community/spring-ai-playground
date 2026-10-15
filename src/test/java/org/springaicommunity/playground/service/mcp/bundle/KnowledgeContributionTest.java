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

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ExportResult;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ValidationReport;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Knowledge;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentPersistenceService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KnowledgeContributionTest {

    private static final String SERVER_NAME = "knowledge-test-server";

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEmbedding {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new AxisEmbeddingModel();
        }
    }

    @Autowired
    private KnowledgeContribution knowledgeContribution;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private RagPipelineService ragPipelineService;

    @Autowired
    private OfflineEtlPipelineService offlineEtlPipelineService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private VectorStoreDocumentPersistenceService persistenceService;

    @Autowired
    private ToolSpecService toolSpecService;

    private VectorStoreDocumentInfo shippedDocument;
    private VectorStoreDocumentInfo otherDocument;
    private RagPipeline pipeline;

    @BeforeEach
    void seed() {
        this.shippedDocument = this.offlineEtlPipelineService.loadDocument("qa-notes.txt", "QA Notes",
                "notes from the QA pass", List.of(new Document("alpha chunk about releases"),
                        new Document("beta chunk about hotfixes")));
        this.vectorStoreService.add(this.shippedDocument);
        this.otherDocument = this.offlineEtlPipelineService.loadDocument("other.txt", "Other", null,
                List.of(new Document("gamma chunk that must not ship")));
        this.vectorStoreService.add(this.otherDocument);
        this.pipeline = this.ragPipelineService.create("QA Pipeline", "Answers questions about QA",
                List.of(this.shippedDocument.docInfoId()), RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig(null, 3, null), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults());
    }

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.ragPipelineService.deleteById(this.pipeline.id());
        this.persistenceService.delete(List.of(this.shippedDocument.docInfoId(), this.otherDocument.docInfoId()));
        this.vectorStoreService.delete(this.shippedDocument.documentListSupplier().get().stream()
                .map(Document::getId).toList());
        this.vectorStoreService.delete(this.otherDocument.documentListSupplier().get().stream()
                .map(Document::getId).toList());
        this.toolSpecService.deleteToolSpec("kn-clash");
    }

    private ShipRequest request(Knowledge... knowledge) {
        return new ShipRequest(SERVER_NAME, "knowledge test", "0.1.0", List.of(), Transport.HTTP, 18700,
                null, null, null, List.of(knowledge));
    }

    @Test
    void defaultsAreDerivedFromTheSource() {
        assertThat(KnowledgeContribution.defaultToolName("Product FAQ (2026)")).isEqualTo("search_product_faq_2026");
        assertThat(KnowledgeContribution.defaultToolName("  ")).isEqualTo("search_knowledge");
        assertThat(KnowledgeContribution.defaultToolName("x".repeat(100))).hasSize(64).startsWith("search_");

        ShipRequest normalized = this.bundleExportService.normalize(request(new Knowledge(this.pipeline.id(),
                null, null, null)));
        Knowledge knowledge = normalized.knowledge().getFirst();
        assertThat(knowledge.toolName()).isEqualTo("search_qa_pipeline");
        assertThat(knowledge.description()).startsWith("Search the QA Pipeline knowledge base (1 document: QA Notes)")
                .contains("similarity score").endsWith("Answers questions about QA.");
        assertThat(knowledge.topK()).isEqualTo(3);
        assertThat(normalized.models().embedding()).isNotNull();
        assertThat(normalized.models().chat()).isNull();
    }

    @Test
    void aDocumentSourceShipsAsItsOwnRetrievalOnlyPipeline() {
        String sourceId = ChatService.RAG_DOCUMENT_SOURCE_PREFIX + this.shippedDocument.docInfoId();
        KnowledgeContribution.Source source = this.knowledgeContribution.resolve(sourceId).orElseThrow();
        assertThat(source.document()).isTrue();
        assertThat(source.pipeline().id()).isEqualTo("doc-" + this.shippedDocument.docInfoId());
        assertThat(source.documents()).extracting(VectorStoreDocumentInfo::title).containsExactly("QA Notes");
        assertThat(this.knowledgeContribution.resolveSourceId("QA Notes")).contains(sourceId);
        assertThat(this.knowledgeContribution.resolveSourceId("QA Pipeline")).contains(this.pipeline.id());
        assertThat(this.knowledgeContribution.resolveSourceId("nope")).isEmpty();
    }

    @Test
    void validationBlocksWhatCannotShip() {
        this.toolSpecService.update(new ToolSpec("kn-clash", "search_qa_pipeline", "clashes", List.of(),
                List.of(), "return 1;", CodeType.Javascript, null).withDraft(false));
        RagPipeline rewriting = this.ragPipelineService.update(new RagPipeline(this.pipeline.id(),
                this.pipeline.name(), this.pipeline.description(), this.pipeline.docInfoIds(),
                new RagPipeline.PreRetrievalConfig(true, null, false, null, false, "english", null, false, 3,
                        true, null, null),
                this.pipeline.retrieval(), this.pipeline.postRetrieval(), this.pipeline.generation(),
                this.pipeline.createdAt(), this.pipeline.updatedAt()));

        ValidationReport report = this.bundleExportService.validate(new ShipRequest(SERVER_NAME, null, "0.1.0",
                List.of("kn-clash"), Transport.HTTP, 18700, null, null, null,
                List.of(new Knowledge(rewriting.id(), null, null, null),
                        new Knowledge("missing-source", null, null, null),
                        new Knowledge(ChatService.RAG_DOCUMENT_SOURCE_PREFIX + this.otherDocument.docInfoId(),
                                "Bad Name", null, 99),
                        new Knowledge(rewriting.id(), "saip_kb", null, null))));

        assertThat(report.blockers())
                .anyMatch(blocker -> blocker.contains("Two tools are named 'search_qa_pipeline'"))
                .anyMatch(blocker -> blocker.contains("runs Rewrite before retrieval, which needs a chat model"))
                .anyMatch(blocker -> blocker.contains("'missing-source' does not exist"))
                .anyMatch(blocker -> blocker.contains("'Bad Name' must be lowercase"))
                .anyMatch(blocker -> blocker.contains("'saip_kb' starts with 'saip_', which is reserved"))
                .anyMatch(blocker -> blocker.contains("top_k must be between 1 and 20"));
        assertThat(report.tools()).extracting(ShippedTool::kind)
                .contains(ShippedTool.Kind.KNOWLEDGE, ShippedTool.Kind.TOOL);
    }

    @Test
    void exportFreezesThePipelineAndShipsOnlyItsDocuments() throws Exception {
        ExportResult result = this.bundleExportService.exportBundle(request(new Knowledge(this.pipeline.id(),
                null, null, 5)));
        BundleManifest manifest = result.manifest();

        BundleManifest.RagPipelineRef ref = manifest.contents().ragPipelines().getFirst();
        assertThat(ref.alias()).isEqualTo("search_qa_pipeline");
        assertThat(ref.source()).isEqualTo(this.pipeline.id());
        assertThat(ref.topK()).isEqualTo(5);
        assertThat(ref.expose().search()).isTrue();
        assertThat(ref.expose().ask()).isFalse();
        assertThat(ref.documents()).extracting(BundleManifest.DocumentRef::title).containsExactly("QA Notes");
        assertThat(ref.documents().getFirst().chunks()).isEqualTo(2);
        assertThat(manifest.contents().toolCount()).isEqualTo(1);
        assertThat(manifest.risk().summary()).extracting(BundleManifest.ToolRisk::kind).containsExactly("KNOWLEDGE");
        assertThat(manifest.risk().summary().getFirst().level()).isEqualTo("L1");
        assertThat(manifest.requires().models().embedding()).isNotNull();

        Path home = result.bundleDir().resolve("home");
        RagPipeline frozen = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                Files.readAllBytes(home.resolve("vectorstore/pipelines/" + this.pipeline.id() + ".json")),
                RagPipeline.class);
        assertThat(frozen.docInfoIds()).containsExactly(this.shippedDocument.docInfoId());
        assertThat(frozen.retrieval().topK()).isEqualTo(5);
        assertThat(frozen.retrieval().similarityThreshold()).isNotNull();
        assertThat(frozen.generation().runAugmentLLM()).isFalse();

        String storeDir = this.persistenceService.getSaveDir().getFileName().toString();
        assertThat(home.resolve("vectorstore/save/" + storeDir + "/" + this.shippedDocument.docInfoId() + ".json"))
                .exists();
        assertThat(home.resolve("vectorstore/save/" + storeDir + "/" + this.otherDocument.docInfoId() + ".json"))
                .doesNotExist();
        Map<String, Object> dump = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                Files.readAllBytes(home.resolve(KnowledgeContribution.DUMP_FILE)),
                PersistenceServiceInterface.MAP_TYPE_REFERENCE);
        assertThat(dump).hasSize(2);
        assertThat(dump.values()).allSatisfy(entry -> assertThat(((Map<?, ?>) ((Map<?, ?>) entry).get("metadata"))
                .get(VectorStoreService.DOC_INFO_ID)).isEqualTo(this.shippedDocument.docInfoId()));
        assertThat(manifest.integrity().files()).containsKey("home/" + KnowledgeContribution.DUMP_FILE);
        assertThat(manifest.provenance().sources()).containsKey("knowledge:search_qa_pipeline");
        assertThat(Files.readString(result.bundleDir().resolve("README.md"))).contains("## Knowledge")
                .contains("`search_qa_pipeline`: 1 document(s), 2 chunks, top_k 5");

        List<String> problems = new ArrayList<>();
        this.knowledgeContribution.verify(manifest, problems);
        assertThat(problems).isEmpty();
    }

    @Test
    void aPipelineEditChangesTheFingerprint() {
        ShipRequest request = this.bundleExportService.normalize(request(new Knowledge(this.pipeline.id(),
                null, null, null)));
        String before = this.bundleExportService.fingerprints(request).get("knowledge:search_qa_pipeline");

        this.ragPipelineService.update(new RagPipeline(this.pipeline.id(), this.pipeline.name(),
                this.pipeline.description(), this.pipeline.docInfoIds(), this.pipeline.preRetrieval(),
                new RagPipeline.RetrievalConfig(null, 3, 0.9), this.pipeline.postRetrieval(),
                this.pipeline.generation(), this.pipeline.createdAt(), this.pipeline.updatedAt()));

        assertThat(this.bundleExportService.fingerprints(request).get("knowledge:search_qa_pipeline"))
                .isNotEqualTo(before);
    }

    @Test
    void theToolDeclaresItsSchemasAndRendersExcerptsWithinTheBudget() {
        BundleManifest.RagPipelineRef ref = new BundleManifest.RagPipelineRef("kb", new BundleManifest.Expose(true,
                false), "search_kb", "Search the kb", "kb", 4,
                List.of(new BundleManifest.DocumentRef("doc-1", "Handbook", 2)));

        McpSchema.Tool tool = this.knowledgeContribution.toolSpecification(ref).tool();
        assertThat(tool.name()).isEqualTo("search_kb");
        assertThat(tool.inputSchema().get("required")).isEqualTo(List.of("query"));
        assertThat(((Map<?, ?>) tool.inputSchema().get("properties")).keySet().toString())
                .isEqualTo("[query, top_k]");
        assertThat(tool.outputSchema()).containsKey("properties");
        assertThat(tool.annotations().readOnlyHint()).isTrue();

        Document first = Document.builder().id("c1").text("first excerpt")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1")
                .metadata(KnowledgeContribution.CHUNK_INDEX_METADATA, 2).score(0.83).build();
        Document second = Document.builder().id("c2").text("x".repeat(KnowledgeContribution.OUTPUT_CHAR_BUDGET))
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1").score(0.41).build();
        McpSchema.CallToolResult rendered = this.knowledgeContribution.render(ref, List.of(first, second));

        assertThat(((McpSchema.TextContent) rendered.content().getFirst()).text())
                .isEqualTo("[1] Handbook · chunk 3 · score 0.83\nfirst excerpt");
        assertThat(rendered.content()).hasSize(2);
        Map<?, ?> structured = (Map<?, ?>) rendered.structuredContent();
        assertThat(structured.get("truncated")).isEqualTo(true);
        List<?> results = (List<?>) structured.get("results");
        assertThat(results).hasSize(2);
        assertThat(((Map<?, ?>) results.getFirst()).get("chunk")).isEqualTo(3);
        assertThat(((Map<?, ?>) results.get(1)).containsKey("chunk")).isFalse();
        assertThat(((Map<?, ?>) results.getFirst()).get("document")).isEqualTo("Handbook");
        assertThat(((String) ((Map<?, ?>) results.get(1)).get("text")).length())
                .isLessThan(KnowledgeContribution.OUTPUT_CHAR_BUDGET);

        Document unscored = Document.builder().id("c3").text("no score no chunk")
                .metadata(VectorStoreService.DOC_INFO_ID, "doc-1").build();
        McpSchema.CallToolResult unscoredRendered = this.knowledgeContribution.render(ref, List.of(unscored));
        Map<?, ?> unscoredEntry = (Map<?, ?>) ((List<?>) ((Map<?, ?>) unscoredRendered.structuredContent())
                .get("results")).getFirst();
        assertThat(unscoredEntry.containsKey("chunk")).isFalse();
        assertThat(unscoredEntry.containsKey("score")).isFalse();
        assertThat(unscoredEntry.get("text")).isEqualTo("no score no chunk");

        McpSchema.CallToolResult empty = this.knowledgeContribution.search(ref, Map.of("top_k", 0, "query", "x"));
        assertThat(empty.isError()).isTrue();
        assertThat(this.knowledgeContribution.search(ref, Map.of()).isError()).isTrue();
    }

}
