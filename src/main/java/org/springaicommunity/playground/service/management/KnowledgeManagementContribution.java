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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService;
import org.springaicommunity.playground.service.mcp.bundle.KnowledgeSuggestService.Suggestion;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
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
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class KnowledgeManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "knowledge";
    public static final String GUIDE_URI = "saip://guide/knowledge";
    public static final String PROMPT_NAME = "saip_knowledge_server";

    private static final String MARKDOWN = "text/markdown";
    private static final String KNOWLEDGE_SOURCE_PREFIX = "knowledge:";
    private static final double INGEST_STAGES = 3;

    private final KnowledgeIngestService ingestService;
    private final RagPipelineService pipelineService;
    private final KnowledgeSearchService searchService;
    private final ConfiguredKnowledgeLoader knowledgeLoader;
    private final OfflineEtlPipelineService documents;
    private final ShipyardService shipyardService;
    private final BundleFreshnessService freshnessService;
    private final KnowledgeSuggestService suggestService;

    public KnowledgeManagementContribution(KnowledgeIngestService ingestService, RagPipelineService pipelineService,
            KnowledgeSearchService searchService, ConfiguredKnowledgeLoader knowledgeLoader,
            OfflineEtlPipelineService documents, ShipyardService shipyardService,
            BundleFreshnessService freshnessService, KnowledgeSuggestService suggestService) {
        this.ingestService = ingestService;
        this.pipelineService = pipelineService;
        this.searchService = searchService;
        this.knowledgeLoader = knowledgeLoader;
        this.documents = documents;
        this.shipyardService = shipyardService;
        this.freshnessService = freshnessService;
        this.suggestService = suggestService;
    }

    public record NoInput() {
    }

    public record DocumentInput(@JsonPropertyDescription("Document title or id from saip_knowledge_list_documents.")
                                String document) {
    }

    public record PipelineInput(@JsonPropertyDescription("Pipeline name or id from saip_knowledge_list_pipelines.")
                                String pipeline) {
    }

    public record SearchInput(
            @JsonPropertyDescription("Pipeline name or id, or document title or id, as a shipped search tool would "
                    + "use it.") String source,
            @JsonPropertyDescription("The question or search phrase.") String query,
            @Nullable @JsonPropertyDescription("Excerpts to return, 1 to 20; default the source's own setting.")
            Integer topK) {
    }

    public record SuggestInput(
            @Nullable @JsonPropertyDescription("Pipeline names or ids, or document titles or ids, already chosen "
                    + "for the server; their documents count as covered and they are not suggested again.")
            List<String> selected,
            @Nullable @JsonPropertyDescription("Tool names the server already has; suggested names avoid them.")
            List<String> takenToolNames) {
    }

    public record SuggestionData(String source, String sourceId, String name, String description, String reason,
                                 int documents, List<String> llmStages) {
    }

    public record SuggestionsData(List<SuggestionData> knowledge) {
    }

    public record DocumentData(String id, String title, String description, int chunks, String fileName,
                               String sourceFingerprint, boolean managedByConfiguration, long updatedAt) {
    }

    public record DocumentsData(List<DocumentData> documents, List<String> ingestRoots, Map<String, String> problems) {
    }

    public record IngestData(DocumentData document, String nextStep) {
    }

    public record PipelineData(String id, String name, List<String> stages, int extraLlmCallsPerQuery,
                               boolean managedByConfiguration, long updatedAt, PipelineSpec spec) {
    }

    public record PipelinesData(List<PipelineData> pipelines) {
    }

    public record ExcerptData(int rank, String documentId, String document, Integer chunk, Double score,
                              String text) {
    }

    public record SearchData(String source, String pipeline, List<String> stages, List<ExcerptData> results,
                             boolean truncated) {
    }

    public record ChangeData(String tool, String part, String change) {
    }

    public record ServerKnowledgeData(String server, List<String> knowledgeTools, List<ChangeData> changes) {
    }

    public record StatusData(List<ServerKnowledgeData> servers) {
    }

    public record NameData(String name) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(listDocuments(), ingestDocument(), deleteDocument(), listPipelines(), applyPipeline(),
                deletePipeline(), search(), suggestTools(), status());
    }

    @Override
    public List<SyncResourceSpecification> resources() {
        Resource resource = Resource.builder().uri(GUIDE_URI).name("knowledge-guide")
                .title("Building the knowledge behind a search tool")
                .description("How to ingest documents, shape a RAG pipeline, check the search and ship it as a "
                        + "knowledge MCP server.")
                .mimeType(MARKDOWN).build();
        return List.of(new SyncResourceSpecification(resource, (exchange, request) -> new ReadResourceResult(
                List.of(new TextResourceContents(GUIDE_URI, MARKDOWN, ProxyGuideContribution.read("knowledge.md"))))));
    }

    @Override
    public List<SyncPromptSpecification> prompts() {
        Prompt prompt = new Prompt(PROMPT_NAME, "Build a knowledge MCP server from documents",
                "Step by step procedure for ingesting documents, optionally shaping a RAG pipeline, checking the "
                        + "search and shipping it as an MCP server.",
                List.of(new PromptArgument("goal", "Goal", "Which documents or folder, what the server should "
                        + "answer and where it should run, for example: make a knowledge server from the PDFs in "
                        + "the inbox that answers questions about harbour operations.", true)));
        return List.of(new SyncPromptSpecification(prompt, (exchange, request) -> {
            Object goal = request.arguments() == null ? null : request.arguments().get("goal");
            String text = "Goal: " + (goal == null ? "(not given)" : goal) + "\n\n"
                    + "Read the resource " + GUIDE_URI + " first, then follow its procedure: list the documents, "
                    + "ingest what is missing with saip_knowledge_ingest_document, shape a pipeline with "
                    + "saip_knowledge_apply_pipeline only when plain retrieval is not enough, check answers with "
                    + "saip_knowledge_search, then ship with the saip_shipyard_* tools (" + ShipyardManagementContribution.GUIDE_URI
                    + ") and hand over the connection snippet.";
            return new GetPromptResult("Build a knowledge MCP server from documents",
                    List.of(new PromptMessage(Role.USER, new TextContent(text))));
        }));
    }

    private ManagementTool listDocuments() {
        return ManagementTool.named("saip_knowledge_list_documents", CATEGORY, NoInput.class)
                .data(DocumentsData.class)
                .description("List the knowledge-base documents of this app with chunk counts and fingerprints, "
                        + "the folders a document may be ingested from by path, and declared documents that could "
                        + "not be loaded.")
                .idempotent()
                .handle((input, caller) -> {
                    List<DocumentData> list = this.documents.getVisibleDocumentList().stream()
                            .map(this::documentData).toList();
                    DocumentsData data = new DocumentsData(list,
                            this.ingestService.ingestRoots().stream().map(Object::toString).toList(),
                            this.knowledgeLoader.problems());
                    return ManagementResult.ok(list.size() + " document(s).", data);
                });
    }

    private ManagementTool ingestDocument() {
        return ManagementTool.named("saip_knowledge_ingest_document", CATEGORY, DocumentSpec.class)
                .data(IngestData.class)
                .description("Read a file, URL or inline text, split it into chunks, optionally enrich them with "
                        + "the chat model and embed them into the knowledge base. Same inputs as the Vector "
                        + "Database upload dialog. Synchronous: a large document takes minutes, enrichment takes "
                        + "one chat-model call per chunk per enricher; send a progressToken in _meta to get "
                        + "progress notifications per stage and per enriched chunk. A title that already "
                        + "exists must be deleted first.")
                .openWorld()
                .write(spec -> {
                    String title = this.ingestService.declaredTitle(spec);
                    requireNotDeclaredDocument(title);
                    if (this.ingestService.find(title).isPresent()) {
                        throw new IllegalArgumentException("A document titled '" + title
                                + "' already exists; delete it first or choose another title");
                    }
                    int llmCalls = spec.enrich().toOptions().llmCallsPerChunk();
                    return "Ingest '" + title + "' from " + spec.source().describe() + " (chunks of "
                            + spec.split().toInfo().chunkSize() + " tokens"
                            + (llmCalls == 0 ? ")" : ", " + llmCalls + " chat-model call(s) per chunk)");
                })
                .handle((spec, caller) -> {
                    VectorStoreDocumentInfo info = this.ingestService.ingest(spec, ingestProgress(spec, caller));
                    DocumentData data = documentData(info);
                    caller.progress().report(INGEST_STAGES, INGEST_STAGES, "Ingested " + data.chunks() + " chunk(s)");
                    return ManagementResult.persisted("Ingested '" + info.title() + "' with " + data.chunks()
                            + " chunk(s).", new IngestData(data,
                            "Check it with saip_knowledge_search, then ship it with saip_shipyard_ship "
                                    + "(knowledge source '" + info.title() + "')."));
                });
    }

    private ManagementTool deleteDocument() {
        return ManagementTool.named("saip_knowledge_delete_document", CATEGORY, DocumentInput.class)
                .data(NameData.class)
                .description("Delete a knowledge-base document and its chunks. Pipelines scoped to it keep the "
                        + "reference but stop matching; shipped servers that searched it go out of date.")
                .destructive(input -> {
                    VectorStoreDocumentInfo info = requireDocument(input.document());
                    requireNotDeclaredDocument(info.title());
                    List<RagPipeline> scoped = this.pipelineService.referencing(List.of(info.docInfoId()));
                    return "Delete document '" + info.title() + "' (" + chunkCount(info) + " chunks)"
                            + (scoped.isEmpty() ? "" : "; scoped by pipeline(s) "
                                    + String.join(", ", scoped.stream().map(RagPipeline::name).toList()));
                })
                .handle((input, caller) -> {
                    VectorStoreDocumentInfo info = requireDocument(input.document());
                    this.ingestService.delete(info.docInfoId());
                    return ManagementResult.persisted("Deleted '" + info.title() + "'.", new NameData(info.title()));
                });
    }

    private ManagementTool listPipelines() {
        return ManagementTool.named("saip_knowledge_list_pipelines", CATEGORY, NoInput.class)
                .data(PipelinesData.class)
                .description("List the RAG pipelines of this app with their stages and full settings. A document "
                        + "can also be searched directly without a pipeline; a pipeline adds query rewriting, "
                        + "translation, multi-query expansion, document scope and retrieval limits.")
                .idempotent()
                .handle((input, caller) -> {
                    List<PipelineData> list = this.pipelineService.list().stream().map(this::pipelineData).toList();
                    return ManagementResult.ok(list.size() + " pipeline(s).", new PipelinesData(list));
                });
    }

    private ManagementTool applyPipeline() {
        return ManagementTool.named("saip_knowledge_apply_pipeline", CATEGORY, PipelineSpec.class)
                .data(PipelineData.class)
                .description("Create a RAG pipeline or update the one with the same name. Same fields as the "
                        + "Pipeline Studio dialog; documents are referenced by title or id, unset fields keep "
                        + "their defaults. Enabled pre-retrieval stages need a chat model, also in a shipped "
                        + "server.")
                .write(spec -> {
                    String name = spec.resolvedName();
                    requireNotDeclaredPipeline(name);
                    Optional<RagPipeline> existing = this.pipelineService.findByName(name);
                    RagPipeline preview = new RagPipeline("preview", name, spec.description(), spec.documents(),
                            spec.preRetrieval().toConfig(), spec.retrieval().toConfig(),
                            spec.postRetrieval().toConfig(), spec.generation().toConfig(), 0L, 0L);
                    return (existing.isPresent() ? "Update pipeline '" : "Create pipeline '") + name + "' over "
                            + (spec.documents().isEmpty() ? "every document" : String.join(", ", spec.documents()))
                            + " with stages " + String.join(" > ", preview.stageLabels());
                })
                .handle((spec, caller) -> {
                    RagPipeline applied = this.pipelineService.apply(spec);
                    return ManagementResult.persisted("Applied pipeline '" + applied.name() + "'.",
                            pipelineData(applied));
                });
    }

    private ManagementTool deletePipeline() {
        return ManagementTool.named("saip_knowledge_delete_pipeline", CATEGORY, PipelineInput.class)
                .data(NameData.class)
                .description("Delete a RAG pipeline. Documents are not touched; shipped servers that searched "
                        + "through it go out of date.")
                .destructive(input -> {
                    RagPipeline pipeline = requirePipeline(input.pipeline());
                    requireNotDeclaredPipeline(pipeline.name());
                    return "Delete pipeline '" + pipeline.name() + "'";
                })
                .handle((input, caller) -> {
                    RagPipeline pipeline = requirePipeline(input.pipeline());
                    this.pipelineService.deleteById(pipeline.id());
                    return ManagementResult.persisted("Deleted pipeline '" + pipeline.name() + "'.",
                            new NameData(pipeline.name()));
                });
    }

    private ManagementTool search() {
        return ManagementTool.named("saip_knowledge_search", CATEGORY, SearchInput.class).data(SearchData.class)
                .description("Run a search exactly as a shipped search_ tool would: same pipeline, same excerpts, "
                        + "same ranking. Use it to check that the knowledge answers the questions before shipping.")
                .idempotent()
                .handle((input, caller) -> {
                    String sourceId = resolveSourceId(input.source());
                    RagPipeline pipeline = this.searchService.resolvePipeline(sourceId)
                            .orElseThrow(() -> new IllegalArgumentException("unknown knowledge source '"
                                    + input.source() + "'"));
                    SearchResult result = this.searchService.search(sourceId, input.query(), input.topK(), Map.of());
                    List<ExcerptData> excerpts = result.excerpts().stream().map(KnowledgeManagementContribution::excerptData)
                            .toList();
                    return ManagementResult.ok(excerpts.isEmpty() ? SearchResult.EMPTY_MESSAGE
                            : excerpts.size() + " excerpt(s)" + (result.truncated() ? ", truncated." : "."),
                            new SearchData(input.source(), pipeline.name(), pipeline.stageLabels(), excerpts,
                                    result.truncated()));
                });
    }

    private ManagementTool suggestTools() {
        return ManagementTool.named("saip_knowledge_suggest_tools", CATEGORY, SuggestInput.class)
                .data(SuggestionsData.class)
                .description("Propose the knowledge tools of a server from what is indexed: one search_ tool per "
                        + "pipeline that scopes documents, the all-documents pipeline for documents no pipeline "
                        + "covers (or those documents one by one), with unique tool names and descriptions. The "
                        + "entries go straight into the knowledge list of saip_shipyard_validate.")
                .idempotent()
                .handle((input, caller) -> {
                    List<String> selected = input.selected() == null ? List.of() : input.selected().stream()
                            .map(this::resolveSourceId).toList();
                    List<SuggestionData> suggestions = this.suggestService.suggest(selected,
                            input.takenToolNames()).stream().map(KnowledgeManagementContribution::suggestionData)
                            .toList();
                    return ManagementResult.ok(suggestions.isEmpty()
                            ? "Nothing to suggest: no indexed document is left uncovered."
                            : suggestions.size() + " knowledge tool(s) suggested; pass them as the knowledge list "
                                    + "of saip_shipyard_validate.", new SuggestionsData(suggestions));
                });
    }

    private ManagementTool status() {
        return ManagementTool.named("saip_knowledge_status", CATEGORY, NoInput.class).data(StatusData.class)
                .description("For every shipped server with knowledge tools: which tools it has and whether their "
                        + "pipeline or documents changed or were removed since it was shipped. A changed server "
                        + "needs saip_shipyard_ship again to pick up the new snapshot.")
                .idempotent()
                .handle((input, caller) -> {
                    List<ServerKnowledgeData> servers = this.shipyardService.servers().stream()
                            .filter(summary -> summary.entry().isPresent())
                            .map(summary -> knowledgeStatus(summary.entry().get()))
                            .filter(status -> !status.knowledgeTools().isEmpty())
                            .toList();
                    long changed = servers.stream().filter(status -> !status.changes().isEmpty()).count();
                    return ManagementResult.ok(servers.size() + " knowledge server(s), " + changed
                            + " out of date.", new StatusData(servers));
                });
    }

    private static IngestProgress ingestProgress(DocumentSpec spec, ManagementCaller caller) {
        ManagementProgress progress = caller.progress();
        return new IngestProgress(
                (done, total) -> progress.report(1 + (double) done / Math.max(total, 1), INGEST_STAGES,
                        "Enriched chunk " + done + " of " + total),
                null,
                (stage, chunks) -> {
                    switch (stage) {
                        case READING -> progress.report(0, INGEST_STAGES, "Reading " + spec.source().describe());
                        case SPLIT -> progress.report(1, INGEST_STAGES, "Split into " + chunks + " chunk(s)");
                        case EMBEDDING -> progress.report(2, INGEST_STAGES, "Embedding " + chunks + " chunk(s)");
                    }
                });
    }

    private ServerKnowledgeData knowledgeStatus(BundleEntry entry) {
        List<String> tools = entry.manifest().contents().ragPipelines().stream()
                .map(BundleManifest.RagPipelineRef::alias).toList();
        List<ChangeData> changes = this.freshnessService.changes(entry).stream()
                .filter(change -> change.source().startsWith(KNOWLEDGE_SOURCE_PREFIX))
                .map(change -> change.knowledge()
                        .map(key -> new ChangeData(key.tool(), key.pipeline() ? "pipeline"
                                : "document " + key.document(), change.change().name()))
                        .orElseGet(() -> new ChangeData(change.label(), "snapshot", change.change().name())))
                .toList();
        return new ServerKnowledgeData(entry.name(), tools, changes);
    }

    private String resolveSourceId(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        String wanted = source.trim();
        if (this.searchService.resolvePipeline(wanted).isPresent()) return wanted;
        Optional<RagPipeline> byName = this.pipelineService.findByName(wanted);
        if (byName.isPresent()) return byName.get().id();
        return this.ingestService.find(wanted).map(info -> "doc:" + info.docInfoId()).orElse(wanted);
    }

    private VectorStoreDocumentInfo requireDocument(String titleOrId) {
        return this.ingestService.find(titleOrId)
                .orElseThrow(() -> new IllegalArgumentException("unknown document '" + titleOrId + "'"));
    }

    private RagPipeline requirePipeline(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) throw new IllegalArgumentException("pipeline is required");
        return this.pipelineService.get(nameOrId.trim()).or(() -> this.pipelineService.findByName(nameOrId))
                .orElseThrow(() -> new IllegalArgumentException("unknown pipeline '" + nameOrId + "'"));
    }

    private void requireNotDeclaredDocument(String title) {
        if (this.knowledgeLoader.managesDocument(title)) {
            throw new IllegalStateException("Document '" + title + "' is managed by configuration; change it under "
                    + ConfiguredKnowledgeLoader.DOCUMENTS_PROPERTY);
        }
    }

    private void requireNotDeclaredPipeline(String name) {
        if (this.knowledgeLoader.managesPipeline(name)) {
            throw new IllegalStateException("Pipeline '" + name + "' is managed by configuration; change it under "
                    + ConfiguredKnowledgeLoader.PIPELINES_PROPERTY);
        }
    }

    private DocumentData documentData(VectorStoreDocumentInfo info) {
        return new DocumentData(info.docInfoId(), info.title(), info.description(), chunkCount(info),
                info.getDocumentFileName(), info.sourceFingerprint(), this.knowledgeLoader.managesDocument(info.title()),
                info.updateTimestamp());
    }

    private PipelineData pipelineData(RagPipeline pipeline) {
        return new PipelineData(pipeline.id(), pipeline.name(), pipeline.stageLabels(), pipeline.extraLlmCallCount(),
                this.knowledgeLoader.managesPipeline(pipeline.name()), pipeline.updatedAt(),
                this.pipelineService.specOf(pipeline));
    }

    private static int chunkCount(VectorStoreDocumentInfo info) {
        return info.documentListSupplier() == null ? 0 : info.documentListSupplier().get().size();
    }

    private static SuggestionData suggestionData(Suggestion suggestion) {
        return new SuggestionData(suggestion.sourceName(), suggestion.knowledge().sourceId(),
                suggestion.knowledge().toolName(), suggestion.knowledge().description(),
                suggestion.reason().name(), suggestion.documents(), suggestion.llmStages());
    }

    private static ExcerptData excerptData(Excerpt excerpt) {
        return new ExcerptData(excerpt.rank(), excerpt.documentId(), excerpt.document(), excerpt.chunk(),
                excerpt.score(), excerpt.text());
    }

}
