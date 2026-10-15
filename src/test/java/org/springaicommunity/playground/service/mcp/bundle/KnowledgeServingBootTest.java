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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "vaadin.productionMode=true", "spring.ai.model.chat=none",
                "spring.ai.model.embedding=ollama", "spring.ai.ollama.embedding.options.model=test-embed",
                "spring.ai.playground.persistence=true", "spring.ai.mcp.server.protocol=STREAMABLE" })
class KnowledgeServingBootTest {

    private static final String DOC_INFO_ID = "docInfoId-knowledge-boot";
    private static final String PIPELINE_ID = "knowledge-boot-pipeline";
    private static final String TOOL_NAME = "search_release_notes";

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEmbedding {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new AxisEmbeddingModel();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private McpClientService mcpClientService;

    private static Path preparedHome;

    @DynamicPropertySource
    static void bundleHome(DynamicPropertyRegistry registry) {
        preparedHome = prepareBundleHome();
        registry.add("spring.ai.playground.user-home", () -> preparedHome.toString());
        registry.add(BundleHome.SOURCE_PROPERTY, () -> preparedHome.resolve("bundle").toString());
    }

    @AfterAll
    static void removePreparedHome() throws IOException {
        if (preparedHome != null) {
            FileSystemUtils.deleteRecursively(preparedHome);
        }
    }

    private static Path prepareBundleHome() {
        try {
            Path userHome = Files.createTempDirectory("saip-knowledge-serving-");
            Path homeDir = userHome.resolve("bundle");
            Path pipelinesDir = homeDir.resolve("vectorstore").resolve("pipelines");
            Path saveDir = homeDir.resolve("vectorstore").resolve("save").resolve("SimpleVectorStore");
            Path dumpDir = homeDir.resolve("vectorstore").resolve("simpleVectorStore");
            Files.createDirectories(pipelinesDir);
            Files.createDirectories(saveDir);
            Files.createDirectories(dumpDir);

            Document alpha = new Document("chunk-alpha", "alpha release shipped on Monday",
                    Map.of(VectorStoreService.DOC_INFO_ID, DOC_INFO_ID, KnowledgeContribution.CHUNK_INDEX_METADATA, 0));
            Document beta = new Document("chunk-beta", "beta hotfix shipped on Friday",
                    Map.of(VectorStoreService.DOC_INFO_ID, DOC_INFO_ID, KnowledgeContribution.CHUNK_INDEX_METADATA, 1));
            SimpleVectorStore store = SimpleVectorStore.builder(new AxisEmbeddingModel()).build();
            store.add(List.of(alpha, beta));
            Path dumpFile = dumpDir.resolve("simpleVectorStore.json");
            store.save(dumpFile.toFile());

            Map<String, Object> documentInfo = new LinkedHashMap<>();
            documentInfo.put("docInfoId", DOC_INFO_ID);
            documentInfo.put("title", "Release Notes");
            documentInfo.put("description", "what shipped when");
            documentInfo.put("createTimestamp", 1L);
            documentInfo.put("updateTimestamp", 1L);
            documentInfo.put("documentFileName", "release-notes.txt");
            documentInfo.put("documentPath", "");
            documentInfo.put("chatOrigin", false);
            documentInfo.put("documentList", List.of(
                    Map.of("id", "chunk-alpha", "text", alpha.getText(), "metadata", alpha.getMetadata()),
                    Map.of("id", "chunk-beta", "text", beta.getText(), "metadata", beta.getMetadata())));
            Path documentFile = saveDir.resolve(DOC_INFO_ID + ".json");
            Files.write(documentFile, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(documentInfo));

            RagPipeline pipeline = new RagPipeline(PIPELINE_ID, "Release notes", null, List.of(DOC_INFO_ID),
                    RagPipeline.PreRetrievalConfig.defaults(), new RagPipeline.RetrievalConfig(null, 2, 0.35),
                    RagPipeline.PostRetrievalConfig.defaults(),
                    new RagPipeline.GenerationConfig(true, null, null, false), 1L, 1L);
            Path pipelineFile = pipelinesDir.resolve(PIPELINE_ID + ".json");
            Files.write(pipelineFile, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(pipeline));

            BundleManifest manifest = new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                    new BundleManifest.Server("knowledge-boot-test", "knowledge serving test", "0.1.0"),
                    new BundleManifest.Requires("0.2.0", List.of(), new BundleManifest.Models(null,
                            new BundleManifest.ModelRef("ollama", "test-embed")), List.of()),
                    new BundleManifest.Contents(List.of(), null, List.of(new BundleManifest.RagPipelineRef(
                            PIPELINE_ID, new BundleManifest.Expose(true, false), TOOL_NAME,
                            "Search the release notes", PIPELINE_ID, 2,
                            List.of(new BundleManifest.DocumentRef(DOC_INFO_ID, "Release Notes", 2)))), null),
                    new BundleManifest.Risk("L1", List.of(new BundleManifest.ToolRisk(TOOL_NAME, "L1", false,
                            "KNOWLEDGE", "Search the release notes"))),
                    new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, Map.of(
                            "home/vectorstore/pipelines/" + PIPELINE_ID + ".json", BundleExportService.sha256(pipelineFile),
                            "home/vectorstore/save/SimpleVectorStore/" + DOC_INFO_ID + ".json",
                            BundleExportService.sha256(documentFile),
                            "home/" + KnowledgeContribution.DUMP_FILE, BundleExportService.sha256(dumpFile)),
                            Map.of()),
                    new BundleManifest.Provenance("test", Instant.now().toString()));
            Files.write(homeDir.resolve(BundleManifest.FILE_NAME),
                    PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(manifest));
            return userHome;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare knowledge bundle home", e);
        }
    }

    @Test
    void theKnowledgeToolSearchesTheShippedSnapshot() throws Exception {
        long now = System.currentTimeMillis();
        McpServerInfo serverInfo = new McpServerInfo(McpTransportType.STREAMABLE_HTTP,
                "knowledge-boot-loopback", "", now, now,
                PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(
                        new McpStreamableHttpClientProperties.ConnectionParameters(
                                "http://127.0.0.1:" + this.port, null)));
        this.mcpClientService.startMcpClient(serverInfo);
        try {
            List<McpSchema.Tool> tools = this.mcpClientService.getToolListAsOpt(serverInfo).orElseThrow();
            assertThat(tools).extracting(McpSchema.Tool::name).containsExactly(TOOL_NAME);
            assertThat(tools.getFirst().outputSchema()).isNotNull();

            McpSchema.CallToolResult result = this.mcpClientService
                    .callTool(serverInfo, TOOL_NAME, Map.of("query", "when did alpha ship", "top_k", 1), Map.of())
                    .orElseThrow();
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).hasSize(1);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .startsWith("[1] Release Notes · chunk 1 · score 1.00").contains("alpha release shipped on Monday");
            Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
            assertThat(structured.get("truncated")).isEqualTo(false);
            assertThat((List<?>) structured.get("results")).hasSize(1);
        } finally {
            this.mcpClientService.stopMcpClient(serverInfo);
        }
    }

}
