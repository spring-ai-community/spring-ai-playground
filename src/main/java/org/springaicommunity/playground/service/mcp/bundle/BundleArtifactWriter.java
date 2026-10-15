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

import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class BundleArtifactWriter {

    public static final String REGISTRY_NAMESPACE_PLACEHOLDER = "io.github.OWNER";
    public static final String REGISTRY_IMAGE_PLACEHOLDER = "ghcr.io/OWNER";
    public static final String LOCAL_IMAGE_REPOSITORY = "saip-mcp";
    public static final String OLLAMA_BASE_URL_OVERRIDE_ENV = "SAIP_OLLAMA_BASE_URL";
    public static final String CONTAINER_OLLAMA_BASE_URL = "http://host.docker.internal:11434";
    public static final String HOST_GATEWAY = "host.docker.internal:host-gateway";
    static final String BUNDLE_ACTUATOR_ENDPOINTS = "health,info,metrics,prometheus," + BundleStatusEndpoint.ID;
    static final String UI_ENABLED_ENV = "SPRING_AI_PLAYGROUND_UI_ENABLED";
    private static final String DOCKERIGNORE = """
            *
            !home
            !home/**
            """;

    private final ConnectionSnippetService connectionSnippetService;

    public BundleArtifactWriter(ConnectionSnippetService connectionSnippetService) {
        this.connectionSnippetService = connectionSnippetService;
    }

    public List<Path> writeArtifacts(Path bundleDir, BundleManifest manifest,
            ShipRequest request, String image) throws IOException {
        Path dockerfile = bundleDir.resolve("Dockerfile");
        Files.writeString(dockerfile, dockerfile(image, manifest));
        Path compose = bundleDir.resolve("compose.yaml");
        Files.writeString(compose, compose(manifest, request));
        Path readme = bundleDir.resolve("README.md");
        Files.writeString(readme, readme(manifest, request, image));
        Path serverJson = bundleDir.resolve("server.json");
        Files.writeString(serverJson, serverJson(manifest, request));
        Path dockerignore = bundleDir.resolve(".dockerignore");
        Files.writeString(dockerignore, DOCKERIGNORE);
        return List.of(dockerfile, compose, readme, serverJson, dockerignore);
    }

    private String serverJson(BundleManifest manifest, ShipRequest request) {
        List<Map<String, Object>> environmentVariables = new ArrayList<>();
        environmentVariables.add(fixedEnv("SPRING_PROFILES_INCLUDE",
                "Runs the bundled app as a stdio MCP server", "mcp-stdio"));
        for (BundleManifest.EnvVar envVar : manifest.requires().env()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", envVar.name());
            entry.put("description", envVar.purpose());
            entry.put("isRequired", true);
            entry.put("isSecret", envVar.secret());
            environmentVariables.add(entry);
        }

        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("type", "stdio");

        Map<String, Object> ociPackage = new LinkedHashMap<>();
        ociPackage.put("registryType", "oci");
        ociPackage.put("identifier", REGISTRY_IMAGE_PLACEHOLDER + "/"
                + manifest.server().name().toLowerCase(Locale.ROOT));
        ociPackage.put("version", manifest.server().version());
        ociPackage.put("runtimeHint", "docker");
        if (usesOllama(manifest.requires().models())) {
            Map<String, Object> addHost = new LinkedHashMap<>();
            addHost.put("type", "named");
            addHost.put("name", "--add-host");
            addHost.put("value", HOST_GATEWAY);
            ociPackage.put("runtimeArguments", List.of(addHost));
        }
        ociPackage.put("transport", transport);
        ociPackage.put("environmentVariables", environmentVariables);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://static.modelcontextprotocol.io/schemas/2025-07-09/server.json");
        root.put("name", REGISTRY_NAMESPACE_PLACEHOLDER + "/" + manifest.server().name());
        root.put("description", manifest.server().description() == null
                || manifest.server().description().isBlank()
                ? "MCP tool server exported from Spring AI Playground"
                : manifest.server().description());
        root.put("version", manifest.server().version());
        root.put("packages", List.of(ociPackage));
        return PersistenceServiceInterface.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root);
    }

    private static Map<String, Object> fixedEnv(String name, String description, String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("description", description);
        entry.put("isRequired", true);
        entry.put("isSecret", false);
        entry.put("value", value);
        return entry;
    }

    static List<String> modelEnvLines(BundleManifest.Models models) {
        List<String> lines = new ArrayList<>();
        if (models == null || models.modelFree()) {
            lines.add("SPRING_AI_MODEL_CHAT=none");
            lines.add("SPRING_AI_MODEL_EMBEDDING=none");
            return lines;
        }
        BundleManifest.ModelRef chat = models.chat();
        if (chat == null) {
            lines.add("SPRING_AI_MODEL_CHAT=none");
        } else {
            lines.add("SPRING_AI_MODEL_CHAT=" + chat.provider());
            providerModelEnv(chat, "CHAT").ifPresent(lines::add);
        }
        BundleManifest.ModelRef embedding = models.embedding();
        if (embedding == null) {
            lines.add("SPRING_AI_MODEL_EMBEDDING=none");
        } else {
            lines.add("SPRING_AI_MODEL_EMBEDDING=" + embedding.provider());
            providerModelEnv(embedding, "EMBEDDING").ifPresent(lines::add);
        }
        return lines;
    }

    private static Optional<String> providerModelEnv(BundleManifest.ModelRef model, String kind) {
        String provider = model.provider().toLowerCase(Locale.ROOT);
        if (!provider.equals("ollama") && !provider.equals("openai")) return Optional.empty();
        return Optional.of("SPRING_AI_" + provider.toUpperCase(Locale.ROOT) + "_" + kind + "_OPTIONS_MODEL="
                + model.model());
    }

    public static String registryImage(BundleManifest manifest) {
        return REGISTRY_IMAGE_PLACEHOLDER + "/" + manifest.server().name().toLowerCase(Locale.ROOT) + ":"
                + manifest.server().version();
    }

    public static String localImage(BundleManifest manifest) {
        return LOCAL_IMAGE_REPOSITORY + "/" + manifest.server().name().toLowerCase(Locale.ROOT) + ":"
                + manifest.server().version();
    }

    public static boolean usesOllama(BundleManifest.Models models) {
        return usesProvider(models, "ollama");
    }

    public static boolean usesProvider(BundleManifest.Models models, String provider) {
        return models != null && (isProvider(models.chat(), provider) || isProvider(models.embedding(), provider));
    }

    private static boolean isProvider(BundleManifest.ModelRef model, String provider) {
        return model != null && provider.equalsIgnoreCase(model.provider());
    }

    public String dockerfile(String baseImage, BundleManifest manifest) {
        StringBuilder dockerfile = new StringBuilder();
        dockerfile.append("FROM ").append(baseImage).append('\n');
        dockerfile.append("COPY home ").append(BundleHome.IMAGE_SOURCE_DIR).append('\n');
        dockerfile.append("ENV SPRING_AI_PLAYGROUND_BUNDLE_SOURCE=").append(BundleHome.IMAGE_SOURCE_DIR)
                .append('\n');
        dockerfile.append("ENV SPRING_AI_MCP_SERVER_PROTOCOL=STREAMABLE\n");
        dockerfile.append("ENV SPRING_AI_PLAYGROUND_BUILTINMCPSERVER_NAME=").append(manifest.server().name())
                .append('\n');
        dockerfile.append("ENV SPRING_AI_MCP_SERVER_VERSION=").append(manifest.server().version()).append('\n');
        dockerfile.append("ENV MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=").append(BUNDLE_ACTUATOR_ENDPOINTS)
                .append('\n');
        for (String line : modelEnvLines(manifest.requires().models())) {
            dockerfile.append("ENV ").append(line).append('\n');
        }
        if (usesOllama(manifest.requires().models())) {
            dockerfile.append("ENV SPRING_AI_OLLAMA_BASE_URL=").append(CONTAINER_OLLAMA_BASE_URL).append('\n');
            dockerfile.append("ENV SPRING_AI_OLLAMA_INIT_PULLMODELSTRATEGY=never\n");
        }
        return dockerfile.toString();
    }

    private String compose(BundleManifest manifest, ShipRequest request) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("services:\n");
        yaml.append("  ").append(manifest.server().name()).append(":\n");
        yaml.append("    build: .\n");
        yaml.append("    image: ").append(localImage(manifest)).append('\n');
        yaml.append("    container_name: ").append(DockerCliService.containerName(manifest.server().name()))
                .append('\n');
        yaml.append("    ports:\n      - \"").append(request.effectiveHttpPort()).append(":8282\"\n");
        yaml.append("    extra_hosts:\n      - \"").append(HOST_GATEWAY).append("\"\n");
        yaml.append("    environment:\n");
        for (BundleManifest.EnvVar envVar : manifest.requires().env()) {
            yaml.append("      ").append(envVar.name()).append(": \"${").append(envVar.name())
                    .append("}\"\n");
        }
        if (usesOllama(manifest.requires().models())) {
            yaml.append("      SPRING_AI_OLLAMA_BASE_URL: \"${").append(OLLAMA_BASE_URL_OVERRIDE_ENV).append(":-")
                    .append(CONTAINER_OLLAMA_BASE_URL).append("}\"\n");
        }
        yaml.append("      SPRING_AI_PLAYGROUND_MCPSERVER_AUTHTOKEN: \"${SAIP_MCP_AUTH_TOKEN:-}\"\n");
        return yaml.toString();
    }

    private String readme(BundleManifest manifest, ShipRequest request,
            String baseImage) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(manifest.server().name()).append(" - MCP server bundle\n\n");
        if (manifest.server().description() != null && !manifest.server().description().isBlank()) {
            md.append(manifest.server().description()).append("\n\n");
        }
        md.append("Version ").append(manifest.server().version()).append(", exported by ")
                .append(manifest.provenance().exportedBy()).append(". Requires runtime image >= ")
                .append(manifest.requires().minRuntimeVersion()).append(".\n\n");

        md.append("## Exposed tools\n\n| Tool | Risk | HITL |\n|---|---|---|\n");
        for (BundleManifest.ToolRisk toolRisk : manifest.risk().summary()) {
            md.append("| ").append(toolRisk.tool()).append(" | ").append(toolRisk.level()).append(" | ")
                    .append(toolRisk.hitl() ? "required" : "-").append(" |\n");
        }

        if (!manifest.contents().composedMembers().isEmpty()) {
            md.append("\n## Proxied tools\n\nThese tools are served by upstream MCP servers and re-published ")
                    .append("under the names below. Each upstream must be reachable from the container when it ")
                    .append("starts; a proxied tool whose upstream is offline at startup stays unavailable until ")
                    .append("the container restarts.\n\n")
                    .append("| Tool | Upstream | Upstream tool | HITL |\n|---|---|---|---|\n");
            for (McpComposition.Member member : manifest.contents().composedMembers()) {
                md.append("| ").append(member.exposedAlias()).append(" | ").append(member.serverId()).append(" | ")
                        .append(member.toolName()).append(" | ").append(member.hitl() ? "required" : "-")
                        .append(" |\n");
            }
            md.append("\nUpstream connections ship as `home/mcp/save/<TRANSPORT>-<name>.json`. Only HTTP ")
                    .append("upstreams without OAuth can ship; secrets in their URL or headers are `${ENV_VAR}` ")
                    .append("references listed below.\n");
        }

        md.append("\n## Required environment variables\n\n");
        if (manifest.requires().env().isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Name | Purpose | Secret |\n|---|---|---|\n");
            for (BundleManifest.EnvVar envVar : manifest.requires().env()) {
                md.append("| ").append(envVar.name()).append(" | ").append(envVar.purpose()).append(" | ")
                        .append(envVar.secret() ? "yes" : "no").append(" |\n");
            }
        }

        BundleManifest.Models models = manifest.requires().models();
        md.append("\n## Model requirement\n\n");
        if (models.modelFree()) {
            md.append("None - this server boots and serves tools without any model provider.\n");
        } else {
            if (models.chat() != null) {
                md.append("- Chat model: `").append(models.chat().model()).append("` (provider `")
                        .append(models.chat().provider()).append("`)");
                if ("ollama".equalsIgnoreCase(models.chat().provider())) {
                    md.append(" - install with `ollama pull ").append(models.chat().model()).append('`');
                }
                md.append('\n');
            }
            if (models.embedding() != null) {
                md.append("- Embedding model: `").append(models.embedding().model()).append("` (provider `")
                        .append(models.embedding().provider()).append("`)\n");
            }
            if (usesOllama(models)) {
                md.append("\nThe container reaches Ollama on the Docker host at `").append(CONTAINER_OLLAMA_BASE_URL)
                        .append("`. Set `").append(OLLAMA_BASE_URL_OVERRIDE_ENV)
                        .append("` before `docker compose up` to use another Ollama endpoint.\n");
            }
        }

        md.append("\n## Image\n\nEverything runs from one image built from this folder: the bundle is copied ")
                .append("into `").append(BundleHome.IMAGE_SOURCE_DIR).append("` on top of `").append(baseImage)
                .append("`, and every start serves a fresh copy of it, so runs never change the bundle.\n\n")
                .append("```bash\ndocker compose build\n```\n\nbuilds and tags `").append(localImage(manifest))
                .append("`.\n");
        if (request.transport() != ShipRequest.Transport.STDIO) {
            md.append("\n## Run (Streamable HTTP)\n\n```bash\ndocker compose up -d --build\n```\n");
            md.append("\nOn a remote Docker host, run the same command against it, for example ")
                    .append("`docker --context <name> compose up -d --build` or ")
                    .append("`docker --host ssh://user@host compose up -d --build`. The bundle folder is sent as ")
                    .append("build context and the image is built on that engine; `host.docker.internal` then means ")
                    .append("that host.\n");
        }

        md.append("\n## Web UI\n\nThe image is the full application, so the published port also serves the app's ")
                .append("screens: open `http://<host>:<port>/` to inspect a running server in a browser. The bearer ")
                .append("token protects `/mcp` and the bundle status, not the screens. Set `").append(UI_ENABLED_ENV)
                .append("=false` in the container's environment to run it headless, with every path other than ")
                .append("`/mcp` and the actuator endpoints answering `404`.\n");

        if (!manifest.contents().ragPipelines().isEmpty()) {
            md.append("\n## Knowledge\n\nThis server searches a snapshot of these knowledge sources. Each one is ")
                    .append("a retrieval-only tool that takes `query` and an optional `top_k` (1-")
                    .append(KnowledgeContribution.MAX_TOP_K).append(") and returns excerpts with their source ")
                    .append("document and similarity score. Queries are embedded with `")
                    .append(manifest.requires().models().embedding().model()).append("` (")
                    .append(manifest.requires().models().embedding().provider())
                    .append("), the model that indexed them, so that model must be reachable at run time.\n\n");
            for (BundleManifest.RagPipelineRef ref : manifest.contents().ragPipelines()) {
                md.append("- `").append(ref.alias()).append("`: ").append(ref.documents().size())
                        .append(" document(s), ").append(ref.documents().stream()
                                .mapToInt(BundleManifest.DocumentRef::chunks).sum()).append(" chunks");
                if (ref.topK() != null) md.append(", top_k ").append(ref.topK());
                md.append('\n');
            }
            md.append("\nThe snapshot lives under `home/vectorstore/`; re-ship the server to pick up document ")
                    .append("changes.\n");
        }

        md.append("\n## Connect\n\n");
        md.append("Endpoint: `http://localhost:").append(request.effectiveHttpPort())
                .append("/mcp` (Streamable HTTP; SSE is not supported)\n\n");
        md.append("Set `SAIP_MCP_AUTH_TOKEN` in the compose environment (wired to ")
                .append("`SPRING_AI_PLAYGROUND_MCPSERVER_AUTHTOKEN` in the container) to require a bearer ")
                .append("token; without it the endpoint is unauthenticated, so do not expose it beyond ")
                .append("localhost or a trusted network.\n\n");
        for (ConnectionSnippetService.Snippet snippet : this.connectionSnippetService.snippetsFor(manifest,
                DeployTarget.local(), request.effectiveHttpPort(), Optional.empty())) {
            md.append("### ").append(snippet.label()).append("\n\n```")
                    .append(snippet.kind() == ConnectionSnippetService.ContentKind.JSON ? "json" : "bash")
                    .append('\n').append(snippet.content()).append("\n```\n\n");
        }
        md.append("For remote deployments replace `localhost` with the deployment host.\n");

        md.append("\n## Publish to the MCP Registry\n\n");
        md.append("The bundle ships a starter `server.json` for the official MCP Registry ")
                .append("(registry.modelcontextprotocol.io). To publish:\n\n");
        String registryImage = registryImage(manifest);
        md.append("1. Push the same image you ran locally under your registry name:\n\n```bash\n")
                .append("docker compose build\ndocker tag ").append(localImage(manifest)).append(' ')
                .append(registryImage).append("\ndocker push ").append(registryImage).append("\n```\n\n");
        md.append("2. In `server.json`, replace `OWNER` with your GitHub username in both `name` ")
                .append("(`io.github.OWNER/...`) and the package `identifier`.\n");
        md.append("3. Publish with the registry CLI:\n\n```bash\nmcp-publisher login github\n")
                .append("mcp-publisher publish\n```\n");
        return md.toString();
    }

}
