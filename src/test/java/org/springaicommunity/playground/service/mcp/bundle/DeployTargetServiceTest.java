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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget.Origin;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ProcResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployTargetServiceTest {

    private static final String CONTEXTS = """
            {"Name":"default","DockerEndpoint":"unix:///var/run/docker.sock"}
            {"Name":"colima","DockerEndpoint":"unix:///Users/me/.colima/docker.sock"}
            {"Name":"build-box","DockerEndpoint":"ssh://ops@build.example.com"}
            {"Name":"prod","DockerEndpoint":"tcp://10.0.0.5:2376"}
            """;

    @TempDir
    Path home;

    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> envs = new ArrayList<>();

    private DeployTargetService service(Map<String, String> properties) {
        DockerCliService docker = new DockerCliService((command, extraEnv, timeout) -> {
            this.commands.add(command);
            this.envs.add(extraEnv);
            if (command.contains("context")) return new ProcResult(0, CONTEXTS, "");
            if (command.contains("version")) return new ProcResult(0, "29.3.1\n", "");
            return new ProcResult(0, "", "");
        }, baseUrl -> true, "docker");
        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return new DeployTargetService(docker, options, this.home, environment);
    }

    private DeployTargetService service() {
        return service(Map.of());
    }

    private BundleEntry entry(String name) {
        return new BundleEntry(name, null, this.home.resolve("mcp/bundles/" + name), null);
    }

    @Test
    void targetsListTheBuiltInOneFirstThenConfigurationThenAddedThenRemoteContexts() {
        DeployTargetService service = service(Map.of(
                "spring.ai.playground.shipyard.targets[0].name", "prod",
                "spring.ai.playground.shipyard.targets[0].docker-context", "prod",
                "spring.ai.playground.shipyard.targets[0].public-host", "mcp.example.com"));
        service.addTarget("lab", "tcp://192.168.1.20:2375", null);

        List<DeployTarget> targets = service.targets();

        assertThat(targets).extracting(DeployTarget::name).containsExactly("local", "prod", "lab", "build-box");
        assertThat(targets).extracting(DeployTarget::origin).containsExactly(Origin.BUILT_IN,
                Origin.CONFIGURATION, Origin.ADDED, Origin.DOCKER_CONTEXT);
        DeployTarget prod = targets.get(1);
        assertThat(prod.cliArguments()).containsExactly("--context", "prod");
        assertThat(prod.dockerHost()).isEqualTo("tcp://10.0.0.5:2376");
        assertThat(prod.endpointHost()).isEqualTo("mcp.example.com");
    }

    @Test
    void anInvalidConfiguredTargetIsSkippedNotFatal() {
        DeployTargetService service = service(Map.of(
                "spring.ai.playground.shipyard.targets[0].name", "broken",
                "spring.ai.playground.shipyard.targets[0].docker-host", "http://nowhere"));
        assertThat(service.targets()).extracting(DeployTarget::name).doesNotContain("broken");
    }

    @Test
    void addedTargetsPersistAndOnlyThoseCanBeRemoved() {
        DeployTargetService service = service();
        service.addTarget("lab", "ssh://me@lab.local", "lab.example.com");

        assertThat(Files.exists(this.home.resolve("mcp").resolve(DeployTargetService.TARGETS_FILE))).isTrue();
        assertThat(service().find("lab")).hasValueSatisfying(target -> {
            assertThat(target.origin()).isEqualTo(Origin.ADDED);
            assertThat(target.endpointHost()).isEqualTo("lab.example.com");
        });
        assertThatThrownBy(() -> service.addTarget("lab", "ssh://other", null)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.removeTarget("build-box")).hasMessageContaining("Docker context");
        assertThatThrownBy(() -> service.removeTarget("local")).hasMessageContaining("Built in");

        service.removeTarget("lab");
        assertThat(service().find("lab")).isEmpty();
    }

    @Test
    void aServerRemembersTheTargetItLastRanOnAndItsCommandsFollowIt() {
        DeployTargetService service = service();
        DeployTarget buildBox = service.find("build-box").orElseThrow();

        service.run(entry("hotspots"), buildBox, Map.of(DockerCliService.AUTH_TOKEN_ENV, "tok"));
        DeployTargetService restarted = service();
        this.commands.clear();
        restarted.logsTail("hotspots", 5);

        assertThat(restarted.targetOf("hotspots").name()).isEqualTo("build-box");
        assertThat(restarted.targetOf("never-ran").name()).isEqualTo("local");
        assertThat(this.commands.getLast()).containsSequence("--context", "build-box", "logs");
    }

    @Test
    void aTargetThatWasUsedCannotBeRemovedAndAMissingOneFallsBackToLocal() {
        DeployTargetService service = service();
        DeployTarget lab = service.addTarget("lab", "tcp://192.168.1.20:2375", null);
        service.run(entry("hotspots"), lab, Map.of(DockerCliService.AUTH_TOKEN_ENV, "tok"));

        assertThatThrownBy(() -> service.removeTarget("lab")).hasMessageContaining("[hotspots]");

        service.forget("hotspots");
        service.removeTarget("lab");
        assertThat(service.targetOf("hotspots").name()).isEqualTo("local");
    }

    @Test
    void aRemoteRunWithoutABearerTokenIsRefused() {
        DeployTargetService service = service();
        DeployTarget buildBox = service.find("build-box").orElseThrow();

        assertThatThrownBy(() -> service.run(entry("hotspots"), buildBox, Map.of(DockerCliService.AUTH_TOKEN_ENV, " ")))
                .hasMessageContaining("bearer token");
        service.run(entry("hotspots"), DeployTarget.local(), Map.of());
        assertThat(service.targetOf("hotspots").isLocal()).isTrue();
    }

    @Test
    void aConfiguredServerTargetWinsAndCannotBeChangedFromTheUi() {
        DeployTargetService service = service(Map.of(
                "spring.ai.playground.shipyard.servers[0].name", "hotspots",
                "spring.ai.playground.shipyard.servers[0].target", "build-box"));

        assertThat(service.configuredTargetName("hotspots")).contains("build-box");
        assertThat(service.targetOf("hotspots").name()).isEqualTo("build-box");
        assertThatThrownBy(() -> service.run(entry("hotspots"), DeployTarget.local(), Map.of()))
                .hasMessageContaining("set in configuration");
    }

    @Test
    void runSettingsFromConfigurationWinOverTheDialogAndAreRemembered() {
        DeployTargetService service = service(Map.of(
                DockerCliService.AUTH_TOKEN_ENV, "configured-token",
                BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, "http://ollama.lan:11434"));
        DeployTarget buildBox = service.find("build-box").orElseThrow();

        service.run(entry("hotspots"), buildBox, Map.of(DockerCliService.AUTH_TOKEN_ENV, "",
                BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, "http://host.docker.internal:11434"));

        assertThat(this.envs.getLast()).containsEntry(DockerCliService.AUTH_TOKEN_ENV, "configured-token")
                .containsEntry(BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, "http://ollama.lan:11434");
        assertThat(service.authToken("hotspots")).contains("configured-token");
        assertThat(service.configuredRunSetting(DockerCliService.AUTH_TOKEN_ENV)).contains("configured-token");
        assertThat(service().configuredRunSetting(DockerCliService.AUTH_TOKEN_ENV)).isEmpty();
    }

    @Test
    void aConfiguredTokenIsKnownAfterARestartWithoutAnyRunFromThisProcess() {
        DeployTargetService restarted = service(Map.of(DockerCliService.AUTH_TOKEN_ENV, "configured-token"));
        assertThat(restarted.authToken("hotspots")).contains("configured-token");
    }

}
