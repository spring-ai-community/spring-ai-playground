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
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.Kind;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.Line;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunPlan;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunRequest;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunResult;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RunPlannerTest {

    private static final String TOOL_ID = "run-planner-t1";
    private static final String SERVER_NAME = "run-planner-server";

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @TempDir
    Path home;

    private final List<List<String>> commands = new ArrayList<>();
    private BundleEntry entry;
    private DeployTargetService targets;

    @BeforeEach
    void shipABundle() {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "runPlannerTool", "echoes", List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));
        this.bundleExportService.exportBundle(new ShipRequest(SERVER_NAME, "run planner bundle", "0.1.0",
                List.of(TOOL_ID), Transport.HTTP, 18400, new BundleManifest.Models(null, null)));
        this.entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
    }

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    private RunPlanner planner(Map<String, String> properties, String ollamaTags) {
        this.targets = DeployTargetFixtures.service(this.home, properties, Set.of("offline"), this.commands);
        return new RunPlanner(this.targets, new OllamaModelProbe(baseUrl -> Optional.ofNullable(ollamaTags)),
                this.bundleRegistryService);
    }

    private DeployTarget target(String name) {
        return this.targets.find(name).orElseThrow();
    }

    @Test
    void aLocalPlanNeedsNoTokenAndBuildsOnThisEngine() {
        RunPlanner planner = planner(Map.of(), null);

        RunPlan plan = planner.plan(this.entry, DeployTarget.local(), "base:latest", null);

        assertThat(plan.tokenRequired()).isFalse();
        assertThat(plan.configuredToken()).isEmpty();
        assertThat(plan.endpoint()).isEqualTo("localhost:18400");
        assertThat(plan.commandPreview()).startsWith("docker compose -f ").doesNotContain("--context");
        assertThat(plan.targetLines()).extracting(Line::kind).containsExactly(Kind.OK);
        assertThat(plan.targetLines().getFirst().text()).startsWith("Docker 29.3.1 reachable on Docker on this machine");
        assertThat(plan.modelLines()).isEmpty();
        assertThat(plan.runnable("")).isTrue();
    }

    @Test
    void aRemotePlanRequiresATokenAndExplainsTheContainerHost() {
        RunPlanner planner = planner(Map.of(), null);

        RunPlan plan = planner.plan(this.entry, target("build-box"), "base:latest", null);

        assertThat(plan.tokenRequired()).isTrue();
        assertThat(plan.runnable("")).isFalse();
        assertThat(plan.runnable("token")).isTrue();
        assertThat(plan.endpoint()).isEqualTo("build.example.com:18400");
        assertThat(plan.commandPreview()).startsWith("docker --context build-box compose -f ");
        assertThat(plan.targetLines()).extracting(Line::kind).containsExactly(Kind.OK, Kind.NOTE, Kind.NOTE);
        assertThat(plan.targetLines()).extracting(Line::text)
                .anyMatch(text -> text.startsWith("The bundle folder is sent as build context"))
                .contains("host.docker.internal inside the container means build.example.com, not this machine");
    }

    @Test
    void loopbackUpstreamUrlsPointAtTheHostTheContainerReaches() {
        assertThat(RunPlanner.reachableFromContainer("http://localhost:18611")).isEqualTo("http://host.docker.internal:18611");
        assertThat(RunPlanner.reachableFromContainer("http://127.0.0.1:18611/mcp?x=1"))
                .isEqualTo("http://host.docker.internal:18611/mcp?x=1");
        assertThat(RunPlanner.reachableFromContainer("http://[::1]:18611")).isEqualTo("http://host.docker.internal:18611");
        assertThat(RunPlanner.reachableFromContainer("https://mcp.example.com/mcp")).isEqualTo("https://mcp.example.com/mcp");
        assertThat(RunPlanner.reachableFromContainer("http://host.docker.internal:18611"))
                .isEqualTo("http://host.docker.internal:18611");
        assertThat(RunPlanner.reachableFromContainer("http://localhost:${PORT}")).isEqualTo("http://localhost:${PORT}");
    }

    @Test
    void anUnreachableTargetIsNeverRunnable() {
        RunPlanner planner = planner(Map.of(), null);

        RunPlan plan = planner.plan(this.entry, target("offline"), "base:latest", null);

        assertThat(plan.docker().daemonUp()).isFalse();
        assertThat(plan.runnable("token")).isFalse();
        assertThat(plan.targetLines().getFirst())
                .isEqualTo(new Line(Kind.WARN, "Docker on docker context offline (tcp://10.9.9.9:2375) is not reachable"));
    }

    @Test
    void configuredRunSettingsAreReportedByThePlan() {
        RunPlanner planner = planner(Map.of(DockerCliService.AUTH_TOKEN_ENV, "configured-token",
                BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, "http://ollama.lab:11434"), null);

        RunPlan plan = planner.plan(this.entry, DeployTarget.local(), "base:latest", null);

        assertThat(plan.configuredToken()).contains("configured-token");
        assertThat(plan.configuredOllamaEndpoint()).contains("http://ollama.lab:11434");
        assertThat(planner.defaultOllamaEndpoint()).isEqualTo("http://ollama.lab:11434");
    }

    @Test
    void modelLinesTellWhichOllamaModelsArePulled() {
        RunPlanner planner = planner(Map.of(), "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}");
        BundleManifest withModels = withModels(this.entry.manifest(), new BundleManifest.Models(
                new BundleManifest.ModelRef("ollama", "qwen3.5:4b"),
                new BundleManifest.ModelRef("ollama", "qwen3-embedding:0.6b")));

        List<Line> lines = planner.modelLines(withModels, DeployTarget.local(), null);

        assertThat(lines).containsExactly(new Line(Kind.OK, "qwen3.5:4b is pulled"),
                new Line(Kind.WARN, "qwen3-embedding:0.6b is not pulled; run: ollama pull qwen3-embedding:0.6b"));
    }

    @Test
    void anUnreachableOllamaIsReportedOnce() {
        RunPlanner planner = planner(Map.of(), null);
        BundleManifest withModels = withModels(this.entry.manifest(), new BundleManifest.Models(
                new BundleManifest.ModelRef("ollama", "qwen3.5:4b"), null));

        List<Line> lines = planner.modelLines(withModels, DeployTarget.local(), "http://localhost:11434");

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().kind()).isEqualTo(Kind.WARN);
        assertThat(lines.getFirst().text()).startsWith("Ollama is not reachable at http://localhost:11434");
    }

    @Test
    void runPassesTheOllamaOverrideAndTokenAsEnvironment() {
        BundleManifest withModels = withModels(this.entry.manifest(), new BundleManifest.Models(
                new BundleManifest.ModelRef("ollama", "qwen3.5:4b"), null));
        assertThat(RunPlanner.environment(withModels, Map.of("API_KEY", "k"), "http://ollama.lab:11434", "tok"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("API_KEY", "k",
                        BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, "http://ollama.lab:11434",
                        DockerCliService.AUTH_TOKEN_ENV, "tok"));
        assertThat(RunPlanner.environment(this.entry.manifest(), Map.of(), "http://x", "")).isEmpty();
    }

    @Test
    void runStartsTheContainerOnTheRememberedTargetWithTheGivenToken() {
        RunPlanner planner = planner(Map.of(), null);

        RunResult result = planner.run(new RunRequest(SERVER_NAME, null, Map.of(), null, "tok"));

        assertThat(result.target().isLocal()).isTrue();
        assertThat(result.authToken()).contains("tok");
        assertThat(this.commands).anySatisfy(command -> assertThat(command).contains("compose", "up", "--build"));
    }

    @Test
    void runOnARemoteTargetGeneratesATokenWhenNoneIsGiven() {
        RunPlanner planner = planner(Map.of(), null);

        RunResult result = planner.run(new RunRequest(SERVER_NAME, "build-box", Map.of(), null, null));

        assertThat(result.target().name()).isEqualTo("build-box");
        assertThat(result.authToken()).hasValueSatisfying(token -> assertThat(token).hasSizeGreaterThan(20));
        assertThat(this.targets.targetOf(SERVER_NAME).name()).isEqualTo("build-box");
    }

    @Test
    void runRefusesAnUnreachableTargetAndAnUnknownServer() {
        RunPlanner planner = planner(Map.of(), null);

        assertThatThrownBy(() -> planner.run(new RunRequest(SERVER_NAME, "offline", Map.of(), null, "tok")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker on docker context offline (tcp://10.9.9.9:2375) is not reachable");
        assertThatThrownBy(() -> planner.run(new RunRequest("no-such", null, Map.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No server named 'no-such'");
    }

    private static BundleManifest withModels(BundleManifest manifest, BundleManifest.Models models) {
        return new BundleManifest(manifest.bundleVersion(), manifest.server(),
                new BundleManifest.Requires(manifest.requires().minRuntimeVersion(), manifest.requires().env(),
                        models, manifest.requires().upstreamServers()),
                manifest.contents(), manifest.risk(), manifest.integrity(), manifest.provenance());
    }

}
