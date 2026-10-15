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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ExportResult;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ValidationReport;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService.ToolSpecsMcpServerSetting;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BundleExportServiceTest {

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private ToolSpecPersistenceService toolSpecPersistenceService;

    @Autowired
    private ToolActivationCalculator activationCalculator;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private BundleFreshnessService bundleFreshnessService;

    @BeforeEach
    void seedTools() {
        toolSpecService.update(spec("bx-1", "bundleEchoTool", false,
                List.of(Map.entry("apiKey", "${BUNDLE_TEST_API_KEY}"))));
        toolSpecService.update(spec("bx-2", "bundleDraftTool", true, List.of()));
        toolSpecService.update(spec("bx-3", "bundleLeakyTool", false,
                List.of(Map.entry("serviceToken", "sk-live-0123456789abcdefghij"))));
    }

    @AfterEach
    void cleanup() {
        bundleRegistryService.delete("bundle-test-server");
        toolSpecService.deleteToolSpec("bx-1");
        toolSpecService.deleteToolSpec("bx-2");
        toolSpecService.deleteToolSpec("bx-3");
        toolSpecService.deleteToolSpec("bx-4");
        for (McpTransportType transport : McpTransportType.values()) {
            for (String upstream : List.of("bx-upstream", "bx-other", "bx-stdio", "bx-sse", "bx-oauth",
                    "bx-leaky")) {
                mcpServerInfoService.deleteMcpServerInfo(transport, upstream);
            }
        }
    }

    private void registerUpstream(McpTransportType transport, String name, String connectionAsJson) {
        mcpServerInfoService.loadAll(() -> mcpServerInfoService.updateMcpServerInfo(transport, name,
                new McpServerInfo(transport, name, "upstream " + name, 1L, 1L, connectionAsJson)));
    }

    private static ShipRequest proxiedRequest(List<String> toolIds, McpComposition.Member... members) {
        return new ShipRequest("bundle-test-server", "test bundle", "0.1.0", toolIds, Transport.BOTH,
                null, null, List.of(members), RiskLevel.L3);
    }

    private static McpComposition.Member member(String server, String tool, String alias) {
        return new McpComposition.Member(server, tool, alias, null, false, "");
    }

    private static ToolSpec spec(String id, String name, boolean draft,
            List<Map.Entry<String, String>> staticVariables) {
        return new ToolSpec(id, name, "test tool " + name, staticVariables,
                List.of(new ToolParamSpec("text", "input", true, JsonSchemaType.STRING, "hello")),
                "return text;", CodeType.Javascript, null).withDraft(draft);
    }

    private static ShipRequest request(List<String> toolIds) {
        return new ShipRequest("bundle-test-server", "test bundle", "0.1.0", toolIds,
                Transport.BOTH, null, null);
    }

    @Test
    void validateBlocksDraftUnknownAndBadName() {
        ValidationReport report =
                bundleExportService.validate(request(List.of("bx-2", "no-such-id")));
        assertThat(report.exportable()).isFalse();
        assertThat(report.blockers())
                .anyMatch(blocker -> blocker.contains("draft"))
                .anyMatch(blocker -> blocker.contains("no-such-id"));

        ValidationReport badName = bundleExportService.validate(
                new ShipRequest("bad name!", null, "0.1.0", List.of("bx-1"), Transport.HTTP,
                        null, null));
        assertThat(badName.blockers()).anyMatch(blocker -> blocker.contains("Server name"));

        ValidationReport empty = bundleExportService.validate(request(List.of()));
        assertThat(empty.blockers()).anyMatch(blocker -> blocker.contains("at least one tool"));
    }

    @Test
    void validateCollectsEnvRequirementsAndSecretWarnings() {
        ValidationReport report =
                bundleExportService.validate(request(List.of("bx-1", "bx-3")));
        assertThat(report.exportable()).isTrue();
        assertThat(report.envRequirements())
                .anyMatch(envVar -> envVar.name().equals("BUNDLE_TEST_API_KEY") && envVar.secret());
        assertThat(report.warnings())
                .anyMatch(warning -> warning.contains("bundleLeakyTool") && warning.contains("serviceToken"));
        assertThat(report.warnings())
                .anyMatch(warning -> warning.contains("bundleEchoTool")
                        && warning.contains("could not run locally"));
    }

    @Test
    void exportWritesBundleAndRegistryFindsIt() throws Exception {
        ExportResult result = bundleExportService.exportBundle(request(List.of("bx-1")));

        assertThat(result.bundleDir()).exists();
        assertThat(result.bundleDir().resolve("home/saip-bundle.json")).exists();
        assertThat(result.bundleDir().resolve("home/tool/save/toolSpecsMcpSetting.json")).exists();
        assertThat(result.bundleDir().resolve("Dockerfile")).exists();
        assertThat(result.bundleDir().resolve("compose.yaml")).exists();
        assertThat(result.bundleDir().resolve("README.md")).exists();
        assertThat(result.bundleDir().resolve("server.json")).exists();

        BundleManifest manifest = result.manifest();
        assertThat(manifest.contents().tools()).containsExactly("bx-1");
        assertThat(manifest.requires().models().modelFree()).isTrue();
        assertThat(manifest.integrity().tools()).containsKey("bundleEchoTool");
        assertThat(manifest.requires().env())
                .anyMatch(envVar -> envVar.name().equals("BUNDLE_TEST_API_KEY"));

        ToolSpecsMcpServerSetting written = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                Files.readAllBytes(result.bundleDir().resolve("home/tool/save/toolSpecsMcpSetting.json")),
                ToolSpecsMcpServerSetting.class);
        assertThat(written.toolSpecs()).extracting(ToolSpec::name).containsExactly("bundleEchoTool");
        assertThat(written.toolMcpServerSetting().autoAdd()).isFalse();
        assertThat(written.toolMcpServerSetting().exposedToolIds()).containsExactly("bx-1");

        String dockerfile = Files.readString(result.bundleDir().resolve("Dockerfile"));
        assertThat(dockerfile).contains("COPY home /opt/saip-bundle\n")
                .contains("ENV SPRING_AI_PLAYGROUND_BUNDLE_SOURCE=/opt/saip-bundle\n")
                .contains("ENV SPRING_AI_MCP_SERVER_PROTOCOL=STREAMABLE\n")
                .contains("ENV SPRING_AI_PLAYGROUND_BUILTINMCPSERVER_NAME=bundle-test-server\n")
                .contains("ENV SPRING_AI_MCP_SERVER_VERSION=0.1.0\n")
                .contains("ENV MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus,bundle\n")
                .contains("ENV SPRING_AI_MODEL_CHAT=none\n").contains("ENV SPRING_AI_MODEL_EMBEDDING=none\n")
                .doesNotContain("OLLAMA");

        String compose = Files.readString(result.bundleDir().resolve("compose.yaml"));
        assertThat(compose).contains("    build: .\n")
                .contains("    image: saip-mcp/bundle-test-server:0.1.0\n")
                .contains("host.docker.internal:host-gateway")
                .contains("BUNDLE_TEST_API_KEY: \"${BUNDLE_TEST_API_KEY}\"")
                .doesNotContain("volumes").doesNotContain("SPRING_AI_MODEL_CHAT");

        String serverJson = Files.readString(result.bundleDir().resolve("server.json"));
        assertThat(serverJson).contains("io.github.OWNER/bundle-test-server")
                .contains("\"registryType\"").contains("\"oci\"")
                .contains("BUNDLE_TEST_API_KEY")
                .contains("mcp-stdio").doesNotContain("SPRING_AI_MODEL_CHAT");

        String readme = Files.readString(result.bundleDir().resolve("README.md"));
        assertThat(readme).contains("docker compose up -d --build")
                .contains("\"--add-host\", \"host.docker.internal:host-gateway\"")
                .contains("\"saip-mcp/bundle-test-server:0.1.0\"").doesNotContain("\"-v\"")
                .contains("docker tag saip-mcp/bundle-test-server:0.1.0 ghcr.io/OWNER/bundle-test-server:0.1.0");

        assertThat(bundleRegistryService.list())
                .anyMatch(entry -> entry.name().equals("bundle-test-server"));
        BundleRegistryService.BundleEntry entry =
                bundleRegistryService.find("bundle-test-server").orElseThrow();
        assertThat(entry.buildConfig().toolIds()).containsExactly("bx-1");

        bundleExportService.exportBundle(request(List.of("bx-1", "bx-3")));
        assertThat(bundleRegistryService.find("bundle-test-server").orElseThrow()
                .manifest().contents().tools()).containsExactly("bx-1", "bx-3");
    }

    @Test
    void reexportRewritesHomeFromScratch() throws Exception {
        Path home = bundleExportService.exportBundle(request(List.of("bx-1"))).bundleDir()
                .resolve(BundleManifest.HOME_DIR);
        Path salt = home.resolve(".security").resolve("oauth.salt");
        Path log = home.resolve("logs").resolve("spring-ai-playground.log");
        for (Path residue : List.of(salt, log)) {
            Files.createDirectories(residue.getParent());
            Files.writeString(residue, "not part of the bundle");
        }

        bundleExportService.exportBundle(request(List.of("bx-1")));

        assertThat(home.resolve(".security")).doesNotExist();
        assertThat(home.resolve("logs")).doesNotExist();
        assertThat(home.resolve(BundleManifest.FILE_NAME)).exists();
        assertThat(home.resolve("tool/save/toolSpecsMcpSetting.json")).exists();
    }

    @Test
    void dockerignoreSendsOnlyHomeToTheImageBuild() throws Exception {
        ExportResult result = bundleExportService.exportBundle(request(List.of("bx-1")));
        Path dockerignore = result.bundleDir().resolve(".dockerignore");

        assertThat(Files.readAllLines(dockerignore)).containsExactly("*", "!home", "!home/**");
        assertThat(result.files()).contains(dockerignore);
    }

    @Test
    void validateWarnsBeforeReplacingAnExistingServer() {
        assertThat(bundleExportService.validate(request(List.of("bx-1"))).warnings())
                .noneMatch(warning -> warning.contains("already exists"));

        bundleExportService.exportBundle(request(List.of("bx-1")));

        assertThat(bundleExportService.validate(request(List.of("bx-1"))).warnings())
                .anyMatch(warning -> warning.startsWith("Server 'bundle-test-server' already exists"));
    }

    @Test
    void proxiedToolsShipWithOnlyTheirOwnUpstreamConnection() throws Exception {
        String connection = "{\"url\":\"https://mcp.example.com\",\"endpoint\":\"/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer ${BUNDLE_UPSTREAM_TOKEN}\"}}";
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-upstream", connection);
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-other", "{\"url\":\"https://other.example.com\"}");

        ExportResult result = bundleExportService.exportBundle(proxiedRequest(List.of("bx-1"),
                member("bx-upstream", "search", "bx_search")));

        Path saveDir = result.bundleDir().resolve("home/mcp/save");
        assertThat(saveDir.resolve("STREAMABLE_HTTP-bx-upstream.json")).exists();
        assertThat(saveDir.resolve("STREAMABLE_HTTP-bx-other.json")).doesNotExist();
        BundleManifest manifest = result.manifest();
        assertThat(manifest.requires().upstreamServers()).containsExactly("STREAMABLE_HTTP-bx-upstream");
        assertThat(manifest.integrity().files()).containsEntry("home/mcp/save/STREAMABLE_HTTP-bx-upstream.json",
                BundleExportService.sha256(saveDir.resolve("STREAMABLE_HTTP-bx-upstream.json")));
        assertThat(manifest.contents().composedMembers()).extracting(McpComposition.Member::exposedAlias)
                .containsExactly("bx_search");
        assertThat(manifest.contents().composedTools().maxRiskLevel()).isEqualTo("L3");
        assertThat(manifest.contents().toolCount()).isEqualTo(2);
        assertThat(manifest.requires().env())
                .anyMatch(envVar -> envVar.name().equals("BUNDLE_UPSTREAM_TOKEN") && envVar.secret());
        assertThat(Files.readString(result.bundleDir().resolve("compose.yaml")))
                .contains("BUNDLE_UPSTREAM_TOKEN: \"${BUNDLE_UPSTREAM_TOKEN}\"");
        assertThat(Files.readString(result.bundleDir().resolve("README.md")))
                .contains("## Proxied tools").contains("| bx_search | bx-upstream | search |");
        assertThat(Files.readString(saveDir.resolve("STREAMABLE_HTTP-bx-upstream.json")))
                .doesNotContain("lastUsedAtEpochMs\":1");
    }

    @Test
    void aLoopbackUpstreamIsShippedAsTheHostTheContainerReaches() throws Exception {
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-upstream",
                "{\"url\":\"http://127.0.0.1:18611\",\"endpoint\":\"/mcp\"}");

        ExportResult result = bundleExportService.exportBundle(proxiedRequest(List.of("bx-1"),
                member("bx-upstream", "search", "bx_search")));

        String shipped = Files.readString(result.bundleDir().resolve("home/mcp/save/STREAMABLE_HTTP-bx-upstream.json"));
        assertThat(shipped).contains("http://host.docker.internal:18611").contains("/mcp").doesNotContain("127.0.0.1");
    }

    @Test
    void upstreamsThatCannotRunInTheBundleBlockShipping() {
        registerUpstream(McpTransportType.STDIO, "bx-stdio", "{\"command\":\"npx\",\"args\":[\"server\"]}");
        registerUpstream(McpTransportType.SSE, "bx-sse", "{\"url\":\"https://sse.example.com\"}");
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-oauth",
                "{\"url\":\"https://mcp.example.com\",\"oauth\":{\"client-id\":\"saip\"}}");
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-leaky",
                "{\"url\":\"https://mcp.example.com/mcp?api_key=sk-live-0123456789abcdef\","
                        + "\"headers\":{\"Authorization\":\"Bearer sk-live-0123456789abcdef\"}}");

        ValidationReport report = bundleExportService.validate(proxiedRequest(List.of("bx-1"),
                member("bx-stdio", "run", "bx_run"), member("bx-sse", "stream", "bx_stream"),
                member("bx-oauth", "read", "bx_read"),
                member("bx-leaky", "fetch", "bx_fetch"), member("bx-missing", "any", "bx_any"),
                member("bx-upstream-none", "echo", "bundleEchoTool")));

        assertThat(report.exportable()).isFalse();
        assertThat(report.blockers())
                .anyMatch(blocker -> blocker.contains("'bx-stdio' runs as a local stdio command"))
                .anyMatch(blocker -> blocker.contains("'bx-sse' uses sse; Shipyard only ships Streamable HTTP"))
                .anyMatch(blocker -> blocker.contains("'bx-oauth' signs in with OAuth"))
                .anyMatch(blocker -> blocker.contains("'bx-leaky' header 'Authorization' holds a literal secret"))
                .anyMatch(blocker -> blocker.contains("'bx-leaky' URL parameter 'api_key' holds a literal secret"))
                .anyMatch(blocker -> blocker.contains("needs upstream server 'bx-missing'"))
                .anyMatch(blocker -> blocker.contains("Two tools are named 'bundleEchoTool' (Tool and Proxied)"));
    }

    @Test
    void aProxyOnlyServerIsExportable() {
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-upstream", "{\"url\":\"https://mcp.example.com\"}");

        ValidationReport report = bundleExportService.validate(proxiedRequest(List.of(),
                member("bx-upstream", "search", "bx_search")));

        assertThat(report.blockers()).isEmpty();
    }

    @Test
    void freshnessReportsToolAndUpstreamEditsMadeAfterShipping() {
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-upstream", "{\"url\":\"https://mcp.example.com\"}");
        bundleExportService.exportBundle(proxiedRequest(List.of("bx-1", "bx-3"),
                member("bx-upstream", "search", "bx_search")));
        BundleRegistryService.BundleEntry shipped = bundleRegistryService.find("bundle-test-server").orElseThrow();
        assertThat(shipped.manifest().provenance().sources()).containsOnlyKeys("tool:bundleEchoTool",
                "tool:bundleLeakyTool", "upstream:bx-upstream");
        assertThat(bundleFreshnessService.changes(shipped)).isEmpty();

        toolSpecService.update(spec("bx-1", "bundleEchoTool", false, List.of()));
        toolSpecService.deleteToolSpec("bx-3");
        registerUpstream(McpTransportType.STREAMABLE_HTTP, "bx-upstream", "{\"url\":\"https://moved.example.com\"}");

        assertThat(bundleFreshnessService.changes(shipped)).containsExactlyInAnyOrder(
                new BundleFreshnessService.SourceChange("tool:bundleEchoTool", BundleFreshnessService.Change.CHANGED),
                new BundleFreshnessService.SourceChange("tool:bundleLeakyTool", BundleFreshnessService.Change.REMOVED),
                new BundleFreshnessService.SourceChange("upstream:bx-upstream", BundleFreshnessService.Change.CHANGED));
    }

    @Test
    void versionMustBeAValidImageTag() {
        ValidationReport report = bundleExportService.validate(new ShipRequest("bundle-test-server",
                "test bundle", "1.0 beta", List.of("bx-1"), Transport.BOTH, null, null));

        assertThat(report.blockers()).anyMatch(blocker -> blocker.startsWith("Version must be a valid image tag"));
    }

    @Test
    void anOllamaModelIsReachedThroughTheDockerHostAndCanBeRedirected() throws Exception {
        ExportResult result = bundleExportService.exportBundle(new ShipRequest("bundle-test-server",
                "test bundle", "0.1.0", List.of("bx-1"), Transport.BOTH, null,
                new BundleManifest.Models(new BundleManifest.ModelRef("ollama", "qwen3.5:4b"), null)));

        assertThat(result.manifest().requires().env()).noneMatch(envVar -> envVar.name().contains("OLLAMA"));
        assertThat(Files.readString(result.bundleDir().resolve("Dockerfile")))
                .contains("ENV SPRING_AI_MODEL_CHAT=ollama\n")
                .contains("ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=qwen3.5:4b\n")
                .contains("ENV SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434\n")
                .contains("ENV SPRING_AI_OLLAMA_INIT_PULLMODELSTRATEGY=never\n");
        assertThat(Files.readString(result.bundleDir().resolve("compose.yaml")))
                .contains("SPRING_AI_OLLAMA_BASE_URL: \"${SAIP_OLLAMA_BASE_URL:-http://host.docker.internal:11434}\"");
        assertThat(Files.readString(result.bundleDir().resolve("server.json")))
                .contains("\"--add-host\"").contains("host.docker.internal:host-gateway");
    }

    @Test
    void anOpenAiModelPinsItsModelAndRequiresTheApiKey() throws Exception {
        ExportResult result = bundleExportService.exportBundle(new ShipRequest("bundle-test-server",
                "test bundle", "0.1.0", List.of("bx-1"), Transport.BOTH, null,
                new BundleManifest.Models(null, new BundleManifest.ModelRef("openai", "text-embedding-3-small"))));

        assertThat(result.manifest().requires().env())
                .anyMatch(envVar -> envVar.name().equals("SPRING_AI_OPENAI_APIKEY") && envVar.secret());
        assertThat(Files.readString(result.bundleDir().resolve("Dockerfile")))
                .contains("ENV SPRING_AI_MODEL_EMBEDDING=openai\n")
                .contains("ENV SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL=text-embedding-3-small\n")
                .doesNotContain("OLLAMA");
    }

    @Test
    void exportRefusesNonExportableRequest() {
        assertThatThrownBy(() -> bundleExportService.exportBundle(request(List.of("bx-2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void validateBlocksDuplicateToolNames() {
        toolSpecService.update(spec("bx-4", "bundleEchoTool", false, List.of()));

        ValidationReport report =
                bundleExportService.validate(request(List.of("bx-1", "bx-4")));

        assertThat(report.exportable()).isFalse();
        assertThat(report.blockers())
                .anyMatch(blocker -> blocker.contains("Two tools are named 'bundleEchoTool' (Tool and Tool)"));
    }

    @Test
    void exportDeclaresBuiltInToolsByIdWithoutCopyingSpecs() throws Exception {
        ToolSpec builtIn = toolSpecPersistenceService.getDefaultToolSpecs().stream()
                .filter(spec -> spec.toolId() != null && !activationCalculator.hasMissingEnvVars(spec))
                .min(Comparator.comparing(ToolSpec::toolId)).orElseThrow();
        toolSpecService.update(builtIn.withDraft(false));
        String builtInId = builtIn.toolId();

        ExportResult result =
                bundleExportService.exportBundle(request(List.of("bx-1", builtInId)));

        assertThat(result.manifest().contents().tools()).containsExactlyInAnyOrder("bx-1", builtInId);
        ToolSpecsMcpServerSetting written = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                Files.readAllBytes(result.bundleDir().resolve("home/tool/save/toolSpecsMcpSetting.json")),
                ToolSpecsMcpServerSetting.class);
        assertThat(written.toolSpecs()).extracting(ToolSpec::name).containsExactly("bundleEchoTool");
        assertThat(written.toolMcpServerSetting().exposedToolIds())
                .containsExactlyInAnyOrder("bx-1", builtInId);
    }

}
