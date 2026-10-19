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
package org.springaicommunity.playground.service.management;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ExportResult;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ValidationReport;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConfiguredBundleExporter;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService.ContentKind;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService.Snippet;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunPlan;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerState;
import org.springaicommunity.playground.service.management.ShipyardManagementContribution.ShipData;
import org.springaicommunity.playground.service.management.ShipyardManagementContribution.StatusData;
import org.springaicommunity.playground.service.management.ShipyardManagementContribution.TargetData;
import org.springaicommunity.playground.service.management.ShipyardManagementContribution.TargetsData;
import org.springaicommunity.playground.service.management.ShipyardManagementContribution.ValidationData;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipyardManagementContributionTest {

    private final ShipyardService shipyardService = mock(ShipyardService.class);
    private final BundleExportService bundleExportService = mock(BundleExportService.class);
    private final ConfiguredBundleExporter configuredBundleExporter = mock(ConfiguredBundleExporter.class);
    private final RunPlanner runPlanner = mock(RunPlanner.class);
    private final DeployTargetService deployTargetService = mock(DeployTargetService.class);
    private final ConnectionSnippetService snippetService = mock(ConnectionSnippetService.class);
    private final ShipyardManagementContribution contribution = new ShipyardManagementContribution(shipyardService,
            bundleExportService, configuredBundleExporter, runPlanner, deployTargetService, snippetService);

    private final ShipRequest request = new ShipRequest("field-kit", "field tools", "0.1.0", List.of("t-1", "t-2"),
            ShipRequest.Transport.BOTH, 18290, null, List.of(), null,
            List.of(new ShipRequest.Knowledge("pipe-1", "search_handbook", "Search the handbook", 4)));

    @BeforeEach
    void setUp() {
        when(configuredBundleExporter.toRequest(any(ShipyardServer.class))).thenReturn(request);
        when(bundleExportService.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bundleExportService.image()).thenReturn("ghcr.io/x/playground:v0.2.0-M14");
    }

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static ValidationReport report(List<String> blockers, List<String> warnings) {
        return new ValidationReport(blockers, warnings,
                List.of(new BundleManifest.EnvVar("SPRING_AI_OPENAI_APIKEY", "OpenAI key", true)), List.of());
    }

    @Test
    void theShipInputIsTranslatedThroughTheConfigurationRecord() {
        Map<String, Object> arguments = Map.of("name", "field-kit", "tools", List.of("dateMath", "uuid"),
                "hostPort", 18290, "composedTools", List.of(Map.of("server", "deepwiki", "tool", "read_wiki_structure",
                        "hitl", true)),
                "knowledge", List.of(Map.of("source", "Advanced RAG", "name", "search_handbook", "topK", 4)),
                "chatModel", Map.of("provider", "ollama", "model", "qwen3.5:4b"));
        when(bundleExportService.validate(any())).thenReturn(report(List.of(), List.of()));

        tool("saip_shipyard_validate").handle(arguments, ManagementCaller.ANONYMOUS);

        ArgumentCaptor<ShipyardServer> server = ArgumentCaptor.forClass(ShipyardServer.class);
        verify(configuredBundleExporter).toRequest(server.capture());
        assertThat(server.getValue().name()).isEqualTo("field-kit");
        assertThat(server.getValue().tools()).containsExactly("dateMath", "uuid");
        assertThat(server.getValue().hostPort()).isEqualTo(18290);
        assertThat(server.getValue().composedTools()).singleElement()
                .satisfies(composed -> {
                    assertThat(composed.server()).isEqualTo("deepwiki");
                    assertThat(composed.hitl()).isTrue();
                });
        assertThat(server.getValue().knowledge()).singleElement()
                .satisfies(knowledge -> assertThat(knowledge.source()).isEqualTo("Advanced RAG"));
        assertThat(server.getValue().chatModel().model()).isEqualTo("qwen3.5:4b");
        assertThat(server.getValue().autoRuns()).isFalse();
    }

    @Test
    void validateReportsBlockersWithoutWritingAnything() {
        when(bundleExportService.validate(any())).thenReturn(report(List.of("Select at least one tool"), List.of()));

        ManagementResult result = tool("saip_shipyard_validate").handle(Map.of("name", "field-kit"),
                ManagementCaller.ANONYMOUS);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Select at least one tool");
        ValidationData data = (ValidationData) result.data();
        assertThat(data.exportable()).isFalse();
        assertThat(data.environment()).singleElement()
                .satisfies(env -> assertThat(env.name()).isEqualTo("SPRING_AI_OPENAI_APIKEY"));
        verify(bundleExportService, never()).exportBundle(any());
    }

    @Test
    void aBlockedShipNeverReachesTheApprovalPrompt() {
        when(bundleExportService.validate(any())).thenReturn(report(List.of("unknown tool name(s) [nope]"),
                List.of()));

        assertThatThrownBy(() -> tool("saip_shipyard_ship").approvalFor(Map.of("name", "field-kit")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown tool name(s)");
        verify(bundleExportService, never()).exportBundle(any());
    }

    @Test
    void shipSummarizesTheContentsForApprovalAndExportsTheBundle() {
        when(bundleExportService.validate(any())).thenReturn(report(List.of(), List.of("replaces its bundle")));
        BundleManifest manifest = mock(BundleManifest.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(manifest.server().name()).thenReturn("field-kit");
        when(manifest.server().version()).thenReturn("0.1.0");
        when(manifest.contents().tools()).thenReturn(List.of("t-1", "t-2"));
        when(manifest.contents().composedMembers()).thenReturn(List.of());
        when(manifest.contents().ragPipelines()).thenReturn(List.of());
        when(bundleExportService.exportBundle(any())).thenReturn(new ExportResult(Path.of("/home/mcp/bundles/field-kit"),
                manifest, List.of()));

        ManagementApproval approval = tool("saip_shipyard_ship").approvalFor(Map.of("name", "field-kit"));
        ManagementResult result = tool("saip_shipyard_ship").handle(Map.of("name", "field-kit"),
                ManagementCaller.ANONYMOUS);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.WRITE);
        assertThat(approval.summary()).contains("Ship 'field-kit' v0.1.0", "2 tool(s)", "1 knowledge tool(s)",
                "replaces its bundle");
        assertThat(result.ok()).isTrue();
        assertThat(result.persisted()).isTrue();
        assertThat(result.warnings()).containsExactly("replaces its bundle");
        ShipData data = (ShipData) result.data();
        assertThat(data.bundleDir()).endsWith("field-kit");
        assertThat(data.nextStep()).contains("saip_shipyard_run");
    }

    @Test
    void runShowsTheComposeCommandAndRefusesAnUnreachableEngine() {
        BundleEntry entry = new BundleEntry("field-kit", mock(BundleManifest.class), Path.of("/b/field-kit"), request);
        DeployTarget local = DeployTarget.local();
        when(shipyardService.entry("field-kit")).thenReturn(entry);
        when(runPlanner.currentTarget("field-kit")).thenReturn(local);
        when(runPlanner.defaultOllamaEndpoint()).thenReturn("http://host.docker.internal:11434");
        when(runPlanner.plan(eq(entry), eq(local), anyString(), anyString())).thenReturn(plan(local, true));

        ManagementApproval approval = tool("saip_shipyard_run").approvalFor(Map.of("server", "field-kit"));

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.summary()).contains("Run 'field-kit' on", "localhost:18290",
                "Runs the command: docker compose -f /b/field-kit/compose.yaml up -d --build");

        when(runPlanner.plan(eq(entry), eq(local), anyString(), anyString())).thenReturn(plan(local, false));
        assertThatThrownBy(() -> tool("saip_shipyard_run").approvalFor(Map.of("server", "field-kit")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not reachable");
        verify(runPlanner, never()).run(any());
    }

    @Test
    void runStartsTheContainerWithoutPassingATokenThroughTheTool() {
        BundleEntry entry = new BundleEntry("field-kit", mock(BundleManifest.class), Path.of("/b/field-kit"), request);
        DeployTarget local = DeployTarget.local();
        when(shipyardService.entry("field-kit")).thenReturn(entry);
        when(runPlanner.defaultOllamaEndpoint()).thenReturn("http://host.docker.internal:11434");
        when(runPlanner.run(any())).thenReturn(new RunPlanner.RunResult(local, Optional.of("secret-token")));
        when(shipyardService.state("field-kit")).thenReturn(state(entry, local, ContainerState.STARTING,
                Optional.of("secret-token")));

        ManagementResult result = tool("saip_shipyard_run").handle(Map.of("server", "field-kit",
                "ollamaEndpoint", "http://ollama.lan:11434"), ManagementCaller.ANONYMOUS);

        ArgumentCaptor<RunRequest> runRequest = ArgumentCaptor.forClass(RunRequest.class);
        verify(runPlanner).run(runRequest.capture());
        assertThat(runRequest.getValue().authToken()).isNull();
        assertThat(runRequest.getValue().ollamaEndpoint()).isEqualTo("http://ollama.lan:11434");
        assertThat(result.message()).contains("STARTING");
        assertThat(String.valueOf(result.data())).doesNotContain("secret-token");
    }

    @Test
    void statusReturnsSnippetsWithoutTheTokenAndMarksOutOfDateServers() {
        BundleManifest manifest = mock(BundleManifest.class);
        BundleEntry entry = new BundleEntry("field-kit", manifest, Path.of("/b/field-kit"), request);
        DeployTarget local = DeployTarget.local();
        when(shipyardService.state("field-kit")).thenReturn(state(entry, local, ContainerState.RUNNING,
                Optional.of("secret-token")));
        when(snippetService.snippetsFor(eq(manifest), eq(local), anyInt(), eq(Optional.empty())))
                .thenReturn(List.of(new Snippet("Claude Code",
                        "claude mcp add --transport http field-kit http://localhost:18290/mcp", ContentKind.COMMAND)));

        ManagementResult result = tool("saip_shipyard_status").handle(Map.of("server", "field-kit"),
                ManagementCaller.ANONYMOUS);

        StatusData data = (StatusData) result.data();
        assertThat(data.state()).isEqualTo("RUNNING");
        assertThat(data.tokenConfigured()).isTrue();
        assertThat(data.connect()).singleElement()
                .satisfies(snippet -> assertThat(snippet.content()).doesNotContain("secret-token"));
        assertThat(data.changedSinceShipped()).isEmpty();
        assertThat(result.message()).contains("RUNNING");
    }

    @Test
    void logsClampTheLineCountAndDeleteGoesThroughTheMutabilityRule() {
        when(shipyardService.logsTail("field-kit", 1000)).thenReturn("line");

        ManagementResult logs = tool("saip_shipyard_logs").handle(Map.of("server", "field-kit", "lines", 5000),
                ManagementCaller.ANONYMOUS);

        assertThat(logs.message()).isEqualTo("Last 1000 line(s).");
        org.mockito.Mockito.doThrow(new IllegalStateException("Stop the container before deleting the bundle"))
                .when(shipyardService).requireMutable("field-kit", "deleting");
        assertThatThrownBy(() -> tool("saip_shipyard_delete").approvalFor(Map.of("server", "field-kit")))
                .hasMessageContaining("Stop the container");
        verify(shipyardService, never()).delete(anyString());
    }

    @Test
    void stopAsksPerServerAndTearsDownOnlyTheContainer() {
        when(shipyardService.entry("field-kit"))
                .thenReturn(new BundleEntry("field-kit", null, Path.of("/tmp/field-kit"), request));
        when(shipyardService.entry("ghost")).thenThrow(new IllegalArgumentException("unknown server 'ghost'"));

        ManagementApproval approval = tool("saip_shipyard_stop").approvalFor(Map.of("server", "field-kit"));
        ManagementResult result = tool("saip_shipyard_stop").handle(Map.of("server", "field-kit"),
                ManagementCaller.ANONYMOUS);

        assertThat(approval.level()).isEqualTo(ApprovalLevel.WRITE);
        assertThat(approval.summary()).isEqualTo("Stop the container of 'field-kit'");
        assertThat(result.message()).isEqualTo("Stopped 'field-kit'.");
        verify(shipyardService).stop("field-kit");
        verify(shipyardService, never()).delete(anyString());
        assertThatThrownBy(() -> tool("saip_shipyard_stop").approvalFor(Map.of("server", "ghost")))
                .hasMessageContaining("unknown server 'ghost'");
    }

    @Test
    void targetsListEveryEngineWithItsDockerReachability() {
        DeployTarget local = DeployTarget.local();
        DeployTarget lab = DeployTarget.docker("lab", "lab-ctx", null, "lab.example.com",
                DeployTarget.Origin.CONFIGURATION);
        when(deployTargetService.targets()).thenReturn(List.of(local, lab));
        when(deployTargetService.check(local)).thenReturn(new DockerAvailability(true, true, "28.1.0"));
        when(deployTargetService.check(lab)).thenReturn(new DockerAvailability(true, false, null));

        ManagementResult result = tool("saip_shipyard_targets").handle(Map.of(), ManagementCaller.ANONYMOUS);

        assertThat(result.message()).isEqualTo("2 target(s).");
        TargetsData data = (TargetsData) result.data();
        assertThat(data.targets()).extracting(TargetData::name).containsExactly("local", "lab");
        TargetData first = data.targets().getFirst();
        assertThat(first.local()).isTrue();
        assertThat(first.dockerUp()).isTrue();
        assertThat(first.dockerVersion()).isEqualTo("28.1.0");
        TargetData second = data.targets().getLast();
        assertThat(second.local()).isFalse();
        assertThat(second.where()).contains("lab-ctx");
        assertThat(second.dockerCliFound()).isTrue();
        assertThat(second.dockerUp()).isFalse();
    }

    @Test
    void theGuideAndPromptPointAtTheProcedure() {
        String guide = ((TextResourceContents) contribution.resources().getFirst().readHandler()
                .apply(null, new ReadResourceRequest(ShipyardManagementContribution.GUIDE_URI)).contents().getFirst())
                .text();

        assertThat(guide).contains("saip_shipyard_validate", "saip_shipyard_ship", "saip_shipyard_run",
                "saip_shipyard_status", "host.docker.internal");
        assertThat(contribution.prompts().getFirst().prompt().name())
                .isEqualTo(ShipyardManagementContribution.PROMPT_NAME);
        assertThat(contribution.tools()).extracting(ManagementTool::name).allMatch(name -> name.startsWith("saip_shipyard_"))
                .hasSize(10);
    }

    private static RunPlan plan(DeployTarget target, boolean dockerUp) {
        return new RunPlan(target, false, new DockerAvailability(true, dockerUp, "29.3.1"), List.of(), List.of(),
                Map.of(), Optional.empty(), false, Optional.empty(), "saip-mcp/field-kit:0.1.0", "localhost:18290",
                "docker compose -f /b/field-kit/compose.yaml up -d --build");
    }

    private static ServerState state(BundleEntry entry, DeployTarget target, ContainerState state,
            Optional<String> token) {
        return new ServerState(entry, target, new DockerAvailability(true, true, "29.3.1"),
                new ContainerStatus(state, 18290, List.of()), 18290, token, false, List.of());
    }

}
