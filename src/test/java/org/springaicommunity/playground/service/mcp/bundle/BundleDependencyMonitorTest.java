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
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.bundle.BundleDependencyMonitor.Dependency;
import org.springaicommunity.playground.service.mcp.bundle.BundleDependencyMonitor.Kind;
import org.springaicommunity.playground.service.mcp.bundle.BundleDependencyMonitor.State;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.StatusEntry;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleDependencyMonitorTest {

    private static final McpServerInfo DEEPWIKI = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "DeepWiki",
            "d", 1L, 1L, "{\"url\":\"https://mcp.deepwiki.com\"}");
    private static final McpComposition.Member DEEPWIKI_MEMBER = new McpComposition.Member("DeepWiki",
            "read_wiki_structure", "deepwiki_read_wiki_structure", null, false, "");

    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final McpExposedToolService exposedToolService = mock(McpExposedToolService.class);
    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private String ollamaTags = "{\"models\":[]}";
    private BundleDependencyMonitor monitor;

    @BeforeEach
    void setUp() {
        this.monitor = new BundleDependencyMonitor(providerOf(this.mcpClientService),
                providerOf(this.exposedToolService), providerOf(this.toolSpecService), this.environment,
                new OllamaModelProbe(baseUrl -> Optional.ofNullable(this.ollamaTags)));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private static BundleManifest manifest(BundleManifest.Models models, boolean proxied) {
        return new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server("deps-test", "d", "0.1.0"),
                new BundleManifest.Requires("0.2.0", List.of(), models, List.of()),
                new BundleManifest.Contents(List.of(), proxied ? new BundleManifest.ComposedTools(
                        McpCompositionService.EXPOSED_ID, List.of(DEEPWIKI_MEMBER), "L3") : null, null, null),
                new BundleManifest.Risk("L3", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, Map.of(), Map.of()),
                new BundleManifest.Provenance("test", "now"));
    }

    private Dependency dependency(String name) {
        return this.monitor.status().dependencies().stream().filter(dependency -> dependency.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void notServingUntilABundleIsWatched() {
        assertThat(this.monitor.status().serving()).isFalse();
        this.monitor.check();
        verify(this.mcpClientService, never()).startMcpClient(DEEPWIKI);
    }

    @Test
    void anUnreachableUpstreamIsReportedThenReconnectedWithFreshToolRegistrations() {
        doThrow(new IllegalStateException("connection refused")).when(this.mcpClientService).startMcpClient(DEEPWIKI);
        this.monitor.watch(manifest(new BundleManifest.Models(null, null), true), List.of(DEEPWIKI));

        Dependency down = dependency("DeepWiki");
        assertThat(down.state()).isEqualTo(State.DOWN);
        assertThat(down.kind()).isEqualTo(Kind.UPSTREAM);
        assertThat(down.detail()).contains("connection refused");
        assertThat(down.tools()).containsExactly("deepwiki_read_wiki_structure");
        assertThat(this.monitor.status().unavailable()).containsExactly(down);
        verify(this.exposedToolService, never()).sync();

        doNothing().when(this.mcpClientService).startMcpClient(DEEPWIKI);
        when(this.mcpClientService.getStatus(DEEPWIKI)).thenReturn(StatusEntry.ok());
        when(this.toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("deepwiki_read_wiki_structure"));
        this.monitor.check();

        assertThat(dependency("DeepWiki").state()).isEqualTo(State.UP);
        verify(this.toolSpecService).removeExternalMcpTool("deepwiki_read_wiki_structure");
        verify(this.exposedToolService).sync();
    }

    @Test
    void aProxiedToolIsWithdrawnFromTheServerWhileItsUpstreamIsDown() {
        doThrow(new IllegalStateException("connection refused")).when(this.mcpClientService).startMcpClient(DEEPWIKI);
        when(this.toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("deepwiki_read_wiki_structure"),
                Set.of());

        this.monitor.watch(manifest(new BundleManifest.Models(null, null), true), List.of(DEEPWIKI));
        this.monitor.check();

        verify(this.toolSpecService, times(1)).removeExternalMcpTool("deepwiki_read_wiki_structure");
        verify(this.toolSpecService, times(1)).refreshDefaultMcpTool();
        verify(this.exposedToolService, never()).sync();
        assertThat(dependency("DeepWiki").state()).isEqualTo(State.DOWN);
    }

    @Test
    void aLiveUpstreamIsOnlyPinged() {
        when(this.mcpClientService.isConnecting(DEEPWIKI)).thenReturn(true);
        when(this.mcpClientService.pingAndUpdateStatus(DEEPWIKI)).thenReturn(StatusEntry.ok());

        this.monitor.watch(manifest(new BundleManifest.Models(null, null), true), List.of(DEEPWIKI));

        assertThat(dependency("DeepWiki").state()).isEqualTo(State.UP);
        verify(this.mcpClientService, never()).startMcpClient(DEEPWIKI);
        verify(this.exposedToolService, never()).sync();
    }

    @Test
    void ollamaModelsAreProbedAtTheConfiguredEndpoint() {
        this.environment.setProperty("spring.ai.ollama.base-url", "http://host.docker.internal:11434");
        this.ollamaTags = "{\"models\":[{\"name\":\"qwen3-embedding:0.6b\"}]}";
        this.monitor.watch(manifest(new BundleManifest.Models(new BundleManifest.ModelRef("ollama", "qwen3.5:4b"),
                new BundleManifest.ModelRef("ollama", "qwen3-embedding:0.6b")), false), List.of());

        assertThat(dependency("ollama:qwen3-embedding:0.6b").state()).isEqualTo(State.UP);
        Dependency chat = dependency("ollama:qwen3.5:4b");
        assertThat(chat.kind()).isEqualTo(Kind.CHAT_MODEL);
        assertThat(chat.state()).isEqualTo(State.DOWN);
        assertThat(chat.detail()).isEqualTo("model qwen3.5:4b is not pulled at http://host.docker.internal:11434");

        this.ollamaTags = null;
        this.monitor.check();

        assertThat(dependency("ollama:qwen3-embedding:0.6b").detail())
                .isEqualTo("Ollama unreachable at http://host.docker.internal:11434");
    }

    @Test
    void otherProvidersAreNotProbed() {
        this.monitor.watch(manifest(new BundleManifest.Models(null,
                new BundleManifest.ModelRef("openai", "text-embedding-3-small")), false), List.of());

        assertThat(dependency("openai:text-embedding-3-small").state()).isEqualTo(State.UNKNOWN);
        assertThat(this.monitor.status().unavailable()).isEmpty();
    }

}
