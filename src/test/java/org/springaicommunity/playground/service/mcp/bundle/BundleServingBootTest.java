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
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService.ToolSpecsMcpServerSetting;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolMcpServerSetting;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "vaadin.productionMode=true", "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none", "spring.ai.playground.persistence=true",
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "management.endpoints.web.exposure.include=health,bundle" })
class BundleServingBootTest {

    private static final String TOOL_ID = "bundled-echo-1";
    private static final String TOOL_NAME = "bundledEcho";

    @LocalServerPort
    private int port;

    @Autowired
    private McpClientService mcpClientService;

    @Autowired
    private Path homeDir;

    private static Path preparedHome;
    private static Map<String, String> bundleAtStart;

    @DynamicPropertySource
    static void bundleHome(DynamicPropertyRegistry registry) {
        preparedHome = prepareBundleHome();
        registry.add("spring.ai.playground.user-home", () -> preparedHome.toString());
        registry.add(BundleHome.SOURCE_PROPERTY, () -> bundleSource().toString());
    }

    private static Path bundleSource() {
        return preparedHome.resolve("spring-ai-playground-test");
    }

    private static Map<String, String> snapshot(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            Map<String, String> contents = new TreeMap<>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(dir.relativize(file).toString(), Files.readString(file));
            }
            return contents;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void removePreparedHome() throws IOException {
        if (preparedHome != null) {
            FileSystemUtils.deleteRecursively(preparedHome);
        }
    }

    private static Path prepareBundleHome() {
        try {
            Path userHome = Files.createTempDirectory("saip-bundle-serving-");
            Path homeDir = userHome.resolve("spring-ai-playground-test");
            Path toolSaveDir = homeDir.resolve("tool").resolve("save");
            Files.createDirectories(toolSaveDir);

            ToolSpec spec = new ToolSpec(TOOL_ID, TOOL_NAME, "echoes the input text",
                    List.of(),
                    List.of(new ToolParamSpec("text", "text to echo", true, JsonSchemaType.STRING, "hi")),
                    "return text;", CodeType.Javascript, null).withDraft(false);
            ToolSpecsMcpServerSetting setting = new ToolSpecsMcpServerSetting(List.of(spec),
                    new ToolMcpServerSetting(false, Set.of(TOOL_ID)));
            Path settingFile = toolSaveDir.resolve("toolSpecsMcpSetting.json");
            Files.write(settingFile, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(setting));

            CanonicalHasher hasher = new CanonicalHasher(PersistenceServiceInterface.OBJECT_MAPPER);
            BundleManifest manifest = new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                    new BundleManifest.Server("serving-boot-test", "serving test bundle", "0.1.0"),
                    new BundleManifest.Requires("0.2.0", List.of(), new BundleManifest.Models(null, null),
                            List.of()),
                    new BundleManifest.Contents(List.of(TOOL_ID), null, null, null),
                    new BundleManifest.Risk("L0",
                            List.of(new BundleManifest.ToolRisk(TOOL_NAME, "L0", false))),
                    new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME,
                            Map.of("home/tool/save/toolSpecsMcpSetting.json",
                                    BundleExportService.sha256(settingFile)),
                            Map.of(TOOL_NAME, hasher.hashTool(spec))),
                    new BundleManifest.Provenance("test", Instant.now().toString()));
            Files.write(homeDir.resolve(BundleManifest.FILE_NAME),
                    PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(manifest));
            bundleAtStart = snapshot(homeDir);
            return userHome;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare bundle home", e);
        }
    }

    @Test
    void theBundleSourceIsServedFromAFreshCopyAndNeverWritten() throws Exception {
        HttpResponse<String> status;
        try (HttpClient client = HttpClient.newHttpClient()) {
            status = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + this.port + "/actuator/bundle")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"serving\":true").contains("\"server\":\"serving-boot-test\"");
        assertThat(this.homeDir).isNotEqualTo(bundleSource());
        assertThat(this.homeDir.resolve(BundleManifest.FILE_NAME)).exists();
        assertThat(snapshot(bundleSource())).isEqualTo(bundleAtStart).hasSize(2);
        assertThat(new BundleHealthProbe().bundleStatus("http://127.0.0.1:" + this.port, Optional.empty())).hasValueSatisfying(
                bundleStatus -> assertThat(bundleStatus.server()).isEqualTo("serving-boot-test"));
    }

    @Test
    void bundleHomeBootsModelFreeAndServesExactlyTheManifestTools() throws Exception {
        long now = System.currentTimeMillis();
        McpServerInfo serverInfo = new McpServerInfo(McpTransportType.STREAMABLE_HTTP,
                "bundle-serving-loopback", "", now, now,
                PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(
                        new McpStreamableHttpClientProperties.ConnectionParameters(
                                "http://127.0.0.1:" + this.port, null)));
        this.mcpClientService.startMcpClient(serverInfo);
        try {
            List<McpSchema.Tool> tools = this.mcpClientService.getToolListAsOpt(serverInfo).orElseThrow();
            assertThat(tools).extracting(McpSchema.Tool::name).containsExactly(TOOL_NAME);

            McpSchema.CallToolResult result = this.mcpClientService
                    .callTool(serverInfo, TOOL_NAME, Map.of("text", "ping-from-bundle"), Map.of())
                    .orElseThrow();
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .contains("ping-from-bundle");
        } finally {
            this.mcpClientService.stopMcpClient(serverInfo);
        }
    }

}
