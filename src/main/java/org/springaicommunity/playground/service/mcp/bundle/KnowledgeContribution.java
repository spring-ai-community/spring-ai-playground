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

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.config.AbsentModelFallbackConfig.AbsentEmbeddingModel;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentPersistenceService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(3)
public class KnowledgeContribution implements BundleContribution {

    public static final String TOOL_NAME_PREFIX = "search_";
    public static final int MAX_TOP_K = KnowledgeSearchService.MAX_TOP_K;
    static final int MAX_TOOL_NAME_LENGTH = 64;
    static final int OUTPUT_CHAR_BUDGET = KnowledgeSearchService.OUTPUT_CHAR_BUDGET;
    static final String PIPELINES_DIR = "vectorstore/pipelines/";
    public static final String CHUNK_INDEX_METADATA = KnowledgeSearchService.CHUNK_INDEX_METADATA;
    static final String DUMP_FILE = "vectorstore/simpleVectorStore/simpleVectorStore.json";
    static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");
    public static final String SOURCE_PREFIX = "knowledge:";
    public static final String PIPELINE_SOURCE_SUFFIX = "/pipeline";
    public static final String DOCUMENT_SOURCE_SUFFIX = "/doc:";
    private static final int TITLES_IN_DESCRIPTION = 5;
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeContribution.class);

    public record Source(String sourceId, RagPipeline pipeline, List<VectorStoreDocumentInfo> documents,
            List<String> missingDocInfoIds, boolean document) {

        public String name() {
            return this.pipeline.name();
        }
    }

    private final RagPipelineService ragPipelineService;
    private final ObjectProvider<OfflineEtlPipelineService> documentsProvider;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<VectorStoreService> vectorStoreServiceProvider;
    private final ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider;
    private final ObjectProvider<RagPipelineExecutor> executorProvider;
    private final ObjectProvider<ToolSpecService> toolSpecServiceProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final KnowledgeSearchService knowledgeSearchService;
    private final Path homeDir;

    public KnowledgeContribution(RagPipelineService ragPipelineService,
            ObjectProvider<OfflineEtlPipelineService> documentsProvider,
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<VectorStoreService> vectorStoreServiceProvider,
            ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider,
            ObjectProvider<RagPipelineExecutor> executorProvider,
            ObjectProvider<ToolSpecService> toolSpecServiceProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider, KnowledgeSearchService knowledgeSearchService,
            Path springAiPlaygroundHomeDir) {
        this.ragPipelineService = ragPipelineService;
        this.documentsProvider = documentsProvider;
        this.vectorStoreProvider = vectorStoreProvider;
        this.vectorStoreServiceProvider = vectorStoreServiceProvider;
        this.persistenceProvider = persistenceProvider;
        this.executorProvider = executorProvider;
        this.toolSpecServiceProvider = toolSpecServiceProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.knowledgeSearchService = knowledgeSearchService;
        this.homeDir = springAiPlaygroundHomeDir;
    }

    public Optional<Source> resolve(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return Optional.empty();
        List<VectorStoreDocumentInfo> knowledgeDocuments = knowledgeDocuments();
        if (sourceId.startsWith(ChatService.RAG_DOCUMENT_SOURCE_PREFIX)) {
            String docInfoId = sourceId.substring(ChatService.RAG_DOCUMENT_SOURCE_PREFIX.length());
            return knowledgeDocuments.stream().filter(info -> docInfoId.equals(info.docInfoId())).findFirst()
                    .map(info -> new Source(sourceId, KnowledgeSearchService.documentPipeline(info), List.of(info),
                            List.of(), true));
        }
        return this.ragPipelineService.get(sourceId).map(pipeline -> {
            if (pipeline.docInfoIds().isEmpty()) {
                return new Source(sourceId, pipeline, knowledgeDocuments, List.of(), false);
            }
            Map<String, VectorStoreDocumentInfo> byId = knowledgeDocuments.stream()
                    .collect(Collectors.toMap(VectorStoreDocumentInfo::docInfoId, info -> info, (first, second) -> first));
            List<VectorStoreDocumentInfo> documents = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String docInfoId : pipeline.docInfoIds()) {
                VectorStoreDocumentInfo info = byId.get(docInfoId);
                if (info == null) missing.add(docInfoId);
                else documents.add(info);
            }
            return new Source(sourceId, pipeline, documents, missing, false);
        });
    }

    public Optional<String> resolveSourceId(String source) {
        if (source == null || source.isBlank()) return Optional.empty();
        if (resolve(source).isPresent()) return Optional.of(source);
        Optional<RagPipeline> byName = this.ragPipelineService.list().stream()
                .filter(pipeline -> source.equals(pipeline.name())).findFirst();
        if (byName.isPresent()) return byName.map(RagPipeline::id);
        return knowledgeDocuments().stream()
                .filter(info -> source.equals(info.docInfoId()) || source.equals(info.title()))
                .findFirst()
                .map(info -> ChatService.RAG_DOCUMENT_SOURCE_PREFIX + info.docInfoId());
    }

    public static String defaultToolName(String sourceName) {
        String slug = Objects.requireNonNullElse(sourceName, "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) slug = "knowledge";
        int maxSlug = MAX_TOOL_NAME_LENGTH - TOOL_NAME_PREFIX.length();
        if (slug.length() > maxSlug) slug = slug.substring(0, maxSlug).replaceAll("_+$", "");
        return TOOL_NAME_PREFIX + slug;
    }

    public static String defaultDescription(Source source) {
        List<String> titles = source.documents().stream().map(VectorStoreDocumentInfo::title)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        StringBuilder description = new StringBuilder("Search the ").append(source.name())
                .append(" knowledge base (").append(titles.size()).append(titles.size() == 1 ? " document" : " documents");
        if (!titles.isEmpty()) {
            description.append(": ").append(String.join(", ", titles.subList(0, Math.min(titles.size(),
                    TITLES_IN_DESCRIPTION))));
            if (titles.size() > TITLES_IN_DESCRIPTION) {
                description.append(" and ").append(titles.size() - TITLES_IN_DESCRIPTION).append(" more");
            }
        }
        description.append("). Returns the most relevant excerpts with their source document and a similarity ")
                .append("score (higher is closer).");
        String pipelineDescription = source.pipeline().description();
        if (!source.document() && pipelineDescription != null && !pipelineDescription.isBlank()) {
            description.append(' ').append(pipelineDescription.strip());
            if (!pipelineDescription.strip().endsWith(".")) description.append('.');
        }
        return description.toString();
    }

    @Override
    public ShipRequest normalize(ShipRequest request) {
        if (request.knowledge().isEmpty()) return request;
        List<ShipRequest.Knowledge> normalized = new ArrayList<>();
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            Optional<Source> source = resolve(knowledge.sourceId());
            String toolName = knowledge.toolName() == null || knowledge.toolName().isBlank()
                    ? source.map(Source::name).map(KnowledgeContribution::defaultToolName).orElse(null)
                    : knowledge.toolName().strip();
            String description = knowledge.description() == null || knowledge.description().isBlank()
                    ? source.map(KnowledgeContribution::defaultDescription).orElse(null)
                    : knowledge.description().strip();
            Integer topK = knowledge.topK() != null ? knowledge.topK()
                    : source.map(found -> effectiveTopK(found.pipeline())).orElse(null);
            normalized.add(new ShipRequest.Knowledge(knowledge.sourceId(), toolName, description, topK));
        }
        BundleManifest.Models models = request.models();
        if (models.embedding() == null) {
            VectorStoreService vectorStoreService = this.vectorStoreServiceProvider.getIfAvailable();
            if (vectorStoreService != null) {
                models = new BundleManifest.Models(models.chat(), new BundleManifest.ModelRef(
                        vectorStoreService.getEmbeddingModelServiceName().toLowerCase(Locale.ROOT),
                        Objects.requireNonNullElse(vectorStoreService.getEmbeddingOptions().getModel(),
                                vectorStoreService.getEmbeddingModelServiceName())));
            }
        }
        return new ShipRequest(request.serverName(), request.description(), request.version(), request.toolIds(),
                request.transport(), request.httpPort(), models, request.composedTools(),
                request.composedToolsMaxRisk(), normalized);
    }

    @Override
    public void validate(ShipRequest request, ShipDraft draft) {
        if (request.knowledge().isEmpty()) return;
        if (!(this.vectorStoreProvider.getIfAvailable() instanceof SimpleVectorStore)) {
            draft.block("Knowledge can ship only from the built-in SimpleVectorStore; this app uses "
                    + Optional.ofNullable(this.vectorStoreProvider.getIfAvailable())
                            .map(store -> store.getClass().getSimpleName()).orElse("no vector store"));
        }
        if (this.embeddingModelProvider.getIfAvailable() instanceof AbsentEmbeddingModel
                || request.models().embedding() == null) {
            draft.block("Knowledge needs the embedding model that indexed it, but this app has no embedding model");
        }
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            String label = Objects.requireNonNullElse(knowledge.toolName(), knowledge.sourceId());
            draft.ship(new ShippedTool(label, ShippedTool.Kind.KNOWLEDGE, knowledge.description(),
                    RiskLevel.L1.name(), RiskLevel.L1, false));
            Optional<Source> resolved = resolve(knowledge.sourceId());
            if (resolved.isEmpty()) {
                draft.block("Knowledge source '" + knowledge.sourceId() + "' does not exist in this app");
                continue;
            }
            Source source = resolved.get();
            if (knowledge.toolName() == null || !TOOL_NAME_PATTERN.matcher(knowledge.toolName()).matches()) {
                draft.block("Knowledge tool name '" + knowledge.toolName() + "' must be lowercase letters, digits "
                        + "or '_' (max " + MAX_TOOL_NAME_LENGTH + ")");
            } else if (ManagementToolNames.isReserved(knowledge.toolName())) {
                draft.block(ManagementToolNames.reservedMessage("Knowledge tool name", knowledge.toolName()));
            }
            if (!source.missingDocInfoIds().isEmpty()) {
                draft.block("Knowledge '" + label + "' references documents that no longer exist: "
                        + source.missingDocInfoIds());
            }
            if (source.documents().isEmpty()) {
                draft.block("Knowledge '" + label + "' has no documents to ship");
            }
            if (knowledge.topK() != null && (knowledge.topK() < 1 || knowledge.topK() > MAX_TOP_K)) {
                draft.block("Knowledge '" + label + "' top_k must be between 1 and " + MAX_TOP_K);
            }
            if (source.pipeline().extraLlmCallCount() > 0 && request.models().chat() == null) {
                draft.block("Knowledge '" + label + "' runs " + llmStages(source.pipeline())
                        + " before retrieval, which needs a chat model; pick one under Advanced");
            }
            if (source.pipeline().preRetrieval().compression()) {
                draft.warn("Knowledge '" + label + "' compresses the query with conversation history, but MCP "
                        + "calls carry no history; the stage runs on the query alone");
            }
            if (source.pipeline().generation().runAugmentLLM()) {
                draft.warn("Knowledge '" + label + "' ships as search only; its answer generation stage is not "
                        + "exposed");
            }
        }
    }

    private static String llmStages(RagPipeline pipeline) {
        return pipeline.stageLabels().stream()
                .filter(stage -> !stage.equals("Retrieve") && !stage.equals("Augment") && !stage.equals("Re-rank")
                        && !stage.startsWith("Top-"))
                .collect(Collectors.joining(", "));
    }

    @Override
    public void write(ShipRequest request, BundleWriter writer) throws IOException {
        if (request.knowledge().isEmpty()) return;
        Map<String, VectorStoreDocumentInfo> documents = new LinkedHashMap<>();
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            Source source = resolve(knowledge.sourceId()).orElseThrow();
            RagPipeline frozen = freeze(source, knowledge.topK());
            writer.write(PIPELINES_DIR + frozen.id() + ".json",
                    PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(frozen));
            List<BundleManifest.DocumentRef> refs = new ArrayList<>();
            for (VectorStoreDocumentInfo info : source.documents()) {
                documents.putIfAbsent(info.docInfoId(), info);
                refs.add(new BundleManifest.DocumentRef(info.docInfoId(), info.title(), chunkCount(info)));
            }
            writer.ragPipeline(new BundleManifest.RagPipelineRef(frozen.id(), new BundleManifest.Expose(true, false),
                    knowledge.toolName(), knowledge.description(), knowledge.sourceId(), knowledge.topK(), refs));
        }
        String storeDir = storeDirName();
        for (VectorStoreDocumentInfo info : documents.values()) {
            writer.write("vectorstore/save/" + storeDir + "/" + info.docInfoId() + ".json", documentBytes(info));
        }
        writer.write(DUMP_FILE, scopedDump(documents.keySet()));
    }

    RagPipeline freeze(Source source, Integer topK) {
        RagPipeline pipeline = source.pipeline();
        RagPipeline.RetrievalConfig retrieval = pipeline.retrieval();
        VectorStoreService vectorStoreService = this.vectorStoreServiceProvider.getIfAvailable();
        Double threshold = retrieval.similarityThreshold() != null ? retrieval.similarityThreshold()
                : vectorStoreService == null ? null : vectorStoreService.getSearchRequestOption().similarityThreshold();
        RagPipeline.GenerationConfig generation = pipeline.generation();
        return new RagPipeline(pipeline.id(), pipeline.name(), pipeline.description(),
                source.documents().stream().map(VectorStoreDocumentInfo::docInfoId).sorted().toList(),
                pipeline.preRetrieval(),
                new RagPipeline.RetrievalConfig(retrieval.extraFilterExpression(),
                        topK != null ? topK : effectiveTopK(pipeline), threshold),
                pipeline.postRetrieval(),
                new RagPipeline.GenerationConfig(true, generation.promptTemplate(),
                        generation.emptyContextPromptTemplate(), false, generation.documentFormat()),
                pipeline.createdAt(), pipeline.updatedAt());
    }

    private Integer effectiveTopK(RagPipeline pipeline) {
        if (pipeline.retrieval().topK() != null) return pipeline.retrieval().topK();
        VectorStoreService vectorStoreService = this.vectorStoreServiceProvider.getIfAvailable();
        return vectorStoreService == null ? null : vectorStoreService.getSearchRequestOption().topK();
    }

    private byte[] documentBytes(VectorStoreDocumentInfo info) throws IOException {
        Optional<Path> saved = savedDocumentFile(info.docInfoId());
        if (saved.isPresent()) return Files.readAllBytes(saved.get());
        VectorStoreDocumentPersistenceService persistence = this.persistenceProvider.getObject();
        Map<String, Object> map = PersistenceServiceInterface.OBJECT_MAPPER.convertValue(info,
                PersistenceServiceInterface.MAP_TYPE_REFERENCE);
        persistence.buildSaveData(info, map);
        return PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(map);
    }

    private int chunkCount(VectorStoreDocumentInfo info) {
        Optional<Path> saved = savedDocumentFile(info.docInfoId());
        if (saved.isPresent()) {
            try {
                Map<String, Object> map = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                        Files.readAllBytes(saved.get()), PersistenceServiceInterface.MAP_TYPE_REFERENCE);
                if (map.get("documentList") instanceof List<?> chunks) return chunks.size();
            } catch (IOException | RuntimeException e) {
                logger.debug("Could not count chunks of {}: {}", info.docInfoId(), e.getMessage());
            }
        }
        return info.documentListSupplier().get().size();
    }

    private Optional<Path> savedDocumentFile(String docInfoId) {
        VectorStoreDocumentPersistenceService persistence = this.persistenceProvider.getIfAvailable();
        if (persistence == null) return Optional.empty();
        Path file = persistence.getSaveDir().resolve(docInfoId + ".json");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private String storeDirName() {
        VectorStoreDocumentPersistenceService persistence = this.persistenceProvider.getIfAvailable();
        return persistence == null ? "SimpleVectorStore" : persistence.getSaveDir().getFileName().toString();
    }

    private byte[] scopedDump(Set<String> docInfoIds) throws IOException {
        if (!(this.vectorStoreProvider.getIfAvailable() instanceof SimpleVectorStore store)) {
            throw new IllegalStateException("Knowledge can ship only from a SimpleVectorStore");
        }
        Path temp = Files.createTempFile("saip-vectorstore-", ".json");
        try {
            store.save(temp.toFile());
            Map<String, Object> all = PersistenceServiceInterface.OBJECT_MAPPER.readValue(Files.readAllBytes(temp),
                    PersistenceServiceInterface.MAP_TYPE_REFERENCE);
            Map<String, Object> scoped = new LinkedHashMap<>();
            all.forEach((id, content) -> {
                if (content instanceof Map<?, ?> entry && entry.get("metadata") instanceof Map<?, ?> metadata
                        && docInfoIds.contains(String.valueOf(metadata.get(VectorStoreService.DOC_INFO_ID)))) {
                    scoped.put(id, content);
                }
            });
            return PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(scoped);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public Map<String, String> fingerprints(ShipRequest request) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (ShipRequest.Knowledge knowledge : request.knowledge()) {
            Optional<Source> resolved = resolve(knowledge.sourceId());
            if (resolved.isEmpty()) continue;
            Source source = resolved.get();
            String key = SOURCE_PREFIX + knowledge.toolName();
            RagPipeline frozen = freeze(source, knowledge.topK()).withTimestamps(0L, 0L);
            fingerprints.put(key + PIPELINE_SOURCE_SUFFIX, BundleExportService.sha256(
                    PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(frozen)
                            .getBytes(StandardCharsets.UTF_8)));
            Map<String, Long> titleCounts = source.documents().stream()
                    .collect(Collectors.groupingBy(VectorStoreDocumentInfo::title, Collectors.counting()));
            source.documents().stream().sorted((first, second) -> first.docInfoId().compareTo(second.docInfoId()))
                    .forEach(info -> fingerprints.put(key + DOCUMENT_SOURCE_SUFFIX + documentKey(info, titleCounts),
                            documentFingerprint(info)));
        }
        return fingerprints;
    }

    public static String documentKey(VectorStoreDocumentInfo info, Map<String, Long> titleCounts) {
        return titleCounts.getOrDefault(info.title(), 1L) > 1 ? info.title() + "#" + info.docInfoId() : info.title();
    }

    public Optional<String> documentTitle(String docInfoId) {
        return knowledgeDocuments().stream().filter(info -> info.docInfoId().equals(docInfoId))
                .map(VectorStoreDocumentInfo::title).findFirst();
    }

    private String documentFingerprint(VectorStoreDocumentInfo info) {
        if (info.sourceFingerprint() != null) return info.sourceFingerprint();
        Optional<Path> saved = savedDocumentFile(info.docInfoId());
        if (saved.isPresent()) {
            try {
                return BundleExportService.sha256(saved.get());
            } catch (IOException | UncheckedIOException e) {
                logger.debug("Could not hash {}: {}", saved.get(), e.getMessage());
            }
        }
        return info.title() + "@" + info.updateTimestamp();
    }

    @Override
    public void verify(BundleManifest manifest, List<String> problems) {
        List<BundleManifest.RagPipelineRef> refs = manifest.contents().ragPipelines();
        if (refs.isEmpty()) return;
        if (!(this.vectorStoreProvider.getIfAvailable() instanceof SimpleVectorStore)) {
            problems.add("knowledge bundles need the built-in SimpleVectorStore");
        }
        Set<String> present = knowledgeDocuments().stream().map(VectorStoreDocumentInfo::docInfoId)
                .collect(Collectors.toSet());
        for (BundleManifest.RagPipelineRef ref : refs) {
            if (this.ragPipelineService.get(ref.id()).isEmpty()) {
                problems.add("knowledge pipeline " + ref.id() + " for tool '" + ref.alias() + "' is missing");
            }
            for (BundleManifest.DocumentRef document : ref.documents()) {
                if (!present.contains(document.docInfoId())) {
                    problems.add("knowledge document '" + document.title() + "' (" + document.docInfoId()
                            + ") for tool '" + ref.alias() + "' is missing");
                }
            }
        }
        verifyDimensions(problems);
    }

    private void verifyDimensions(List<String> problems) {
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || embeddingModel instanceof AbsentEmbeddingModel) {
            problems.add("knowledge bundles need an embedding model");
            return;
        }
        int indexed = indexedDimensions();
        if (indexed <= 0) return;
        int current;
        try {
            current = embeddingModel.dimensions();
        } catch (RuntimeException e) {
            logger.warn("Embedding model not reachable at startup; knowledge tools stay unavailable until it is: {}",
                    e.getMessage());
            return;
        }
        if (current != indexed) {
            problems.add("embedding model produces " + current + " dimensions but the knowledge was indexed with "
                    + indexed + "; configure the embedding model the bundle was built with");
        }
    }

    private int indexedDimensions() {
        Path dump = this.homeDir.resolve(DUMP_FILE);
        if (!Files.isRegularFile(dump)) return 0;
        try {
            Map<String, Object> all = PersistenceServiceInterface.OBJECT_MAPPER.readValue(Files.readAllBytes(dump),
                    PersistenceServiceInterface.MAP_TYPE_REFERENCE);
            return all.values().stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(entry -> entry.get("embedding")).filter(List.class::isInstance).map(List.class::cast)
                    .mapToInt(List::size).findFirst().orElse(0);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read indexed dimensions: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<McpServerInfo> serve(BundleManifest manifest) {
        ToolSpecService toolSpecService = this.toolSpecServiceProvider.getIfAvailable();
        if (toolSpecService == null) return List.of();
        for (BundleManifest.RagPipelineRef ref : manifest.contents().ragPipelines()) {
            toolSpecService.addServedTool(toolSpecification(ref));
        }
        return List.of();
    }

    SyncToolSpecification toolSpecification(BundleManifest.RagPipelineRef ref) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "What to look for, in natural language"));
        properties.put("top_k", Map.of("type", "integer", "minimum", 1, "maximum", MAX_TOP_K,
                "description", "How many excerpts to return (default " + Objects.requireNonNullElse(ref.topK(), 4)
                        + ")"));
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("query"));
        inputSchema.put("additionalProperties", false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", Map.of(
                "rank", Map.of("type", "integer"),
                "documentId", Map.of("type", "string"),
                "document", Map.of("type", "string"),
                "chunk", Map.of("type", "integer"),
                "score", Map.of("type", "number"),
                "text", Map.of("type", "string")));
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", Map.of(
                "results", Map.of("type", "array", "items", result),
                "truncated", Map.of("type", "boolean")));
        outputSchema.put("required", List.of("results", "truncated"));

        McpSchema.Tool tool = McpSchema.Tool.builder().name(ref.alias()).description(ref.description())
                .inputSchema(inputSchema).outputSchema(outputSchema)
                .annotations(new McpSchema.ToolAnnotations(ref.alias(), true, false, true, false, false))
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> search(ref, request.arguments()))
                .build();
    }

    McpSchema.CallToolResult search(BundleManifest.RagPipelineRef ref, Map<String, Object> arguments) {
        Object queryArgument = arguments == null ? null : arguments.get("query");
        if (queryArgument == null || queryArgument.toString().isBlank()) {
            return McpSchema.CallToolResult.builder().isError(true).addTextContent("query is required").build();
        }
        Integer topK = topKArgument(arguments.get("top_k"));
        if (topK != null && (topK < 1 || topK > MAX_TOP_K)) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("top_k must be between 1 and " + MAX_TOP_K).build();
        }
        KnowledgeSearchService.SearchResult result;
        try {
            result = this.knowledgeSearchService.search(ref.id(), queryArgument.toString(), topK, titlesOf(ref));
        } catch (IllegalStateException e) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("knowledge pipeline for '" + ref.alias() + "' is not available").build();
        } catch (RuntimeException e) {
            logger.warn("Knowledge tool '{}' failed: {}", ref.alias(), e.getMessage());
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("search failed: " + e.getMessage()).build();
        }
        return toCallToolResult(result);
    }

    McpSchema.CallToolResult render(BundleManifest.RagPipelineRef ref, List<Document> documents) {
        return toCallToolResult(this.knowledgeSearchService.render(documents, titlesOf(ref)));
    }

    static McpSchema.CallToolResult toCallToolResult(KnowledgeSearchService.SearchResult result) {
        List<McpSchema.Content> content = result.asTexts().stream()
                .<McpSchema.Content>map(McpSchema.TextContent::new).toList();
        return McpSchema.CallToolResult.builder().content(content).structuredContent(result.asStructured()).build();
    }

    private static Map<String, String> titlesOf(BundleManifest.RagPipelineRef ref) {
        Map<String, String> titles = new LinkedHashMap<>();
        ref.documents().forEach(document -> titles.put(document.docInfoId(), document.title()));
        return titles;
    }

    private static Integer topKArgument(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(raw.toString().strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<VectorStoreDocumentInfo> knowledgeDocuments() {
        OfflineEtlPipelineService documents = this.documentsProvider.getIfAvailable();
        if (documents == null) return List.of();
        return documents.read().stream().filter(info -> !info.chatOrigin()).toList();
    }

}
