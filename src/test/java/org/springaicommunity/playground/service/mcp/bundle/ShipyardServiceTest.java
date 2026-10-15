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
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ProcResult;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerState;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ShipyardServiceTest {

    private static final String TOOL_ID = "shipyard-service-t1";
    private static final String SERVER_NAME = "shipyard-service-server";

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private BundleFreshnessService freshnessService;

    @Autowired
    private McpClientService mcpClientService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private ConfiguredBundleExporter configuredBundleExporter;

    @Autowired
    private McpCompositionService compositionService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private ToolActivationCalculator activationCalculator;

    @Autowired
    private ToolSpecPersistenceService toolSpecPersistenceService;

    @TempDir
    Path home;

    private final List<List<String>> commands = new ArrayList<>();

    @BeforeEach
    void shipABundle() {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "shipyardServiceTool", "echoes", List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));
        this.bundleExportService.exportBundle(new ShipRequest(SERVER_NAME, "shipyard service bundle", "0.1.0",
                List.of(TOOL_ID), Transport.HTTP, 18400, new BundleManifest.Models(null, null)));
    }

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
        this.mcpServerInfoService.deleteMcpServerInfo(McpTransportType.STREAMABLE_HTTP, SERVER_NAME);
    }

    private ShipyardService service(DeployTargetService targets) {
        return new ShipyardService(this.bundleRegistryService, this.bundleExportService, targets,
                this.freshnessService, this.configuredBundleExporter, this.mcpClientService,
                this.mcpServerInfoService, this.compositionService, this.chatService, this.vectorStoreService,
                this.toolSpecService, this.activationCalculator, this.toolSpecPersistenceService);
    }

    private ShipyardService absentContainers() {
        return service(DeployTargetFixtures.service(this.home, Map.of(), Set.of(), this.commands));
    }

    private ShipyardService runningContainer() {
        DockerCliService docker = new DockerCliService((command, extraEnv, timeout) -> {
            this.commands.add(command);
            if (command.contains("version")) return new ProcResult(0, "29.3.1\n", "");
            if (command.contains("ps")) return new ProcResult(0, "running|0.0.0.0:18400->8282/tcp\n", "");
            if (command.contains("inspect")) return new ProcResult(0, "SPRING_AI_PLAYGROUND_MCPSERVER_AUTHTOKEN=tok\n", "");
            return new ProcResult(0, "", "");
        }, baseUrl -> true, "docker");
        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);
        return service(new DeployTargetService(docker, options, this.home, new MockEnvironment()));
    }

    @Test
    void theStateOfAStoppedServerFallsBackToTheBundlePort() {
        ServerState state = absentContainers().state(SERVER_NAME);

        assertThat(state.status().state()).isEqualTo(ContainerState.ABSENT);
        assertThat(state.hostPort()).isEqualTo(18400);
        assertThat(state.target().isLocal()).isTrue();
        assertThat(state.docker().daemonUp()).isTrue();
        assertThat(state.authToken()).isEmpty();
        assertThat(state.managedByConfiguration()).isFalse();
        assertThat(state.changes()).isEmpty();
        assertThat(state.live()).isFalse();
    }

    @Test
    void anUnknownServerIsReportedByName() {
        assertThatThrownBy(() -> absentContainers().state("no-such-server"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No server named 'no-such-server'");
    }

    @Test
    void rebuildStartsFromTheBuildConfigTheBundleWasShippedWith() {
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();

        ShipRequest request = absentContainers().rebuildRequest(SERVER_NAME);

        assertThat(request).isEqualTo(entry.buildConfig());
        assertThat(request.toolIds()).containsExactly(TOOL_ID);
        assertThat(request.httpPort()).isEqualTo(18400);
    }

    @Test
    void aRunningServerCannotBeRebuiltOrDeleted() {
        ShipyardService service = runningContainer();

        assertThat(service.state(SERVER_NAME).live()).isTrue();
        assertThatThrownBy(() -> service.rebuildRequest(SERVER_NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stop the container before rebuilding the bundle");
        assertThatThrownBy(() -> service.delete(SERVER_NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stop the container before deleting the bundle");
        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isPresent();
    }

    @Test
    void deleteRemovesTheBundleAndForgetsWhereItRan() {
        DeployTargetService targets = DeployTargetFixtures.service(this.home, Map.of(), Set.of(), this.commands);
        ShipyardService service = service(targets);
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        targets.run(entry, targets.find("build-box").orElseThrow(), Map.of(DockerCliService.AUTH_TOKEN_ENV, "tok"));
        assertThat(targets.targetOf(SERVER_NAME).name()).isEqualTo("build-box");

        service.delete(SERVER_NAME);

        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isEmpty();
        assertThat(targets.targetOf(SERVER_NAME).isLocal()).isTrue();
    }

    @Test
    void theConnectionInfoCarriesTheRunTokenAndTheTargetAddress() {
        ShipyardService service = runningContainer();

        String connection = service.connectionInfo(SERVER_NAME).connectionAsJson();

        assertThat(connection).contains("http://127.0.0.1:18400").contains("\"/mcp\"").contains("Bearer tok");
    }

    @Test
    void theDraftSplitsPreselectedToolsAndFallsBackToTheExposedRiskCap() {
        ShipyardService service = absentContainers();
        McpComposition.Member member = new McpComposition.Member("srv", "tool", "srv_tool", null, false, "");
        String builtInToolId = this.toolSpecPersistenceService.getDefaultToolIds().iterator().next();
        ShipRequest prefill = new ShipRequest("draft-test", null, null, List.of(TOOL_ID, builtInToolId),
                Transport.HTTP, null, null, List.of(member), null);

        ShipyardService.FlowDraft draft = service.draft(prefill);

        assertThat(draft.customToolIds()).containsExactly(TOOL_ID);
        assertThat(draft.builtInToolIds()).containsExactly(builtInToolId);
        assertThat(draft.proxiedChoices()).contains(member);
        assertThat(draft.preselectedProxiedKeys()).containsExactly("srv/tool");
        assertThat(draft.proxiedMaxRisk()).isEqualTo(this.compositionService.getExposed()
                .map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5));
        assertThat(draft.chatModels()).isEqualTo(this.chatService.getModels());
        assertThat(draft.embeddingModel()).isNotBlank();
        assertThat(service.draft(null).customToolIds()).isEmpty();
    }

    @Test
    void modelsAreDerivedFromThisAppsProviders() {
        ShipyardService service = absentContainers();

        BundleManifest.Models models = service.models("qwen3.5:4b", true);

        assertThat(models.chat().model()).isEqualTo("qwen3.5:4b");
        assertThat(models.chat().provider()).isEqualTo(this.chatService.getChatModelProvider().toLowerCase());
        assertThat(models.embedding().provider())
                .isEqualTo(this.vectorStoreService.getEmbeddingModelServiceName().toLowerCase());
        assertThat(service.models(null, false).modelFree()).isTrue();
    }

    @Test
    void toolChoicesSeparateCustomToolsFromBuiltIns() {
        ShipyardService service = absentContainers();

        ShipyardService.ToolChoices custom = service.toolChoices(false);
        ShipyardService.ToolChoices builtIn = service.toolChoices(true);

        assertThat(custom.shippable()).extracting(ToolSpec::toolId).contains(TOOL_ID);
        assertThat(builtIn.shippable()).extracting(ToolSpec::toolId).doesNotContain(TOOL_ID);
        assertThat(builtIn.shippable()).allMatch(tool -> this.toolSpecPersistenceService.getDefaultToolIds()
                .contains(tool.toolId()));
    }

    @Test
    void theRequestFromTheCurrentExposureCarriesTheExposedToolIds() {
        ShipRequest request = absentContainers().requestFromCurrentExposure();

        assertThat(request.toolIds()).containsExactlyInAnyOrderElementsOf(
                this.toolSpecService.getToolMcpServerSetting().exposedToolIds());
        assertThat(request.serverName()).isNull();
    }

    @Test
    void bundleFilesAndServerJsonComeFromTheBundleDirectory() {
        ShipyardService service = absentContainers();

        assertThat(service.bundleFiles(SERVER_NAME)).extracting(ShipyardService.BundleFile::path)
                .contains("Dockerfile", "compose.yaml", "server.json", "home/" + BundleManifest.FILE_NAME);
        assertThat(service.bundleFiles(SERVER_NAME)).allMatch(file -> file.bytes() > 0);
        assertThat(service.serverJson(SERVER_NAME)).contains(SERVER_NAME);
        assertThat(service.servers()).anySatisfy(server -> {
            assertThat(server.name()).isEqualTo(SERVER_NAME);
            assertThat(server.entry()).isPresent();
            assertThat(server.outOfDate()).isFalse();
        });
    }

    @Test
    void addToThisAppRegistersAStreamableHttpConnection() {
        absentContainers().addToThisApp(SERVER_NAME);

        assertThat(this.mcpServerInfoService.getMcpServerInfos().get(McpTransportType.STREAMABLE_HTTP))
                .anySatisfy(info -> {
                    assertThat(info.serverName()).isEqualTo(SERVER_NAME);
                    assertThat(info.connectionAsJson()).contains("http://127.0.0.1:18400").doesNotContain("Bearer");
                });
    }

}
