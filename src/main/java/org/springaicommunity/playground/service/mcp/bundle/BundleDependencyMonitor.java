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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BundleDependencyMonitor {

    public enum Kind { UPSTREAM, CHAT_MODEL, EMBEDDING_MODEL }

    public enum State { UP, DOWN, UNKNOWN }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(String name, Kind kind, State state, String detail, List<String> tools) {

        public Dependency {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(boolean serving, String server, List<Dependency> dependencies) {

        public Status {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public List<Dependency> unavailable() {
            return dependencies.stream().filter(dependency -> dependency.state() == State.DOWN).toList();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(BundleDependencyMonitor.class);
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final ObjectProvider<McpExposedToolService> exposedToolServiceProvider;
    private final ObjectProvider<ToolSpecService> toolSpecServiceProvider;
    private final Environment environment;
    private final OllamaModelProbe ollamaModelProbe;
    private final Map<String, Dependency> dependencies = new ConcurrentHashMap<>();
    private volatile BundleManifest manifest;
    private volatile List<McpServerInfo> upstreams = List.of();

    public BundleDependencyMonitor(ObjectProvider<McpClientService> mcpClientServiceProvider,
            ObjectProvider<McpExposedToolService> exposedToolServiceProvider,
            ObjectProvider<ToolSpecService> toolSpecServiceProvider, Environment environment,
            OllamaModelProbe ollamaModelProbe) {
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.exposedToolServiceProvider = exposedToolServiceProvider;
        this.toolSpecServiceProvider = toolSpecServiceProvider;
        this.environment = environment;
        this.ollamaModelProbe = ollamaModelProbe;
    }

    public void watch(BundleManifest manifest, List<McpServerInfo> upstreams) {
        this.manifest = manifest;
        this.upstreams = List.copyOf(upstreams);
        check();
    }

    public Status status() {
        BundleManifest current = this.manifest;
        if (current == null) return new Status(false, null, List.of());
        return new Status(true, current.server().name(), List.copyOf(this.dependencies.values()));
    }

    @Scheduled(fixedDelayString = "${spring.ai.playground.shipyard.dependency-check-interval-ms:30000}",
            initialDelayString = "${spring.ai.playground.shipyard.dependency-check-interval-ms:30000}")
    public void check() {
        BundleManifest current = this.manifest;
        if (current == null) return;
        List<String> reconnectedTools = new ArrayList<>();
        List<String> lostTools = new ArrayList<>();
        for (McpServerInfo upstream : this.upstreams) {
            reconnectedTools.addAll(checkUpstream(current, upstream));
            if (isDown(upstream.serverName())) lostTools.addAll(this.dependencies.get(upstream.serverName()).tools());
        }
        if (!reconnectedTools.isEmpty()) reregister(reconnectedTools);
        if (!lostTools.isEmpty()) unregister(lostTools);
        BundleManifest.Models models = current.requires().models();
        checkModel(Kind.CHAT_MODEL, models.chat(), null);
        checkModel(Kind.EMBEDDING_MODEL, models.embedding(), current.contents().ragPipelines().stream()
                .map(BundleManifest.RagPipelineRef::alias).toList());
    }

    private void reregister(List<String> tools) {
        ToolSpecService toolSpecService = this.toolSpecServiceProvider.getIfAvailable();
        McpExposedToolService exposedToolService = this.exposedToolServiceProvider.getIfAvailable();
        if (toolSpecService == null || exposedToolService == null) return;
        tools.stream().filter(toolSpecService.getExternalMcpToolNames()::contains)
                .forEach(toolSpecService::removeExternalMcpTool);
        exposedToolService.sync();
    }

    private void unregister(List<String> tools) {
        ToolSpecService toolSpecService = this.toolSpecServiceProvider.getIfAvailable();
        if (toolSpecService == null) return;
        List<String> registered = tools.stream().filter(toolSpecService.getExternalMcpToolNames()::contains).toList();
        if (registered.isEmpty()) return;
        registered.forEach(toolSpecService::removeExternalMcpTool);
        toolSpecService.refreshDefaultMcpTool();
        logger.info("Withdrew proxied tool(s) {} from tools/list while their upstream is down", registered);
    }

    private List<String> checkUpstream(BundleManifest current, McpServerInfo upstream) {
        McpClientService clientService = this.mcpClientServiceProvider.getIfAvailable();
        if (clientService == null) return List.of();
        List<String> tools = current.contents().composedMembers().stream()
                .filter(member -> upstream.serverName().equals(member.serverId()))
                .map(McpComposition.Member::exposedAlias).toList();
        boolean wasDown = isDown(upstream.serverName());
        if (clientService.isConnecting(upstream)
                && clientService.pingAndUpdateStatus(upstream).status() == ServerStatus.OK) {
            record(new Dependency(upstream.serverName(), Kind.UPSTREAM, State.UP, null, tools));
            return List.of();
        }
        try {
            clientService.startMcpClient(upstream);
            ServerStatus status = clientService.getStatus(upstream).status();
            if (status == ServerStatus.OK) {
                if (wasDown) logger.info("Bundle upstream '{}' reconnected", upstream.serverName());
                record(new Dependency(upstream.serverName(), Kind.UPSTREAM, State.UP, null, tools));
                return tools;
            }
            record(new Dependency(upstream.serverName(), Kind.UPSTREAM, State.DOWN, status.name(), tools));
        } catch (RuntimeException e) {
            if (!wasDown) logger.warn("Bundle upstream '{}' is unreachable ({}); its tool(s) {} stay unavailable "
                    + "and the connection is retried", upstream.serverName(), e.getMessage(), tools);
            record(new Dependency(upstream.serverName(), Kind.UPSTREAM, State.DOWN, e.getMessage(), tools));
        }
        return List.of();
    }

    private void checkModel(Kind kind, BundleManifest.ModelRef model, List<String> tools) {
        if (model == null) return;
        String name = model.provider() + ":" + model.model();
        if (!"ollama".equalsIgnoreCase(model.provider())) {
            record(new Dependency(name, kind, State.UNKNOWN, "not probed for provider " + model.provider(), tools));
            return;
        }
        String baseUrl = this.environment.getProperty("spring.ai.ollama.base-url", DEFAULT_OLLAMA_BASE_URL);
        OllamaModelProbe.Result result = this.ollamaModelProbe.probe(baseUrl);
        String detail = !result.reachable() ? "Ollama unreachable at " + baseUrl
                : result.has(model.model()) ? null : "model " + model.model() + " is not pulled at " + baseUrl;
        State state = detail == null ? State.UP : State.DOWN;
        if (state == State.DOWN && !isDown(name)) logger.warn("Bundle {} is unavailable: {}", name, detail);
        record(new Dependency(name, kind, state, detail, tools));
    }

    private boolean isDown(String name) {
        Dependency previous = this.dependencies.get(name);
        return previous != null && previous.state() == State.DOWN;
    }

    private void record(Dependency dependency) {
        this.dependencies.put(dependency.name(), dependency);
    }

}
