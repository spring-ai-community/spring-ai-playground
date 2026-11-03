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
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DockerCliService {

    private static final Logger logger = LoggerFactory.getLogger(DockerCliService.class);
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile(":(\\d+)->8282/tcp");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(2);
    public static final String CONTAINER_PREFIX = "saip-mcp-";
    public static final String AUTH_TOKEN_ENV = "SAIP_MCP_AUTH_TOKEN";
    public static final String CONTAINER_AUTH_TOKEN_ENV = "SPRING_AI_PLAYGROUND_MCPSERVER_AUTHTOKEN";
    private static final String DEFAULT_EXECUTABLE = "docker";

    static final String DETAILS_FORMAT = "{{.State.Status}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|"
            + "{{.State.ExitCode}}|{{.RestartCount}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}";

    public record ProcResult(int exitCode, String stdout, String stderr) {

        public boolean ok() {
            return exitCode == 0;
        }
    }

    public interface CommandRunner {

        ProcResult run(List<String> command, Map<String, String> extraEnv, Duration timeout);
    }

    public interface HealthProbe {

        boolean serving(String baseUrl);

        default Optional<BundleDependencyMonitor.Status> bundleStatus(String baseUrl, Optional<String> authToken) {
            return Optional.empty();
        }
    }

    public record DockerContext(String name, String endpoint) {}

    public record DockerAvailability(boolean cliFound, boolean daemonUp, String version) {}

    public enum ContainerState { RUNNING, STARTING, STOPPED, ABSENT }

    public record ContainerStatus(ContainerState state, Integer hostPort,
            List<BundleDependencyMonitor.Dependency> unavailable) {

        public ContainerStatus {
            unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
        }

        public ContainerStatus(ContainerState state, Integer hostPort) {
            this(state, hostPort, null);
        }
    }

    private final CommandRunner commandRunner;
    private final HealthProbe healthProbe;
    private final String configuredExecutable;
    private volatile String dockerExecutable;
    private final Map<String, String> lastAuthTokens = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> inspectedTokens = new ConcurrentHashMap<>();

    @Autowired
    public DockerCliService(SpringAiPlaygroundOptions playgroundOptions) {
        this(DockerCliService::runProcess, new BundleHealthProbe(), playgroundOptions.shipyard().dockerExecutable());
    }

    DockerCliService(CommandRunner commandRunner, HealthProbe healthProbe, String configuredExecutable) {
        this.commandRunner = commandRunner;
        this.healthProbe = healthProbe;
        this.configuredExecutable = configuredExecutable == null || configuredExecutable.isBlank()
                ? null : configuredExecutable;
    }

    public DockerAvailability probe(DeployTarget target) {
        for (String candidate : executableCandidates()) {
            Optional<ProcResult> version = tryVersion(candidate, target);
            if (version.isPresent()) {
                this.dockerExecutable = candidate;
                ProcResult result = version.get();
                return new DockerAvailability(true, result.ok(),
                        result.ok() ? result.stdout().strip() : null);
            }
        }
        return new DockerAvailability(false, false, null);
    }

    private String executable() {
        if (this.dockerExecutable == null) {
            probe(DeployTarget.local());
        }
        return Objects.requireNonNullElse(this.dockerExecutable, DEFAULT_EXECUTABLE);
    }

    private Optional<ProcResult> tryVersion(String executable, DeployTarget target) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(target.cliArguments());
        command.addAll(List.of("version", "--format", "{{.Server.Version}}"));
        try {
            return Optional.of(this.commandRunner.run(command, Map.of(), PROBE_TIMEOUT));
        } catch (UncheckedIOException e) {
            return Optional.empty();
        } catch (IllegalStateException e) {
            return Optional.of(new ProcResult(1, "", e.getMessage()));
        }
    }

    private List<String> command(DeployTarget target, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.addAll(target.cliArguments());
        command.addAll(List.of(arguments));
        return command;
    }

    public List<DockerContext> contexts() {
        ProcResult result;
        try {
            result = this.commandRunner.run(List.of(executable(), "context", "ls", "--format", "{{json .}}"),
                    Map.of(), PROBE_TIMEOUT);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!result.ok()) return List.of();
        List<DockerContext> contexts = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> fields = PersistenceServiceInterface.OBJECT_MAPPER.readValue(line,
                        PersistenceServiceInterface.MAP_TYPE_REFERENCE);
                Object name = fields.get("Name");
                Object endpoint = fields.get("DockerEndpoint");
                if (name != null && endpoint != null) {
                    contexts.add(new DockerContext(name.toString(), endpoint.toString()));
                }
            } catch (RuntimeException e) {
                logger.debug("Skipping unreadable docker context line: {}", line);
            }
        }
        return contexts;
    }

    private List<String> executableCandidates() {
        if (this.configuredExecutable != null) {
            return List.of(this.configuredExecutable);
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(DEFAULT_EXECUTABLE);
        installPathCandidates(System.getProperty("os.name"), System.getProperty("user.home")).stream()
                .filter(path -> Files.isExecutable(Path.of(path)))
                .forEach(candidates::add);
        return candidates;
    }

    static List<String> installPathCandidates(String osName, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
        }
        if (os.contains("mac")) {
            return List.of(userHome + "/.docker/bin/docker",
                    "/usr/local/bin/docker",
                    "/opt/homebrew/bin/docker",
                    "/Applications/Docker.app/Contents/Resources/bin/docker");
        }
        return List.of("/usr/bin/docker", "/usr/local/bin/docker");
    }

    public ContainerStatus status(DeployTarget target, String serverName) {
        return status(target, serverName, lastAuthToken(serverName));
    }

    public ContainerStatus status(DeployTarget target, String serverName, Optional<String> authToken) {
        String container = containerName(serverName);
        ProcResult result = this.commandRunner.run(
                command(target, "ps", "-a", "--filter", "name=^" + container + "$",
                        "--format", "{{.State}}|{{.Ports}}"), Map.of(), COMMAND_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            this.inspectedTokens.remove(serverName);
            return new ContainerStatus(ContainerState.ABSENT, null);
        }
        String[] parts = result.stdout().strip().split("\\|", 2);
        boolean running = "running".equalsIgnoreCase(parts[0]);
        if (!running) this.inspectedTokens.remove(serverName);
        Optional<String> token = authToken.or(() -> running
                ? this.inspectedTokens.computeIfAbsent(serverName, name -> containerAuthToken(target, name))
                : Optional.empty());
        Integer hostPort = null;
        if (running && parts.length > 1) {
            Matcher matcher = HOST_PORT_PATTERN.matcher(parts[1]);
            if (matcher.find()) {
                hostPort = Integer.parseInt(matcher.group(1));
            }
        }
        if (!running) {
            return new ContainerStatus(ContainerState.STOPPED, null);
        }
        if (hostPort != null && !this.healthProbe.serving(target.probeUrl(hostPort))) {
            return new ContainerStatus(ContainerState.STARTING, hostPort);
        }
        List<BundleDependencyMonitor.Dependency> unavailable = hostPort == null ? List.of()
                : this.healthProbe.bundleStatus(target.probeUrl(hostPort), token)
                        .map(BundleDependencyMonitor.Status::unavailable).orElse(List.of());
        return new ContainerStatus(ContainerState.RUNNING, hostPort, unavailable);
    }

    Optional<String> containerAuthToken(DeployTarget target, String serverName) {
        ProcResult result;
        try {
            result = this.commandRunner.run(command(target, "inspect", "--format",
                    "{{range .Config.Env}}{{println .}}{{end}}", containerName(serverName)), Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!result.ok()) return Optional.empty();
        Optional<String> token = result.stdout().lines()
                .filter(line -> line.startsWith(CONTAINER_AUTH_TOKEN_ENV + "="))
                .map(line -> line.substring(CONTAINER_AUTH_TOKEN_ENV.length() + 1).strip())
                .filter(value -> !value.isEmpty())
                .findFirst();
        token.ifPresent(value -> this.lastAuthTokens.put(serverName, value));
        return token;
    }

    public void awaitSettled(DeployTarget target, String serverName, Optional<String> authToken,
            Consumer<ContainerStatus> onSettled) {
        Thread watcher = new Thread(() -> {
            ContainerStatus settled = pollUntilSettled(target, serverName, authToken);
            if (settled != null) {
                onSettled.accept(settled);
            }
        }, "shipyard-await-" + serverName);
        watcher.setDaemon(true);
        watcher.start();
    }

    private ContainerStatus pollUntilSettled(DeployTarget target, String serverName, Optional<String> authToken) {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        ContainerStatus status = null;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            status = status(target, serverName, authToken);
            if (status.state() != ContainerState.STARTING) {
                return status;
            }
        }
        return status;
    }

    public void up(DeployTarget target, Path bundleDir, String serverName, Map<String, String> envValues) {
        containerName(serverName);
        Map<String, String> providedEnv = new LinkedHashMap<>();
        envValues.forEach((name, value) -> {
            if (value != null && !value.isBlank()) providedEnv.put(name, value);
        });
        ProcResult result = this.commandRunner.run(
                command(target, "compose", "-f", bundleDir.resolve("compose.yaml").toString(),
                        "up", "-d", "--build", "--remove-orphans"), providedEnv, COMPOSE_TIMEOUT);
        requireOk(result, "docker compose up failed");
        String token = providedEnv.get(AUTH_TOKEN_ENV);
        this.inspectedTokens.remove(serverName);
        if (token == null) {
            this.lastAuthTokens.remove(serverName);
        } else {
            this.lastAuthTokens.put(serverName, token);
        }
        logger.info("Started bundle container {} on target '{}'", containerName(serverName), target.name());
    }

    public Optional<String> lastAuthToken(String serverName) {
        return Optional.ofNullable(this.lastAuthTokens.get(serverName));
    }

    public void down(DeployTarget target, Path bundleDir, String serverName) {
        containerName(serverName);
        ProcResult result = this.commandRunner.run(
                command(target, "compose", "-f", bundleDir.resolve("compose.yaml").toString(), "down"),
                Map.of(), COMPOSE_TIMEOUT);
        requireOk(result, "docker compose down failed");
        this.inspectedTokens.remove(serverName);
        logger.info("Stopped bundle container {} on target '{}'", containerName(serverName), target.name());
    }

    public String logsTail(DeployTarget target, String serverName, int lines) {
        ProcResult result = this.commandRunner.run(
                command(target, "logs", "--tail", Integer.toString(Math.max(1, lines)),
                        containerName(serverName)), Map.of(), COMMAND_TIMEOUT);
        if (!result.ok()) return result.stderr();
        if (result.stderr().isBlank()) return result.stdout();
        if (result.stdout().isBlank()) return result.stderr();
        return result.stdout().stripTrailing() + "\n" + result.stderr();
    }

    public record ContainerDetails(String status, String startedAt, String finishedAt, Integer exitCode,
                                   Integer restartCount, String health) {
    }

    public Optional<ContainerDetails> details(DeployTarget target, String serverName) {
        ProcResult result;
        try {
            result = this.commandRunner.run(command(target, "inspect", "--format", DETAILS_FORMAT,
                    containerName(serverName)), Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!result.ok() || result.stdout().isBlank()) return Optional.empty();
        String[] parts = result.stdout().strip().split("\\|", -1);
        if (parts.length < 6) return Optional.empty();
        return Optional.of(new ContainerDetails(parts[0], parts[1], parts[2], parseInt(parts[3]),
                parseInt(parts[4]), parts[5]));
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String containerName(String serverName) {
        if (serverName == null || !BundleExportService.SERVER_NAME_PATTERN.matcher(serverName).matches()) {
            throw new IllegalArgumentException("Invalid server name: " + serverName);
        }
        return CONTAINER_PREFIX + serverName;
    }

    private static void requireOk(ProcResult result, String message) {
        if (!result.ok()) {
            throw new IllegalStateException(message + " (exit " + result.exitCode() + "): "
                    + (result.stderr().isBlank() ? result.stdout() : result.stderr()).strip());
        }
    }

    private static ProcResult runProcess(List<String> command, Map<String, String> extraEnv,
            Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(extraEnv);
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> readFully(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> readFully(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }
            return new ProcResult(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to run: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running: " + String.join(" ", command), e);
        }
    }

    private static String readFully(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read process output", e);
        }
    }

}
