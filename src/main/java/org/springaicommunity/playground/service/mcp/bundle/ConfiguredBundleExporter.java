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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardModel;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ConfiguredBundleExporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ConfiguredBundleExporter.class);

    private final List<ShipyardServer> configuredServers;
    private final Path homeDir;
    private final BundleExportService bundleExportService;
    private final BundleRegistryService registryService;
    private final BundleArtifactWriter artifactWriter;
    private final ToolSpecService toolSpecService;
    private final DeployTargetService deployTargetService;
    private final BundleFreshnessService freshnessService;
    private final KnowledgeContribution knowledgeContribution;
    private final Map<String, String> problems = new ConcurrentHashMap<>();

    public ConfiguredBundleExporter(SpringAiPlaygroundOptions playgroundOptions, Path springAiPlaygroundHomeDir,
            BundleExportService bundleExportService, BundleRegistryService registryService,
            BundleArtifactWriter artifactWriter, ToolSpecService toolSpecService,
            DeployTargetService deployTargetService, BundleFreshnessService freshnessService,
            KnowledgeContribution knowledgeContribution) {
        this.configuredServers = playgroundOptions.shipyard().servers();
        this.homeDir = springAiPlaygroundHomeDir;
        this.bundleExportService = bundleExportService;
        this.registryService = registryService;
        this.artifactWriter = artifactWriter;
        this.toolSpecService = toolSpecService;
        this.deployTargetService = deployTargetService;
        this.freshnessService = freshnessService;
        this.knowledgeContribution = knowledgeContribution;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (Files.isRegularFile(this.homeDir.resolve(BundleManifest.FILE_NAME))) {
            return;
        }
        this.configuredServers.forEach(this::reconcile);
    }

    public Map<String, String> problems() {
        return new LinkedHashMap<>(this.problems);
    }

    public void reconcile(ShipyardServer server) {
        this.problems.remove(server.name());
        try {
            ShipRequest request = this.bundleExportService.normalize(toRequest(server));
            Optional<BundleEntry> existing = this.registryService.find(server.name());
            if (existing.isPresent() && upToDate(existing.get(), request)) {
                logger.info("Configured MCP server '{}' is up to date", server.name());
                autoRun(server);
                return;
            }
            if (existing.isPresent() && containerLive(server.name())) {
                logger.warn("Configured MCP server '{}' changed but its container is running; stop it and "
                        + "restart the app to rebuild the bundle", server.name());
                this.problems.put(server.name(), "changed in configuration while its container is running; "
                        + "stop it and restart the app to rebuild the bundle");
                return;
            }
            this.bundleExportService.exportConfigured(request);
            logger.info("Exported configured MCP server '{}' from {}", server.name(),
                    BundleExportService.CONFIGURED_SERVERS_PROPERTY);
            autoRun(server);
        } catch (RuntimeException e) {
            logger.error("Configured MCP server '{}' was not exported: {}", server.name(), e.getMessage());
            this.problems.put(server.name(), "not exported: " + e.getMessage());
        }
    }

    private void autoRun(ShipyardServer server) {
        if (!server.autoRuns()) return;
        Optional<BundleEntry> entry = this.registryService.find(server.name());
        if (entry.isEmpty()) return;
        DeployTarget target = this.deployTargetService.targetOf(server.name());
        if (!this.deployTargetService.check(target).daemonUp()) {
            this.problems.put(server.name(), "auto-run skipped: Docker on target '" + target.name()
                    + "' is not reachable");
            logger.warn("Configured MCP server '{}' was not auto-run: Docker on target '{}' is not reachable",
                    server.name(), target.name());
            return;
        }
        ContainerState state = this.deployTargetService.status(server.name()).state();
        if (state == ContainerState.RUNNING || state == ContainerState.STARTING) return;
        Thread runner = new Thread(() -> {
            try {
                this.deployTargetService.run(entry.get(), target, Map.of());
                logger.info("Auto-ran configured MCP server '{}' on target '{}'", server.name(), target.name());
            } catch (RuntimeException e) {
                this.problems.put(server.name(), "auto-run failed on target '" + target.name() + "': "
                        + e.getMessage());
                logger.error("Configured MCP server '{}' failed to auto-run on target '{}': {}", server.name(),
                        target.name(), e.getMessage());
            }
        }, "shipyard-auto-run-" + server.name());
        runner.setDaemon(true);
        runner.start();
    }

    ShipRequest toRequest(ShipyardServer server) {
        Map<String, String> toolIdsByName = this.toolSpecService.getToolSpecList().stream()
                .collect(Collectors.toMap(ToolSpec::name, ToolSpec::toolId, (first, second) -> first));
        List<String> unknown = server.tools().stream().filter(name -> !toolIdsByName.containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown tool name(s) " + unknown);
        }
        String version = server.version() == null || server.version().isBlank()
                ? BundleExportService.DEFAULT_VERSION : server.version();
        List<McpComposition.Member> composedTools = server.composedTools().stream()
                .map(composed -> new McpComposition.Member(composed.server(), composed.tool(), composed.alias(),
                        composed.description(), composed.hitl(), ""))
                .toList();
        List<ShipRequest.Knowledge> knowledge = server.knowledge().stream()
                .map(entry -> new ShipRequest.Knowledge(this.knowledgeContribution.resolveSourceId(entry.source())
                        .orElseThrow(() -> new IllegalArgumentException("unknown knowledge source '"
                                + entry.source() + "'")), entry.name(), entry.description(), entry.topK()))
                .toList();
        return new ShipRequest(server.name(), server.description(), version,
                server.tools().stream().map(toolIdsByName::get).toList(), server.transport(), server.hostPort(),
                new BundleManifest.Models(modelRef(server.chatModel()), modelRef(server.embeddingModel())),
                composedTools, server.composedToolsMaxRisk(), knowledge);
    }

    private static BundleManifest.ModelRef modelRef(ShipyardModel model) {
        return model == null || model.provider() == null || model.provider().isBlank()
                ? null : new BundleManifest.ModelRef(model.provider(), model.model());
    }

    private boolean upToDate(BundleEntry entry, ShipRequest request) {
        if (!request.equals(entry.buildConfig()) || !this.freshnessService.changes(entry).isEmpty()) return false;
        try {
            return Files.readString(entry.dir().resolve("Dockerfile"))
                    .equals(this.artifactWriter.dockerfile(this.bundleExportService.image(), entry.manifest()));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containerLive(String serverName) {
        if (!this.deployTargetService.check(this.deployTargetService.targetOf(serverName)).daemonUp()) return false;
        ContainerState state = this.deployTargetService.status(serverName).state();
        return state == ContainerState.RUNNING || state == ContainerState.STARTING;
    }

}
