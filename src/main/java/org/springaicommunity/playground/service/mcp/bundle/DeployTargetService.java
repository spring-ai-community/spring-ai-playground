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
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardTarget;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget.Origin;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerStatus;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class DeployTargetService {

    private static final Logger logger = LoggerFactory.getLogger(DeployTargetService.class);
    public static final String CONFIGURED_TARGETS_PROPERTY = "spring.ai.playground.shipyard.targets";
    static final String TARGETS_FILE = "shipyard-targets.json";
    static final String DEPLOYMENTS_FILE = "shipyard-deployments.json";
    public static final List<String> RUN_SETTINGS = List.of(DockerCliService.AUTH_TOKEN_ENV,
            BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV);
    private static final Set<String> NETWORK_SCHEMES = Set.of("tcp://", "ssh://");
    private static final Duration CONTEXT_CACHE_TTL = Duration.ofSeconds(15);
    private static final TypeReference<List<AddedTarget>> ADDED_TARGETS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> DEPLOYMENTS_TYPE = new TypeReference<>() {};

    public record AddedTarget(String name, String dockerHost, String publicHost) {}

    private final DockerCliService dockerCliService;
    private final List<ShipyardTarget> configuredTargets;
    private final List<ShipyardServer> configuredServers;
    private final Path targetsFile;
    private final Path deploymentsFile;
    private final Environment environment;
    private volatile List<DeployTarget> cachedContexts;
    private volatile long contextsReadAt;

    public DeployTargetService(DockerCliService dockerCliService, SpringAiPlaygroundOptions playgroundOptions,
            Path springAiPlaygroundHomeDir, Environment environment) {
        this.dockerCliService = dockerCliService;
        this.environment = environment;
        this.configuredTargets = playgroundOptions.shipyard().targets();
        this.configuredServers = playgroundOptions.shipyard().servers();
        Path mcpDir = springAiPlaygroundHomeDir.resolve("mcp");
        this.targetsFile = mcpDir.resolve(TARGETS_FILE);
        this.deploymentsFile = mcpDir.resolve(DEPLOYMENTS_FILE);
    }

    public List<DeployTarget> targets() {
        Map<String, DeployTarget> targets = new LinkedHashMap<>();
        targets.put(DeployTarget.LOCAL_NAME, DeployTarget.local());
        List<DeployTarget> contexts = dockerContextTargets();
        for (ShipyardTarget configured : this.configuredTargets) {
            try {
                DeployTarget target = DeployTarget.docker(configured.name(), configured.dockerContext(),
                        configured.dockerHost(), configured.publicHost(), Origin.CONFIGURATION);
                targets.putIfAbsent(target.name(), withContextEndpoint(target, contexts));
            } catch (IllegalArgumentException e) {
                logger.error("Ignoring {} entry: {}", CONFIGURED_TARGETS_PROPERTY, e.getMessage());
            }
        }
        for (AddedTarget added : readAddedTargets()) {
            try {
                targets.putIfAbsent(added.name(), DeployTarget.docker(added.name(), null, added.dockerHost(),
                        added.publicHost(), Origin.ADDED));
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring target in {}: {}", this.targetsFile, e.getMessage());
            }
        }
        contexts.forEach(context -> targets.putIfAbsent(context.name(), context));
        return List.copyOf(targets.values());
    }

    public Optional<DeployTarget> find(String name) {
        if (name == null) return Optional.empty();
        return targets().stream().filter(target -> target.name().equals(name)).findFirst();
    }

    public synchronized DeployTarget addTarget(String name, String dockerHost, String publicHost) {
        DeployTarget target = DeployTarget.docker(name, null, dockerHost, publicHost, Origin.ADDED);
        Optional<DeployTarget> existing = find(target.name());
        if (existing.isPresent() && existing.get().origin() != Origin.DOCKER_CONTEXT) {
            throw new IllegalArgumentException("A target named '" + target.name() + "' already exists");
        }
        List<AddedTarget> added = new ArrayList<>(readAddedTargets());
        added.add(new AddedTarget(target.name(), target.dockerHost(), target.publicHost()));
        write(this.targetsFile, added);
        logger.info("Added deploy target '{}' at {}", target.name(), target.dockerHost());
        return target;
    }

    public synchronized void removeTarget(String name) {
        DeployTarget target = find(name).orElseThrow(() -> new IllegalArgumentException("No target '" + name + "'"));
        if (target.origin() != Origin.ADDED) {
            throw new IllegalArgumentException("'" + name + "' comes from " + target.origin().label()
                    + " and cannot be removed here");
        }
        Map<String, String> deployments = readDeployments();
        List<String> users = deployments.entrySet().stream()
                .filter(deployment -> name.equals(deployment.getValue())).map(Map.Entry::getKey).toList();
        if (!users.isEmpty()) {
            throw new IllegalArgumentException("'" + name + "' is where " + users
                    + " last ran; run them on another target or delete them first");
        }
        write(this.targetsFile, readAddedTargets().stream().filter(added -> !name.equals(added.name())).toList());
        logger.info("Removed deploy target '{}'", name);
    }

    public Optional<String> configuredTargetName(String serverName) {
        return this.configuredServers.stream()
                .filter(server -> serverName.equals(server.name()))
                .map(ShipyardServer::target)
                .filter(target -> target != null && !target.isBlank())
                .findFirst();
    }

    public DeployTarget targetOf(String serverName) {
        Optional<String> configured = configuredTargetName(serverName);
        String name = configured.orElseGet(() -> readDeployments().get(serverName));
        if (name == null) return DeployTarget.local();
        return find(name).orElseGet(() -> {
            logger.warn("Target '{}' of server '{}' no longer exists; using '{}'", name, serverName,
                    DeployTarget.LOCAL_NAME);
            return DeployTarget.local();
        });
    }

    public Optional<String> configuredRunSetting(String name) {
        return Optional.ofNullable(this.environment.getProperty(name)).filter(value -> !value.isBlank());
    }

    public DockerAvailability check(DeployTarget target) {
        return this.dockerCliService.probe(target);
    }

    public void run(BundleEntry entry, DeployTarget target, Map<String, String> env) {
        Optional<String> configured = configuredTargetName(entry.name());
        if (configured.isPresent() && !configured.get().equals(target.name())) {
            throw new IllegalArgumentException("'" + entry.name() + "' runs on target '" + configured.get()
                    + "' set in configuration");
        }
        Map<String, String> effective = new LinkedHashMap<>(env);
        RUN_SETTINGS.forEach(name -> configuredRunSetting(name).ifPresent(value -> effective.put(name, value)));
        if (target.isRemote() && Optional.ofNullable(effective.get(DockerCliService.AUTH_TOKEN_ENV))
                .filter(token -> !token.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("A server on a remote target must require a bearer token");
        }
        this.dockerCliService.up(target, entry.dir(), entry.name(), effective);
        remember(entry.name(), target.name());
    }

    public void stop(BundleEntry entry) {
        this.dockerCliService.down(targetOf(entry.name()), entry.dir(), entry.name());
    }

    public ContainerStatus status(String serverName) {
        return this.dockerCliService.status(targetOf(serverName), serverName, authToken(serverName));
    }

    public void awaitSettled(String serverName, Consumer<ContainerStatus> onSettled) {
        this.dockerCliService.awaitSettled(targetOf(serverName), serverName, authToken(serverName), onSettled);
    }

    public String logsTail(String serverName, int lines) {
        return this.dockerCliService.logsTail(targetOf(serverName), serverName, lines);
    }

    public Optional<String> authToken(String serverName) {
        return this.dockerCliService.lastAuthToken(serverName)
                .or(() -> configuredRunSetting(DockerCliService.AUTH_TOKEN_ENV));
    }

    public synchronized void forget(String serverName) {
        Map<String, String> deployments = readDeployments();
        if (deployments.remove(serverName) != null) {
            write(this.deploymentsFile, deployments);
        }
    }

    private synchronized void remember(String serverName, String targetName) {
        Map<String, String> deployments = readDeployments();
        if (targetName.equals(deployments.put(serverName, targetName))) return;
        write(this.deploymentsFile, deployments);
    }

    private List<DeployTarget> dockerContextTargets() {
        long now = System.nanoTime();
        List<DeployTarget> cached = this.cachedContexts;
        if (cached != null && now - this.contextsReadAt < CONTEXT_CACHE_TTL.toNanos()) return cached;
        List<DeployTarget> contexts = new ArrayList<>();
        for (DockerContext context : this.dockerCliService.contexts()) {
            String name = context.name();
            if (!DeployTarget.NAME_PATTERN.matcher(name).matches() || DeployTarget.LOCAL_NAME.equals(name)
                    || NETWORK_SCHEMES.stream().noneMatch(context.endpoint()::startsWith)) {
                continue;
            }
            contexts.add(DeployTarget.docker(name, name, context.endpoint(), null, Origin.DOCKER_CONTEXT));
        }
        this.cachedContexts = List.copyOf(contexts);
        this.contextsReadAt = now;
        return this.cachedContexts;
    }

    private static DeployTarget withContextEndpoint(DeployTarget target, List<DeployTarget> contexts) {
        if (target.dockerContext() == null) return target;
        return contexts.stream().filter(context -> target.dockerContext().equals(context.dockerContext()))
                .findFirst()
                .map(context -> new DeployTarget(target.name(), target.kind(), target.dockerContext(),
                        context.dockerHost(), target.publicHost(), target.origin()))
                .orElse(target);
    }

    private List<AddedTarget> readAddedTargets() {
        return read(this.targetsFile, ADDED_TARGETS_TYPE, List.of());
    }

    private Map<String, String> readDeployments() {
        return new LinkedHashMap<>(read(this.deploymentsFile, DEPLOYMENTS_TYPE, Map.of()));
    }

    private static <T> T read(Path file, TypeReference<T> type, T fallback) {
        if (!Files.isRegularFile(file)) return fallback;
        try {
            return PersistenceServiceInterface.OBJECT_MAPPER.readValue(Files.readAllBytes(file), type);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read {}: {}", file, e.getMessage());
            return fallback;
        }
    }

    private static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, PersistenceServiceInterface.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

}
