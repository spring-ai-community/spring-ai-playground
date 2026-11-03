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
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerDetails;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.HealthProbe;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ProcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerCliServiceTest {

    private static final DeployTarget LOCAL = DeployTarget.local();
    private static final DeployTarget REMOTE = DeployTarget.docker("build-box", null, "ssh://ops@build.example.com",
            null, DeployTarget.Origin.ADDED);

    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> envs = new ArrayList<>();

    private DockerCliService service(ProcResult result) {
        return service(result, baseUrl -> true);
    }

    private DockerCliService service(ProcResult result, HealthProbe healthProbe) {
        return new DockerCliService((command, extraEnv, timeout) -> {
            commands.add(command);
            envs.add(extraEnv);
            return result;
        }, healthProbe, null);
    }

    @Test
    void probeReportsDaemonUpWithVersion() {
        DockerAvailability availability =
                service(new ProcResult(0, "27.1.1\n", "")).probe(LOCAL);
        assertThat(availability.cliFound()).isTrue();
        assertThat(availability.daemonUp()).isTrue();
        assertThat(availability.version()).isEqualTo("27.1.1");
    }

    @Test
    void installPathCandidatesCoverEachDesktopLayout() {
        assertThat(DockerCliService.installPathCandidates("Mac OS X", "/Users/jm"))
                .startsWith("/Users/jm/.docker/bin/docker")
                .contains("/opt/homebrew/bin/docker", "/usr/local/bin/docker");
        assertThat(DockerCliService.installPathCandidates("Windows 11", "C:\\Users\\jm"))
                .allMatch(path -> path.endsWith("docker.exe"));
        assertThat(DockerCliService.installPathCandidates("Linux", "/home/jm"))
                .containsExactly("/usr/bin/docker", "/usr/local/bin/docker");
    }

    @Test
    void aConfiguredExecutableIsTheOnlyCandidate() {
        new DockerCliService((command, extraEnv, timeout) -> {
            commands.add(command);
            return new ProcResult(0, "29.3.1\n", "");
        }, baseUrl -> true, "/opt/tools/podman").probe(LOCAL);
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().getFirst()).isEqualTo("/opt/tools/podman");
    }

    @Test
    void probeReportsCliMissing() {
        DockerCliService missing = new DockerCliService((command, extraEnv, timeout) -> {
            throw new UncheckedIOException(new IOException("no docker"));
        }, baseUrl -> true, null);
        DockerAvailability availability = missing.probe(LOCAL);
        assertThat(availability.cliFound()).isFalse();
        assertThat(availability.daemonUp()).isFalse();
    }

    @Test
    void statusStaysStartingUntilTheActuatorReportsUp() {
        ProcResult running = new ProcResult(0, "running|0.0.0.0:49200->8282/tcp\n", "");
        List<String> probed = new ArrayList<>();

        ContainerStatus starting = service(running, baseUrl -> {
            probed.add(baseUrl);
            return false;
        }).status(LOCAL, "my-server");

        assertThat(starting.state()).isEqualTo(ContainerState.STARTING);
        assertThat(starting.hostPort()).isEqualTo(49200);
        assertThat(probed).containsExactly("http://127.0.0.1:49200");
    }

    @Test
    void statusParsesRunningContainerWithHostPort() {
        ContainerStatus status =
                service(new ProcResult(0, "running|0.0.0.0:49200->8282/tcp\n", "")).status(LOCAL, "my-server");
        assertThat(status.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(status.hostPort()).isEqualTo(49200);
        assertThat(commands.stream().filter(command -> command.contains("ps")).findFirst().orElseThrow())
                .containsSequence("--filter", "name=^saip-mcp-my-server$");
    }

    @Test
    void aRunningBundleCarriesItsUnavailableDependenciesAskedWithTheRunToken() {
        BundleDependencyMonitor.Dependency down = new BundleDependencyMonitor.Dependency("DeepWiki",
                BundleDependencyMonitor.Kind.UPSTREAM, BundleDependencyMonitor.State.DOWN, "refused", List.of("a"));
        BundleDependencyMonitor.Dependency up = new BundleDependencyMonitor.Dependency("ollama:qwen3.5:4b",
                BundleDependencyMonitor.Kind.CHAT_MODEL, BundleDependencyMonitor.State.UP, null, null);
        List<Optional<String>> tokens = new ArrayList<>();
        DockerCliService service = service(new ProcResult(0, "running|0.0.0.0:49200->8282/tcp\n", ""),
                new HealthProbe() {
                    @Override
                    public boolean serving(String baseUrl) {
                        return true;
                    }

                    @Override
                    public Optional<BundleDependencyMonitor.Status> bundleStatus(String baseUrl,
                            Optional<String> authToken) {
                        tokens.add(authToken);
                        return Optional.of(new BundleDependencyMonitor.Status(true, "my-server", List.of(down, up)));
                    }
                });
        service.up(LOCAL, Path.of("/tmp/bundle"), "my-server", Map.of(DockerCliService.AUTH_TOKEN_ENV, "run-token"));

        ContainerStatus status = service.status(LOCAL, "my-server");

        assertThat(status.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(status.unavailable()).containsExactly(down);
        assertThat(tokens).containsExactly(Optional.of("run-token"));
    }

    @Test
    void statusRecoversTheRunTokenFromTheContainerWhenNoneIsKnown() {
        List<Optional<String>> tokens = new ArrayList<>();
        DockerCliService service = new DockerCliService((command, extraEnv, timeout) -> {
            commands.add(command);
            if (command.contains("inspect")) {
                return new ProcResult(0, "PATH=/usr/bin\nSPRING_AI_PLAYGROUND_MCPSERVER_AUTHTOKEN=recovered-token\n",
                        "");
            }
            return new ProcResult(0, "running|0.0.0.0:49200->8282/tcp\n", "");
        }, new HealthProbe() {
            @Override
            public boolean serving(String baseUrl) {
                return true;
            }

            @Override
            public Optional<BundleDependencyMonitor.Status> bundleStatus(String baseUrl,
                    Optional<String> authToken) {
                tokens.add(authToken);
                return Optional.empty();
            }
        }, "docker");

        ContainerStatus status = service.status(REMOTE, "my-server");

        assertThat(status.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(tokens).containsExactly(Optional.of("recovered-token"));
        assertThat(service.lastAuthToken("my-server")).contains("recovered-token");
        assertThat(commands).anyMatch(command -> command.contains("inspect")
                && command.contains("--host") && command.contains("saip-mcp-my-server"));

        service.status(REMOTE, "my-server");

        assertThat(commands.stream().filter(command -> command.contains("inspect")).count()).isEqualTo(1);
    }

    @Test
    void statusReportsAbsentOnEmptyOutput() {
        assertThat(service(new ProcResult(0, "", "")).status(LOCAL, "my-server").state())
                .isEqualTo(ContainerState.ABSENT);
    }

    @Test
    void upBuildsTheBundleImageAndRunsComposeWithEnvPassedThroughNotInterpolated() {
        service(new ProcResult(0, "", "")).up(LOCAL, Path.of("/tmp/bundle"), "my-server",
                Map.of("API_KEY", "secret-value"));
        List<String> command = commands.getLast();
        assertThat(command).containsSequence("docker", "compose", "-f", "/tmp/bundle/compose.yaml",
                "up", "-d", "--build", "--remove-orphans");
        assertThat(String.join(" ", command)).doesNotContain("secret-value");
        assertThat(envs.getLast()).containsEntry("API_KEY", "secret-value");
    }

    @Test
    void upLeavesBlankValuesToTheInheritedEnvironment() {
        service(new ProcResult(0, "", "")).up(LOCAL, Path.of("/tmp/bundle"), "my-server",
                Map.of("API_KEY", "", DockerCliService.AUTH_TOKEN_ENV, " ", "REGION", "eu"));
        assertThat(envs.getLast()).containsOnlyKeys("REGION");
    }

    @Test
    void theFirstCommandResolvesTheExecutableWithoutAnExplicitProbe() {
        service(new ProcResult(0, "running|0.0.0.0:49200->8282/tcp\n", "")).status(LOCAL, "my-server");
        assertThat(commands).hasSize(3);
        assertThat(commands.getFirst()).containsSequence("version", "--format");
        assertThat(commands.get(1)).contains("ps");
        assertThat(commands.getLast()).contains("inspect");
        assertThat(commands.getLast().getFirst()).isEqualTo(commands.getFirst().getFirst());
    }

    @Test
    void upSurfacesComposeFailure() {
        assertThatThrownBy(() -> service(new ProcResult(1, "", "no such image"))
                .up(LOCAL, Path.of("/tmp/bundle"), "my-server", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such image");
    }

    @Test
    void upRemembersAuthTokenInMemoryOnly() {
        DockerCliService running = service(new ProcResult(0, "", ""));
        running.up(LOCAL, Path.of("/tmp/bundle"), "my-server",
                Map.of(DockerCliService.AUTH_TOKEN_ENV, "tok-123"));
        assertThat(running.lastAuthToken("my-server")).contains("tok-123");

        running.up(LOCAL, Path.of("/tmp/bundle"), "my-server", Map.of());
        assertThat(running.lastAuthToken("my-server")).isEmpty();
    }

    @Test
    void logsTailKeepsTheContainerStderrLines() {
        assertThat(service(new ProcResult(0, "out\n", "err\n")).logsTail(LOCAL, "my-server", 5))
                .isEqualTo("out\nerr\n");
        assertThat(service(new ProcResult(0, "out\n", "")).logsTail(LOCAL, "my-server", 5)).isEqualTo("out\n");
        assertThat(service(new ProcResult(1, "", "no such container")).logsTail(LOCAL, "my-server", 5))
                .isEqualTo("no such container");
    }

    @Test
    void detailsParseTheInspectFormatAndAreEmptyForAnUnknownContainer() {
        Optional<ContainerDetails> details = service(new ProcResult(0,
                "running|2026-10-22T10:00:00Z|0001-01-01T00:00:00Z|0|2|healthy\n", "")).details(LOCAL, "my-server");

        assertThat(details).contains(new ContainerDetails("running", "2026-10-22T10:00:00Z",
                "0001-01-01T00:00:00Z", 0, 2, "healthy"));
        assertThat(commands.getLast()).containsSequence("inspect", "--format", DockerCliService.DETAILS_FORMAT,
                "saip-mcp-my-server");
        assertThat(service(new ProcResult(1, "", "Error: No such object")).details(LOCAL, "my-server")).isEmpty();
        assertThat(service(new ProcResult(0, "running|x\n", "")).details(LOCAL, "my-server")).isEmpty();
    }

    @Test
    void rejectsInvalidServerName() {
        assertThatThrownBy(() -> service(new ProcResult(0, "", "")).logsTail(LOCAL, "bad name; rm -rf", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyCommandForARemoteTargetCarriesItsHostWithoutTouchingTheGlobalContext() {
        List<String> probed = new ArrayList<>();
        DockerCliService remote = service(new ProcResult(0, "running|0.0.0.0:18282->8282/tcp\n", ""), baseUrl -> {
            probed.add(baseUrl);
            return true;
        });

        remote.probe(REMOTE);
        remote.up(REMOTE, Path.of("/tmp/bundle"), "my-server", Map.of(DockerCliService.AUTH_TOKEN_ENV, "tok"));
        remote.status(REMOTE, "my-server");
        remote.logsTail(REMOTE, "my-server", 5);
        remote.down(REMOTE, Path.of("/tmp/bundle"), "my-server");

        assertThat(commands).allSatisfy(command ->
                assertThat(command).containsSequence("--host", "ssh://ops@build.example.com"));
        assertThat(commands).noneMatch(command -> command.contains("use"));
        assertThat(probed).containsExactly("http://build.example.com:18282");
    }

    @Test
    void aContextTargetPassesTheContextName() {
        DeployTarget context = DeployTarget.docker("prod", "prod", "tcp://10.0.0.5:2376", null,
                DeployTarget.Origin.DOCKER_CONTEXT);
        service(new ProcResult(0, "", "")).logsTail(context, "my-server", 5);
        assertThat(commands.getLast()).containsSequence("--context", "prod", "logs");
    }

    @Test
    void contextsAreReadFromTheJsonListing() {
        List<DockerCliService.DockerContext> contexts = service(new ProcResult(0, """
                {"Current":true,"Description":"","DockerEndpoint":"unix:///var/run/docker.sock","Name":"default"}
                {"Current":false,"Description":"","DockerEndpoint":"ssh://ops@build.example.com","Name":"build-box"}
                not json
                """, "")).contexts();
        assertThat(contexts).containsExactly(
                new DockerCliService.DockerContext("default", "unix:///var/run/docker.sock"),
                new DockerCliService.DockerContext("build-box", "ssh://ops@build.example.com"));
    }

    @Test
    void aProbeThatTimesOutReportsTheTargetUnreachable() {
        DockerCliService hanging = new DockerCliService((command, extraEnv, timeout) -> {
            throw new IllegalStateException("Command timed out");
        }, baseUrl -> true, null);
        DockerAvailability availability = hanging.probe(REMOTE);
        assertThat(availability.cliFound()).isTrue();
        assertThat(availability.daemonUp()).isFalse();
    }

}
