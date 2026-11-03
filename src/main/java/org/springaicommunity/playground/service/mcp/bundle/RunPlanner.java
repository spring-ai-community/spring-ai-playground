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

import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class RunPlanner {

    public static final String CONTAINER_HOST = "host.docker.internal";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]",
            CONTAINER_HOST);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Kind { OK, WARN, NOTE }

    public record Line(Kind kind, String text) {}

    public record RunPlan(DeployTarget target, boolean targetConfigured, DockerAvailability docker,
            List<Line> targetLines, List<Line> modelLines, Map<String, String> loopbackUpstreams,
            Optional<String> configuredToken, boolean tokenRequired, Optional<String> configuredOllamaEndpoint,
            String image, String endpoint, String commandPreview) {

        public boolean runnable(String authToken) {
            return docker.daemonUp() && !(tokenRequired && (authToken == null || authToken.isBlank()));
        }
    }

    public record RunRequest(String serverName, String targetName, Map<String, String> env, String ollamaEndpoint,
            String authToken) {

        public RunRequest {
            env = env == null ? Map.of() : Map.copyOf(env);
        }
    }

    public record RunResult(DeployTarget target, Optional<String> authToken) {}

    private final DeployTargetService deployTargetService;
    private final OllamaModelProbe ollamaModelProbe;
    private final BundleRegistryService registryService;

    public RunPlanner(DeployTargetService deployTargetService, OllamaModelProbe ollamaModelProbe,
            BundleRegistryService registryService) {
        this.deployTargetService = deployTargetService;
        this.ollamaModelProbe = ollamaModelProbe;
        this.registryService = registryService;
    }

    public List<DeployTarget> targets() {
        return this.deployTargetService.targets();
    }

    public DeployTarget currentTarget(String serverName) {
        return this.deployTargetService.targetOf(serverName);
    }

    public boolean targetConfigured(String serverName) {
        return this.deployTargetService.configuredTargetName(serverName).isPresent();
    }

    public RunPlan plan(BundleEntry entry, DeployTarget target, String baseImage, String ollamaEndpoint) {
        BundleManifest manifest = entry.manifest();
        DockerAvailability docker = this.deployTargetService.check(target);
        List<Line> targetLines = new ArrayList<>();
        if (!docker.cliFound()) {
            targetLines.add(new Line(Kind.WARN, "Docker CLI not found on this machine"));
        } else if (!docker.daemonUp()) {
            targetLines.add(new Line(Kind.WARN, "Docker on " + target.where() + " is not reachable"));
        } else {
            targetLines.add(new Line(Kind.OK, "Docker " + Objects.toString(docker.version(), "") + " reachable on "
                    + target.where()));
        }
        if (!target.isLocal()) {
            targetLines.add(new Line(Kind.NOTE, "The bundle folder is sent as build context and the image is built on "
                    + "that engine; it must be able to pull " + baseImage));
        }
        Map<String, String> loopback = new LinkedHashMap<>();
        ProxiedToolsContribution.upstreamUrls(entry.dir(), manifest).forEach((server, url) -> {
            if (loopbackUrl(url)) loopback.put(server, url);
        });
        if (target.isRemote()) {
            targetLines.add(new Line(Kind.NOTE, CONTAINER_HOST + " inside the container means " + target.endpointHost()
                    + ", not this machine"));
            loopback.forEach((server, url) -> targetLines.add(new Line(Kind.WARN, "Proxied upstream '" + server
                    + "' uses " + url + ", which on this target points at " + target.endpointHost())));
        } else {
            loopback.forEach((server, url) -> targetLines.add(new Line(Kind.NOTE, "Proxied upstream '" + server
                    + "' runs on this machine and is reached from the container at " + url)));
        }
        String command = "docker " + String.join(" ", Stream.concat(target.cliArguments().stream(),
                Stream.of("compose", "-f", entry.dir().resolve("compose.yaml").toString(), "up", "-d", "--build"))
                .toList());
        return new RunPlan(target, targetConfigured(entry.name()), docker,
                List.copyOf(targetLines), modelLines(manifest, target, ollamaEndpoint), Map.copyOf(loopback),
                this.deployTargetService.configuredRunSetting(DockerCliService.AUTH_TOKEN_ENV), target.isRemote(),
                configuredOllamaEndpoint(), BundleArtifactWriter.localImage(manifest),
                target.endpointHost() + ":" + entry.effectiveHttpPort(), command);
    }

    public List<Line> modelLines(BundleManifest manifest, DeployTarget target, String ollamaEndpoint) {
        BundleManifest.Models models = manifest.requires().models();
        List<BundleManifest.ModelRef> required = Stream.of(models.chat(), models.embedding())
                .filter(Objects::nonNull).toList();
        if (required.isEmpty()) return List.of();
        List<Line> lines = new ArrayList<>();
        OllamaModelProbe.Result ollama = null;
        String endpoint = ollamaEndpoint == null || ollamaEndpoint.isBlank() ? defaultOllamaEndpoint() : ollamaEndpoint;
        String hostUrl = target.containerHostUrl(endpoint);
        if (BundleArtifactWriter.usesOllama(models)) {
            ollama = this.ollamaModelProbe.probe(hostUrl);
            if (!ollama.reachable()) {
                lines.add(new Line(Kind.WARN, target.isRemote()
                        ? "Could not check Ollama at " + hostUrl + " from this machine; the running server "
                                + "reports it under Unavailable right now"
                        : "Ollama is not reachable at " + hostUrl + "; tools that need it stay unavailable until "
                                + "it is"));
            }
        }
        for (BundleManifest.ModelRef model : required) {
            if (!"ollama".equalsIgnoreCase(model.provider())) {
                lines.add(new Line(Kind.OK, model.model() + " (" + model.provider() + ", needs its API key at run "
                        + "time)"));
            } else if (ollama != null && ollama.reachable()) {
                boolean pulled = ollama.has(model.model());
                lines.add(new Line(pulled ? Kind.OK : Kind.WARN, pulled ? model.model() + " is pulled"
                        : model.model() + " is not pulled; run: ollama pull " + model.model()));
            }
        }
        return List.copyOf(lines);
    }

    public Optional<String> configuredOllamaEndpoint() {
        return this.deployTargetService.configuredRunSetting(BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV);
    }

    public String defaultOllamaEndpoint() {
        return configuredOllamaEndpoint().orElse(BundleArtifactWriter.CONTAINER_OLLAMA_BASE_URL);
    }

    public RunResult run(RunRequest request) {
        BundleEntry entry = this.registryService.find(request.serverName())
                .orElseThrow(() -> new IllegalArgumentException("No server named '" + request.serverName() + "'"));
        DeployTarget target = request.targetName() == null || request.targetName().isBlank()
                ? currentTarget(entry.name())
                : this.deployTargetService.find(request.targetName()).orElseThrow(
                        () -> new IllegalArgumentException("No target named '" + request.targetName() + "'"));
        if (!this.deployTargetService.check(target).daemonUp()) {
            throw new IllegalStateException("Docker on " + target.where() + " is not reachable");
        }
        String token = request.authToken();
        if ((token == null || token.isBlank()) && target.isRemote()
                && this.deployTargetService.configuredRunSetting(DockerCliService.AUTH_TOKEN_ENV).isEmpty()) {
            token = newToken();
        }
        Map<String, String> env = environment(entry.manifest(), request.env(), request.ollamaEndpoint(), token);
        this.deployTargetService.run(entry, target, env);
        return new RunResult(target, this.deployTargetService.authToken(entry.name()));
    }

    static Map<String, String> environment(BundleManifest manifest, Map<String, String> values,
            String ollamaEndpoint, String authToken) {
        Map<String, String> env = new LinkedHashMap<>(values);
        if (BundleArtifactWriter.usesOllama(manifest.requires().models()) && ollamaEndpoint != null
                && !ollamaEndpoint.isBlank()) {
            env.put(BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV, ollamaEndpoint);
        }
        if (authToken != null && !authToken.isBlank()) {
            env.put(DockerCliService.AUTH_TOKEN_ENV, authToken);
        }
        return env;
    }

    public static boolean loopbackUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT)) || host.startsWith("127."));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String reachableFromContainer(String url) {
        if (!loopbackUrl(url)) return url;
        try {
            URI uri = URI.create(url);
            return new URI(uri.getScheme(), uri.getUserInfo(), CONTAINER_HOST, uri.getPort(), uri.getPath(),
                    uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return url;
        }
    }

    public static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
