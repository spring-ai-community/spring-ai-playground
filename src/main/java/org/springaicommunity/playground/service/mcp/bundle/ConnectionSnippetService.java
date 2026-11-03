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
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ConnectionSnippetService {

    public enum ContentKind { COMMAND, JSON }

    public record Snippet(String label, String content, ContentKind kind) {}

    public record DeepLink(String client, String url) {}

    public static String mcpUrl(DeployTarget target, int hostPort) {
        return target.endpointUrl(hostPort) + "/mcp";
    }

    public String publishSteps(BundleRegistryService.BundleEntry entry, DeployTarget target) {
        String registryImage = BundleArtifactWriter.registryImage(entry.manifest());
        String docker = dockerCommand(target);
        return docker + " compose -f " + entry.dir().resolve("compose.yaml") + " build\n"
                + docker + " tag " + BundleArtifactWriter.localImage(entry.manifest()) + " " + registryImage + "\n"
                + docker + " push " + registryImage + "\n"
                + "mcp-publisher login github\n"
                + "mcp-publisher publish";
    }

    private static String dockerCommand(DeployTarget target) {
        return String.join(" ", Stream.concat(Stream.of("docker"), target.cliArguments().stream()).toList());
    }

    public List<DeepLink> deepLinksFor(BundleManifest manifest, DeployTarget target, int hostPort,
            Optional<String> authToken) {
        String name = manifest.server().name();
        Map<String, Object> config = clientConfig(mcpUrl(target, hostPort), authToken);
        String cursor = "cursor://anysphere.cursor-deeplink/mcp/install?name=" + urlEncode(name)
                + "&config=" + Base64.getEncoder().encodeToString(toJson(config).getBytes(StandardCharsets.UTF_8));
        String vsCode = "vscode:mcp/install?" + uriComponent(toJson(namedConfig(name, config)));
        return List.of(new DeepLink("Cursor", cursor), new DeepLink("VS Code", vsCode));
    }

    private static Map<String, Object> clientConfig(String url, Optional<String> authToken) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "http");
        config.put("url", url);
        authToken.ifPresent(token ->
                config.put("headers", Map.of("Authorization", "Bearer " + token)));
        return config;
    }

    private static Map<String, Object> namedConfig(String name, Map<String, Object> config) {
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("name", name);
        named.putAll(config);
        return named;
    }

    private static String toJson(Map<String, Object> value) {
        return PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(value);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String uriComponent(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    public List<Snippet> snippetsFor(BundleManifest manifest, DeployTarget target, int hostPort,
            Optional<String> authToken) {
        String name = manifest.server().name();
        String url = mcpUrl(target, hostPort);
        List<Snippet> snippets = new ArrayList<>();

        StringBuilder claudeCode = new StringBuilder("claude mcp add --transport http " + name + " " + url);
        authToken.ifPresent(token -> claudeCode.append(" --header \"Authorization: Bearer ").append(token)
                .append("\""));
        snippets.add(new Snippet("Claude Code", claudeCode.toString(), ContentKind.COMMAND));

        snippets.add(new Snippet("Claude Desktop (stdio)",
                claudeDesktopStdio(manifest, target), ContentKind.JSON));

        String headers = authToken
                .map(token -> ",\n    \"headers\": { \"Authorization\": \"Bearer " + token + "\" }")
                .orElse("");
        snippets.add(new Snippet("Generic JSON", """
                {
                  "%s": {
                    "type": "streamable-http",
                    "url": "%s"%s
                  }
                }""".formatted(name, url, headers), ContentKind.JSON));

        String authHeaderArg = authToken
                .map(token -> " -H \"Authorization: Bearer " + token + "\"")
                .orElse("");
        snippets.add(new Snippet("curl handshake",
                "curl -s -X POST" + authHeaderArg
                        + " -H \"Content-Type: application/json\" -H \"Accept: application/json, text/event-stream\""
                        + " -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"curl\",\"version\":\"0\"}}}' " + url,
                ContentKind.COMMAND));
        return snippets;
    }

    private String claudeDesktopStdio(BundleManifest manifest, DeployTarget target) {
        StringBuilder args = new StringBuilder();
        target.cliArguments().forEach(argument -> args.append("\"").append(argument).append("\", "));
        args.append("\"run\", \"-i\", \"--rm\",\n");
        args.append("        \"--add-host\", \"").append(BundleArtifactWriter.HOST_GATEWAY).append("\",\n");
        args.append("        \"-e\", \"SPRING_PROFILES_INCLUDE=mcp-stdio\",\n");
        for (BundleManifest.EnvVar envVar : manifest.requires().env()) {
            args.append("        \"-e\", \"").append(envVar.name()).append("\",\n");
        }
        args.append("        \"").append(BundleArtifactWriter.localImage(manifest)).append("\"");
        return """
                {
                  "mcpServers": {
                    "%s": {
                      "command": "docker",
                      "args": [%s]
                    }
                  }
                }""".formatted(manifest.server().name(), args);
    }

}
