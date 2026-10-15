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

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = {
        "spring.ai.playground.user-home=${java.io.tmpdir}/shipyard-docker-live-home",
        "spring.ai.playground.shipyard.image-repository=saip-standin",
        "spring.ai.playground.shipyard.image-tag=live" })
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_IT", matches = "true")
class ShipyardDockerLiveTest {

    private static final Logger logger = LoggerFactory.getLogger(ShipyardDockerLiveTest.class);
    private static final String ENGINE_IMAGE = "docker:28-dind";
    private static final int ENGINE_PORT = 2375;
    private static final String BASE_IMAGE = "busybox:1.37";
    private static final String STANDIN_IMAGE = "saip-standin:live";
    private static final String STANDIN_DOCKERFILE = """
            FROM busybox:1.37
            RUN mkdir -p /www/actuator \\
                && printf '{"status":"UP"}' > /www/actuator/health \\
                && printf '{"serving":true,"server":"standin","dependencies":[]}' > /www/actuator/bundle
            EXPOSE 8282
            CMD ["sh", "-c", "echo standin listening on 8282; exec httpd -f -p 8282 -h /www"]
            """;
    private static final String REMOTE_TARGET = "lab";
    private static final String REMOTE_SERVER = "lab-live";
    private static final String LOCAL_SERVER = "local-live";
    private static final String TOOL_ID = "docker-live-1";
    private static final Duration ENGINE_STARTUP = Duration.ofMinutes(3);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);

    private static GenericContainer<?> engine;
    private static int remotePort;
    private static int localPort;
    private static String dockerExecutable;
    private static Path workDir;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private DeployTargetService deployTargetService;

    @Autowired
    private ToolSpecService toolSpecService;

    @BeforeAll
    static void requiresALocalEngineAndTheDockerCli() throws IOException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker engine reachable for Testcontainers; skipping");
        dockerExecutable = findDockerCli();
        assumeTrue(dockerExecutable != null, "No docker CLI on this machine; skipping");
        FileSystemUtils.deleteRecursively(Path.of(System.getProperty("java.io.tmpdir"),
                "shipyard-docker-live-home"));
        workDir = Files.createTempDirectory("shipyard-docker-live-");
        Files.createDirectories(workDir.resolve("standin"));
        Files.writeString(workDir.resolve("standin").resolve("Dockerfile"), STANDIN_DOCKERFILE);
        docker(DeployTarget.local(), "pull", BASE_IMAGE);
        docker(DeployTarget.local(), "save", "-o", workDir.resolve("base.tar").toString(), BASE_IMAGE);
        buildStandin(DeployTarget.local());
    }

    @DynamicPropertySource
    static void aRemoteEngineAsAConfiguredTarget(DynamicPropertyRegistry registry) {
        remotePort = freePort();
        localPort = freePort();
        engine = new GenericContainer<>(ENGINE_IMAGE)
                .withPrivilegedMode(true)
                .withEnv("DOCKER_TLS_CERTDIR", "")
                .withExposedPorts(ENGINE_PORT, remotePort)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(ENGINE_PORT)),
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", remotePort),
                                ExposedPort.tcp(remotePort))))
                .waitingFor(Wait.forLogMessage(".*API listen on \\[::\\]:" + ENGINE_PORT + ".*", 1))
                .withStartupTimeout(ENGINE_STARTUP);
        engine.start();
        registry.add("spring.ai.playground.shipyard.targets[0].name", () -> REMOTE_TARGET);
        registry.add("spring.ai.playground.shipyard.targets[0].docker-host",
                () -> "tcp://127.0.0.1:" + engine.getMappedPort(ENGINE_PORT));
    }

    @AfterAll
    static void stopTheRemoteEngine() throws IOException {
        if (engine != null) {
            engine.stop();
        }
        if (dockerExecutable != null) {
            quietly(() -> docker(DeployTarget.local(), "rmi", "-f", STANDIN_IMAGE));
        }
        if (workDir != null) {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @BeforeEach
    void seedATool() {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "dockerLiveEcho", "echoes its input", List.of(),
                List.of(new ToolParamSpec("text", "input", true, JsonSchemaType.STRING, "hello")),
                "return text;", CodeType.Javascript, null));
    }

    @AfterEach
    void stopAndForgetEverything() {
        for (String server : List.of(REMOTE_SERVER, LOCAL_SERVER)) {
            this.bundleRegistryService.find(server).ifPresent(entry -> {
                quietly(() -> this.deployTargetService.stop(entry));
                quietly(() -> docker(this.deployTargetService.targetOf(server), "rmi", "-f",
                        BundleArtifactWriter.localImage(entry.manifest())));
                this.bundleRegistryService.delete(server);
            });
            this.deployTargetService.forget(server);
        }
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    @Test
    void aBundleIsBuiltAndRunOnTheRemoteEngineAndRememberedThere() {
        DeployTarget lab = this.deployTargetService.find(REMOTE_TARGET).orElseThrow();
        assertThat(lab.origin()).isEqualTo(DeployTarget.Origin.CONFIGURATION);
        assertThat(lab.isRemote()).isTrue();
        DockerCliService.DockerAvailability availability = this.deployTargetService.check(lab);
        assertThat(availability.daemonUp()).isTrue();
        assertThat(availability.version()).startsWith("28.");
        buildStandin(lab);

        BundleEntry entry = export(REMOTE_SERVER, remotePort);
        assertThatThrownBy(() -> this.deployTargetService.run(entry, lab, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bearer token");

        String token = UUID.randomUUID().toString().replace("-", "");
        this.deployTargetService.run(entry, lab, Map.of(DockerCliService.AUTH_TOKEN_ENV, token));
        ContainerStatus status = awaitRunning(REMOTE_SERVER);
        assertThat(status.hostPort()).isEqualTo(remotePort);
        assertThat(status.unavailable()).isEmpty();
        assertThat(ConnectionSnippetService.mcpUrl(lab, status.hostPort()))
                .isEqualTo("http://127.0.0.1:" + remotePort + "/mcp");
        assertThat(this.deployTargetService.targetOf(REMOTE_SERVER).name()).isEqualTo(REMOTE_TARGET);
        assertThat(this.deployTargetService.authToken(REMOTE_SERVER)).contains(token);

        String builtImage = BundleArtifactWriter.localImage(entry.manifest());
        assertThat(images(lab)).contains(builtImage);
        assertThat(images(DeployTarget.local())).doesNotContain(builtImage);
        assertThat(this.deployTargetService.logsTail(REMOTE_SERVER, 50)).contains("standin listening on 8282");

        this.deployTargetService.stop(entry);
        assertThat(this.deployTargetService.status(REMOTE_SERVER).state()).isEqualTo(ContainerState.ABSENT);
        assertThat(this.deployTargetService.targetOf(REMOTE_SERVER).name()).isEqualTo(REMOTE_TARGET);
    }

    @Test
    void aBundleRunsOnTheLocalEngineWithoutAToken() {
        BundleEntry entry = export(LOCAL_SERVER, localPort);
        this.deployTargetService.run(entry, DeployTarget.local(), Map.of());
        ContainerStatus status = awaitRunning(LOCAL_SERVER);
        assertThat(status.hostPort()).isEqualTo(localPort);
        assertThat(this.deployTargetService.targetOf(LOCAL_SERVER).isLocal()).isTrue();
        assertThat(this.deployTargetService.authToken(LOCAL_SERVER)).isEmpty();
        assertThat(images(DeployTarget.local())).contains(BundleArtifactWriter.localImage(entry.manifest()));

        this.deployTargetService.stop(entry);
        assertThat(this.deployTargetService.status(LOCAL_SERVER).state()).isEqualTo(ContainerState.ABSENT);
    }

    private BundleEntry export(String serverName, int httpPort) {
        ShipRequest request = new ShipRequest(serverName, "docker live test", "0.1.0", List.of(TOOL_ID),
                Transport.HTTP, httpPort, null);
        assertThat(this.bundleExportService.validate(request).blockers()).isEmpty();
        this.bundleExportService.exportBundle(request);
        return this.bundleRegistryService.find(serverName).orElseThrow();
    }

    private ContainerStatus awaitRunning(String serverName) {
        await().atMost(READY_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> this.deployTargetService.status(serverName).state() == ContainerState.RUNNING);
        return this.deployTargetService.status(serverName);
    }

    private static void buildStandin(DeployTarget target) {
        if (target.isRemote()) {
            docker(target, "load", "-i", workDir.resolve("base.tar").toString());
        }
        docker(target, "build", "-t", STANDIN_IMAGE, workDir.resolve("standin").toString());
    }

    private static List<String> images(DeployTarget target) {
        return docker(target, "images", "--format", "{{.Repository}}:{{.Tag}}").lines().toList();
    }

    private static String findDockerCli() {
        List<String> candidates = new ArrayList<>();
        candidates.add("docker");
        DockerCliService.installPathCandidates(System.getProperty("os.name"), System.getProperty("user.home"))
                .stream().filter(path -> Files.isExecutable(Path.of(path))).forEach(candidates::add);
        for (String candidate : candidates) {
            try {
                run(List.of(candidate, "version", "--format", "{{.Server.Version}}"));
                return candidate;
            } catch (RuntimeException e) {
                logger.debug("Not a working docker CLI: {}", candidate);
            }
        }
        return null;
    }

    private static String docker(DeployTarget target, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(dockerExecutable);
        command.addAll(target.cliArguments());
        command.addAll(List.of(arguments));
        return run(command);
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed (exit "
                        + process.exitValue() + "): " + stderr.join().strip());
            }
            return stdout.join();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to run " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + String.join(" ", command), e);
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read process output", e);
        }
    }

    private static void quietly(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            logger.warn("Cleanup step failed: {}", e.getMessage());
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port", e);
        }
    }

}
