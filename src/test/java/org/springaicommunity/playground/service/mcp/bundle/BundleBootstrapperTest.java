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
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;
import org.springaicommunity.playground.service.mcp.risk.DefaultIntegrityVerifier;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolMcpServerSetting;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleBootstrapperTest {

    private static final String UPSTREAM_STEM = "STREAMABLE_HTTP-DeepWiki";
    private static final String UPSTREAM_CONNECTION = "{\"url\":\"https://mcp.deepwiki.com\",\"endpoint\":\"/mcp\"}";
    private static final McpServerInfo DEEPWIKI_UPSTREAM = new McpServerInfo(McpTransportType.STREAMABLE_HTTP,
            "DeepWiki", "d", 1L, 1L, UPSTREAM_CONNECTION);
    private static final McpComposition.Member DEEPWIKI_MEMBER = new McpComposition.Member("DeepWiki",
            "read_wiki_structure", "deepwiki_read_wiki_structure", null, false, "");

    @TempDir
    Path homeDir;

    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final McpExposedToolService exposedToolService = mock(McpExposedToolService.class);
    private final McpCompositionService compositionService = mock(McpCompositionService.class);
    private final McpServerInfoService mcpServerInfoService = mock(McpServerInfoService.class);
    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final ToolSpecPersistenceService persistenceService = mock(ToolSpecPersistenceService.class);
    private final CanonicalHasher hasher = new CanonicalHasher(PersistenceServiceInterface.OBJECT_MAPPER);
    private final BundleDependencyMonitor dependencyMonitor = mock(BundleDependencyMonitor.class);
    private final MockEnvironment environment = new MockEnvironment();
    private BundleReadiness bundleReadiness;
    private BundleBootstrapper bootstrapper;
    private ToolSpec spec;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(this.homeDir.resolve(BundleManifest.FILE_NAME), "{}");
        this.bundleReadiness = new BundleReadiness(this.homeDir);
        when(this.persistenceService.getDefaultToolIds()).thenReturn(Set.of());
        ToolsContribution tools = new ToolsContribution(this.toolSpecService, providerOf(this.persistenceService),
                this.hasher, mock(DefaultIntegrityVerifier.class), mock(ToolActivationCalculator.class));
        ProxiedToolsContribution proxied = new ProxiedToolsContribution(providerOf(this.mcpServerInfoService),
                providerOf(this.mcpClientService), providerOf(this.compositionService));
        this.bootstrapper = new BundleBootstrapper(this.homeDir, List.of(tools, proxied), this.toolSpecService,
                providerOf(this.exposedToolService), this.dependencyMonitor, this.bundleReadiness,
                this.environment);
        this.spec = new ToolSpec("t-1", "echoTool", "echoes", List.of(), List.of(),
                "return text;", CodeType.Javascript, null).withDraft(false);
        when(this.toolSpecService.getToolSpecList()).thenReturn(List.of(this.spec));
        this.environment.setProperty("spring.ai.model.chat", "none");
        this.environment.setProperty("spring.ai.model.embedding", "none");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private BundleManifest manifest(Map<String, String> fileHashes, Map<String, String> toolHashes,
            BundleManifest.Models models, List<BundleManifest.EnvVar> env) {
        return new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server("unit-test", "d", "0.1.0"),
                new BundleManifest.Requires("0.2.0", env, models, List.of()),
                new BundleManifest.Contents(List.of("t-1"), null, null, null),
                new BundleManifest.Risk("L0", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, fileHashes, toolHashes),
                new BundleManifest.Provenance("test", "now"));
    }

    private BundleManifest proxiedManifest(List<String> tools, Map<String, String> fileHashes) {
        return new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server("unit-test", "d", "0.1.0"),
                new BundleManifest.Requires("0.2.0", List.of(), new BundleManifest.Models(null, null),
                        List.of(UPSTREAM_STEM)),
                new BundleManifest.Contents(tools, new BundleManifest.ComposedTools(McpCompositionService.EXPOSED_ID,
                        List.of(DEEPWIKI_MEMBER), "L3"), null, null),
                new BundleManifest.Risk("L3", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, fileHashes,
                        tools.isEmpty() ? Map.of() : validToolHashes()),
                new BundleManifest.Provenance("test", "now"));
    }

    private Path writeUpstream(String connectionAsJson) throws IOException {
        Path file = this.homeDir.resolve("mcp").resolve("save").resolve(UPSTREAM_STEM + ".json");
        Files.createDirectories(file.getParent());
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("mcpTransportType", "STREAMABLE_HTTP");
        saved.put("serverName", "DeepWiki");
        saved.put("connectionAsJson", connectionAsJson);
        Files.write(file, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(saved));
        return file;
    }

    private static Map<String, String> upstreamFileHash(Path file) throws IOException {
        return Map.of("home/mcp/save/" + UPSTREAM_STEM + ".json", BundleExportService.sha256(file));
    }

    private Map<String, String> validToolHashes() {
        return Map.of("echoTool", this.hasher.hashTool(this.spec));
    }

    @Test
    void servesWhenEverythingVerifies() throws IOException {
        Path settingFile = this.homeDir.resolve("tool").resolve("save").resolve("toolSpecsMcpSetting.json");
        Files.createDirectories(settingFile.getParent());
        Files.writeString(settingFile, "{}");
        BundleManifest manifest = manifest(
                Map.of("home/tool/save/toolSpecsMcpSetting.json", BundleExportService.sha256(settingFile)),
                validToolHashes(), new BundleManifest.Models(null, null), List.of());

        assertThat(this.bundleReadiness.isReady()).isFalse();
        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();

        verify(this.toolSpecService).setExposureMode(ExposureMode.BUILTIN_ONLY);
        verify(this.toolSpecService).setToolMcpServerSetting(new ToolMcpServerSetting(false, Set.of("t-1")));
        verify(this.toolSpecService).reconcileNativeExposure();
        verify(this.exposedToolService).sync();
        assertThat(this.bundleReadiness.isReady()).isTrue();
    }

    @Test
    void servesProxiedToolsBesideTheBundledOnes() throws IOException {
        when(this.mcpServerInfoService.getMcpServerInfos())
                .thenReturn(Map.of(McpTransportType.STREAMABLE_HTTP, List.of(DEEPWIKI_UPSTREAM)));
        BundleManifest manifest = proxiedManifest(List.of("t-1"), upstreamFileHash(writeUpstream(UPSTREAM_CONNECTION)));

        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();

        verify(this.toolSpecService).setExposureMode(ExposureMode.BOTH);
        verify(this.mcpClientService).startMcpClient(DEEPWIKI_UPSTREAM);
        verify(this.compositionService).upsertExposed(List.of(DEEPWIKI_MEMBER), RiskLevel.L3);
        verify(this.exposedToolService).sync();
        verify(this.dependencyMonitor).watch(manifest, List.of(DEEPWIKI_UPSTREAM));
    }

    @Test
    void anUnreachableUpstreamStillServesAndIsHandedToTheMonitor() throws IOException {
        when(this.mcpServerInfoService.getMcpServerInfos())
                .thenReturn(Map.of(McpTransportType.STREAMABLE_HTTP, List.of(DEEPWIKI_UPSTREAM)));
        doThrow(new IllegalStateException("connection refused")).when(this.mcpClientService)
                .startMcpClient(DEEPWIKI_UPSTREAM);
        BundleManifest manifest = proxiedManifest(List.of("t-1"), upstreamFileHash(writeUpstream(UPSTREAM_CONNECTION)));

        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();

        verify(this.dependencyMonitor).watch(manifest, List.of(DEEPWIKI_UPSTREAM));
    }

    @Test
    void aProxyOnlyBundleServesComposedToolsOnly() throws IOException {
        BundleManifest manifest = proxiedManifest(List.of(), upstreamFileHash(writeUpstream(UPSTREAM_CONNECTION)));

        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();

        verify(this.toolSpecService).setExposureMode(ExposureMode.COMPOSED_ONLY);
    }

    @Test
    void rejectsAnUpstreamConnectionEditedAfterExport() throws IOException {
        Map<String, String> exported = upstreamFileHash(writeUpstream(UPSTREAM_CONNECTION));
        writeUpstream("{\"url\":\"https://evil.example.com\"}");
        BundleManifest manifest = proxiedManifest(List.of("t-1"), exported);

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modified after export");
    }

    @Test
    void rejectsAMissingUpstreamConnection() {
        BundleManifest manifest = proxiedManifest(List.of("t-1"),
                Map.of("home/mcp/save/" + UPSTREAM_STEM + ".json", "0".repeat(64)));

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bundle file missing");
        assertThat(this.bundleReadiness.isReady()).isFalse();
    }

    @Test
    void builtInToolHashDriftWarnsInsteadOfFailing() {
        when(this.persistenceService.getDefaultToolIds()).thenReturn(Set.of("t-1"));
        BundleManifest manifest = manifest(Map.of(), Map.of("echoTool", "drifted-hash"),
                new BundleManifest.Models(null, null), List.of());

        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();

        verify(this.toolSpecService).setExposureMode(ExposureMode.BUILTIN_ONLY);
    }

    @Test
    void rejectsModelFreeBundleWhenModelProviderConfigured() {
        this.environment.setProperty("spring.ai.model.chat", "ollama");
        BundleManifest manifest = manifest(Map.of(), validToolHashes(),
                new BundleManifest.Models(null, null), List.of());

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be 'none'");
    }

    @Test
    void rejectsModifiedBundleFile() throws IOException {
        Path settingFile = this.homeDir.resolve("tool").resolve("save").resolve("toolSpecsMcpSetting.json");
        Files.createDirectories(settingFile.getParent());
        Files.writeString(settingFile, "{\"tampered\":true}");
        BundleManifest manifest = manifest(
                Map.of("home/tool/save/toolSpecsMcpSetting.json", "0".repeat(64)),
                validToolHashes(), new BundleManifest.Models(null, null), List.of());

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash mismatch");
    }

    @Test
    void verifiesFileHashesAgainstTheShippedBundleNotTheWorkingCopy(@TempDir Path shippedBundle)
            throws IOException {
        Path shippedFile = shippedBundle.resolve("tool").resolve("save").resolve("toolSpecsMcpSetting.json");
        Files.createDirectories(shippedFile.getParent());
        Files.writeString(shippedFile, "{}");
        Path workingCopy = this.homeDir.resolve("tool").resolve("save").resolve("toolSpecsMcpSetting.json");
        Files.createDirectories(workingCopy.getParent());
        Files.writeString(workingCopy, "{\"lastUsedAtEpochMs\":1}");
        this.environment.setProperty(BundleHome.SOURCE_PROPERTY, shippedBundle.toString());
        BundleManifest manifest = manifest(
                Map.of("home/tool/save/toolSpecsMcpSetting.json", BundleExportService.sha256(shippedFile)),
                validToolHashes(), new BundleManifest.Models(null, null), List.of());

        assertThatCode(() -> this.bootstrapper.serve(manifest)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRequiredEnv() {
        BundleManifest manifest = manifest(Map.of(), validToolHashes(),
                new BundleManifest.Models(null, null),
                List.of(new BundleManifest.EnvVar("BUNDLE_UNIT_TEST_MISSING_ENV", "test", true)));

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUNDLE_UNIT_TEST_MISSING_ENV");
    }

    @Test
    void rejectsUnmetModelRequirement() {
        BundleManifest manifest = manifest(Map.of(), validToolHashes(),
                new BundleManifest.Models(new BundleManifest.ModelRef("ollama", "qwen3"), null), List.of());

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat model provider 'ollama'");
    }

    @Test
    void rejectsTamperedCustomToolSpec() {
        BundleManifest manifest = manifest(Map.of(), Map.of("echoTool", "not-the-real-hash"),
                new BundleManifest.Models(null, null), List.of());

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content hash mismatch");
    }

    @Test
    void rejectsUnsupportedBundleVersion() {
        BundleManifest manifest = new BundleManifest("99",
                new BundleManifest.Server("unit-test", "d", "0.1.0"), null, null,
                new BundleManifest.Risk("L0", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, Map.of(), Map.of()),
                new BundleManifest.Provenance("test", "now"));

        assertThatThrownBy(() -> this.bootstrapper.serve(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported bundle version");
    }

    @Test
    void ignoresHomesWithoutManifest() throws IOException {
        Files.delete(this.homeDir.resolve(BundleManifest.FILE_NAME));

        assertThatCode(() -> this.bootstrapper.onApplicationEvent(null)).doesNotThrowAnyException();
        verify(toolSpecService, never()).reconcileNativeExposure();
    }

}
