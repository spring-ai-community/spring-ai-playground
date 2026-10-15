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
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ChatService.RagSource;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.SourceChange;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.client.HttpConnectionParametersWithExtras;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.TestConnectionResult;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator.State;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
public class ShipyardService {

    public record ServerState(BundleEntry entry, DeployTarget target, DockerAvailability docker,
            ContainerStatus status, int hostPort, Optional<String> authToken, boolean managedByConfiguration,
            List<SourceChange> changes) {

        public boolean live() {
            return status.state() == ContainerState.RUNNING || status.state() == ContainerState.STARTING;
        }
    }

    public record ServerSummary(String name, Optional<BundleEntry> entry, boolean outOfDate, String problem) {}

    public record FlowDraft(List<McpComposition.Member> proxiedChoices, Set<String> preselectedProxiedKeys,
            RiskLevel proxiedMaxRisk, List<RagSource> knowledgeSources, List<ShipRequest.Knowledge> knowledgePrefill,
            List<String> customToolIds, List<String> builtInToolIds, List<String> chatModels,
            String embeddingModel) {}

    public record ToolChoices(List<ToolSpec> shippable, long drafts, long missingRequirements,
            Map<String, String> riskLevels) {}

    public record BundleFile(String path, long bytes) {}

    private final BundleRegistryService registryService;
    private final BundleExportService bundleExportService;
    private final DeployTargetService deployTargetService;
    private final BundleFreshnessService freshnessService;
    private final ConfiguredBundleExporter configuredBundleExporter;
    private final McpClientService mcpClientService;
    private final McpServerInfoService mcpServerInfoService;
    private final McpCompositionService compositionService;
    private final ChatService chatService;
    private final VectorStoreService vectorStoreService;
    private final ToolSpecService toolSpecService;
    private final ToolActivationCalculator activationCalculator;
    private final ToolSpecPersistenceService toolSpecPersistenceService;

    public ShipyardService(BundleRegistryService registryService, BundleExportService bundleExportService,
            DeployTargetService deployTargetService, BundleFreshnessService freshnessService,
            ConfiguredBundleExporter configuredBundleExporter, McpClientService mcpClientService,
            McpServerInfoService mcpServerInfoService, McpCompositionService compositionService,
            ChatService chatService, VectorStoreService vectorStoreService, ToolSpecService toolSpecService,
            ToolActivationCalculator activationCalculator, ToolSpecPersistenceService toolSpecPersistenceService) {
        this.registryService = registryService;
        this.bundleExportService = bundleExportService;
        this.deployTargetService = deployTargetService;
        this.freshnessService = freshnessService;
        this.configuredBundleExporter = configuredBundleExporter;
        this.mcpClientService = mcpClientService;
        this.mcpServerInfoService = mcpServerInfoService;
        this.compositionService = compositionService;
        this.chatService = chatService;
        this.vectorStoreService = vectorStoreService;
        this.toolSpecService = toolSpecService;
        this.activationCalculator = activationCalculator;
        this.toolSpecPersistenceService = toolSpecPersistenceService;
    }

    public BundleEntry entry(String serverName) {
        return this.registryService.find(serverName)
                .orElseThrow(() -> new IllegalArgumentException("No server named '" + serverName + "'"));
    }

    public List<ServerSummary> servers() {
        List<BundleEntry> entries = this.registryService.list();
        Map<String, String> problems = this.configuredBundleExporter.problems();
        List<ServerSummary> summaries = new ArrayList<>();
        for (BundleEntry entry : entries) {
            summaries.add(new ServerSummary(entry.name(), Optional.of(entry),
                    !this.freshnessService.changes(entry).isEmpty(), problems.get(entry.name())));
        }
        problems.forEach((name, problem) -> {
            if (entries.stream().noneMatch(entry -> entry.name().equals(name))) {
                summaries.add(new ServerSummary(name, Optional.empty(), false, problem));
            }
        });
        return List.copyOf(summaries);
    }

    public ServerState state(String serverName) {
        BundleEntry entry = entry(serverName);
        DeployTarget target = this.deployTargetService.targetOf(serverName);
        DockerAvailability docker = this.deployTargetService.check(target);
        ContainerStatus status = docker.daemonUp() ? this.deployTargetService.status(serverName)
                : new ContainerStatus(ContainerState.ABSENT, null);
        int hostPort = status.hostPort() != null ? status.hostPort() : entry.effectiveHttpPort();
        return new ServerState(entry, target, docker, status, hostPort, this.deployTargetService.authToken(serverName),
                this.bundleExportService.managedByConfiguration(serverName), this.freshnessService.changes(entry));
    }

    public FlowDraft draft(ShipRequest prefill) {
        Optional<McpComposition> exposed = this.compositionService.getExposed();
        List<McpComposition.Member> prefillMembers = prefill == null ? List.of() : prefill.composedTools();
        RiskLevel exposedMaxRisk = exposed.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5);
        RiskLevel proxiedMaxRisk = prefillMembers.isEmpty() ? exposedMaxRisk
                : Objects.requireNonNullElse(prefill.composedToolsMaxRisk(), exposedMaxRisk);
        Map<String, McpComposition.Member> byKey = new LinkedHashMap<>();
        exposed.map(McpComposition::members).orElse(List.of()).forEach(member -> byKey.put(memberKey(member), member));
        prefillMembers.forEach(member -> byKey.putIfAbsent(memberKey(member), member));
        Set<String> preselected = new LinkedHashSet<>(prefillMembers.stream().map(ShipyardService::memberKey).toList());
        Set<String> defaultToolIds = this.toolSpecPersistenceService.getDefaultToolIds();
        List<String> preselectedToolIds = prefill == null ? List.of() : prefill.toolIds();
        return new FlowDraft(List.copyOf(byKey.values()), preselected, proxiedMaxRisk,
                this.chatService.getRagSources(), prefill == null ? List.of() : prefill.knowledge(),
                preselectedToolIds.stream().filter(toolId -> !defaultToolIds.contains(toolId)).toList(),
                preselectedToolIds.stream().filter(defaultToolIds::contains).toList(),
                this.chatService.getModels(), embeddingModel());
    }

    public static String memberKey(McpComposition.Member member) {
        return member.serverId() + "/" + member.toolName();
    }

    public BundleManifest.Models models(String chatModel, boolean embeddingRequired) {
        BundleManifest.ModelRef chat = chatModel == null || chatModel.isBlank() ? null
                : new BundleManifest.ModelRef(this.chatService.getChatModelProvider().toLowerCase(Locale.ROOT),
                        chatModel);
        BundleManifest.ModelRef embedding = !embeddingRequired ? null
                : new BundleManifest.ModelRef(
                        this.vectorStoreService.getEmbeddingModelServiceName().toLowerCase(Locale.ROOT),
                        embeddingModel());
        return new BundleManifest.Models(chat, embedding);
    }

    private String embeddingModel() {
        return Objects.requireNonNullElse(this.vectorStoreService.getEmbeddingOptions().getModel(),
                this.vectorStoreService.getEmbeddingModelServiceName());
    }

    public ShipRequest requestFromCurrentExposure() {
        Optional<McpComposition> exposed = this.compositionService.getExposed();
        return new ShipRequest(null, null, null,
                List.copyOf(this.toolSpecService.getToolMcpServerSetting().exposedToolIds()), null, null, null,
                exposed.map(McpComposition::members).orElse(List.of()),
                exposed.map(McpComposition::maxRiskLevel).orElse(null));
    }

    public boolean shippable(ToolSpec tool) {
        return this.activationCalculator.calculate(tool) == State.ACTIVE;
    }

    public ToolChoices toolChoices(boolean builtIn) {
        Set<String> defaultToolIds = this.toolSpecPersistenceService.getDefaultToolIds();
        Predicate<ToolSpec> scope = tool -> defaultToolIds.contains(tool.toolId()) == builtIn;
        List<ToolSpec> all = this.toolSpecService.getToolSpecList().stream().filter(scope).toList();
        List<ToolSpec> shippable = all.stream().filter(this::shippable).toList();
        Map<String, String> riskLevels = new LinkedHashMap<>();
        shippable.forEach(tool -> riskLevels.put(tool.toolId(), this.toolSpecService.riskLevelOf(tool)));
        return new ToolChoices(shippable, all.stream().filter(ToolSpec::draft).count(),
                all.stream().filter(tool -> this.activationCalculator.calculate(tool) == State.MISSING_REQUIREMENTS)
                        .count(), Map.copyOf(riskLevels));
    }

    public void requireMutable(String serverName, String action) {
        if (this.bundleExportService.managedByConfiguration(serverName)) {
            throw new IllegalStateException("'" + serverName + "' is managed by configuration; change it under "
                    + BundleExportService.CONFIGURED_SERVERS_PROPERTY);
        }
        if (state(serverName).live()) {
            throw new IllegalStateException("Stop the container before " + action + " the bundle");
        }
    }

    public ShipRequest rebuildRequest(String serverName) {
        requireMutable(serverName, "rebuilding");
        return requestFrom(entry(serverName));
    }

    public static ShipRequest requestFrom(BundleEntry entry) {
        if (entry.buildConfig() != null) {
            return entry.buildConfig();
        }
        BundleManifest manifest = entry.manifest();
        BundleManifest.ComposedTools composed = manifest.contents().composedTools();
        RiskLevel composedMaxRisk = composed == null || composed.maxRiskLevel() == null
                ? null : RiskLevel.valueOf(composed.maxRiskLevel());
        List<ShipRequest.Knowledge> knowledge = manifest.contents().ragPipelines().stream()
                .map(ref -> new ShipRequest.Knowledge(ref.source(), ref.alias(), ref.description(), ref.topK()))
                .toList();
        return new ShipRequest(manifest.server().name(), manifest.server().description(),
                manifest.server().version(), manifest.contents().tools(), null, null,
                manifest.requires().models(), manifest.contents().composedMembers(), composedMaxRisk, knowledge);
    }

    public void delete(String serverName) {
        requireMutable(serverName, "deleting");
        this.registryService.delete(serverName);
        this.deployTargetService.forget(serverName);
    }

    public void stop(String serverName) {
        this.deployTargetService.stop(entry(serverName));
    }

    public String logsTail(String serverName, int lines) {
        return Objects.requireNonNullElse(this.deployTargetService.logsTail(serverName, lines), "");
    }

    public List<BundleFile> bundleFiles(String serverName) {
        Path dir = entry(serverName).dir();
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString))
                    .map(path -> new BundleFile(dir.relativize(path).toString().replace('\\', '/'), size(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list bundle files: " + dir, e);
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    public String serverJson(String serverName) {
        Path file = entry(serverName).dir().resolve("server.json");
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public McpServerInfo connectionInfo(String serverName) {
        return connectionInfo(state(serverName), serverName);
    }

    public TestConnectionResult testConnection(String serverName) {
        return this.mcpClientService.testConnection(connectionInfo(state(serverName), "shipyard-test-" + serverName));
    }

    public McpSchema.CallToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        ServerState state = state(serverName);
        if (!state.live()) {
            throw new IllegalStateException("'" + serverName + "' is not running");
        }
        return this.mcpClientService.callToolOnce(connectionInfo(state, "shipyard-try-" + serverName), toolName,
                arguments);
    }

    public void addToThisApp(String serverName) {
        this.mcpServerInfoService.updateMcpServerInfo(McpTransportType.STREAMABLE_HTTP, serverName,
                connectionInfo(serverName));
    }

    private static McpServerInfo connectionInfo(ServerState state, String name) {
        long now = System.currentTimeMillis();
        Object parameters = connectionParameters(state.target(), state.hostPort(), state.authToken());
        return new McpServerInfo(McpTransportType.STREAMABLE_HTTP, name,
                Objects.toString(state.entry().manifest().server().description(), ""), now, now,
                PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(parameters));
    }

    private static Object connectionParameters(DeployTarget target, int hostPort, Optional<String> authToken) {
        String baseUrl = target.probeUrl(hostPort);
        return authToken
                .<Object>map(token -> new HttpConnectionParametersWithExtras.StreamableHttp(baseUrl,
                        "/mcp", Map.of("Authorization", "Bearer " + token), null, null))
                .orElseGet(() -> new McpStreamableHttpClientProperties.ConnectionParameters(baseUrl,
                        "/mcp"));
    }

}
