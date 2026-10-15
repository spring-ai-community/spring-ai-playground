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
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;
import org.springaicommunity.playground.service.mcp.risk.McpCompositionService;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.util.EnvVarResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@Order(2)
public class ProxiedToolsContribution implements BundleContribution {

    static final String UPSTREAM_PURPOSE_PREFIX = "referenced by upstream";
    private static final String SOURCE_PREFIX = "upstream:";
    private static final Logger logger = LoggerFactory.getLogger(ProxiedToolsContribution.class);

    private final ObjectProvider<McpServerInfoService> mcpServerInfoServiceProvider;
    private final ObjectProvider<McpClientService> mcpClientServiceProvider;
    private final ObjectProvider<McpCompositionService> compositionServiceProvider;

    public ProxiedToolsContribution(ObjectProvider<McpServerInfoService> mcpServerInfoServiceProvider,
            ObjectProvider<McpClientService> mcpClientServiceProvider,
            ObjectProvider<McpCompositionService> compositionServiceProvider) {
        this.mcpServerInfoServiceProvider = mcpServerInfoServiceProvider;
        this.mcpClientServiceProvider = mcpClientServiceProvider;
        this.compositionServiceProvider = compositionServiceProvider;
    }

    @Override
    public void validate(ShipRequest request, ShipDraft draft) {
        Map<String, McpServerInfo> upstreams = new LinkedHashMap<>();
        for (McpComposition.Member member : request.composedTools()) {
            draft.ship(new ShippedTool(member.exposedAlias(), ShippedTool.Kind.PROXIED, member.descriptionOverride(),
                    "max " + request.composedToolsMaxRisk().name(), request.composedToolsMaxRisk(), member.hitl()));
            Optional<McpServerInfo> upstream = findUpstream(member.serverId());
            if (upstream.isEmpty()) {
                draft.block("Proxied tool '" + member.exposedAlias() + "' needs upstream server '"
                        + member.serverId() + "', which is not configured in this app");
                continue;
            }
            upstreams.putIfAbsent(member.serverId(), upstream.get());
        }
        for (McpServerInfo upstream : upstreams.values()) {
            upstreamBlockers(upstream).forEach(draft::block);
            for (BundleManifest.EnvVar envVar : envRequirements(upstream)) {
                draft.requireEnv(envVar);
                if (EnvVarResolver.lookup(envVar.name()).isEmpty()) {
                    draft.warn("Environment variable " + envVar.name() + " (" + envVar.purpose()
                            + ") is not set on this machine");
                }
            }
        }
    }

    private List<String> upstreamBlockers(McpServerInfo upstream) {
        String name = upstream.serverName();
        if (upstream.mcpTransportType() == McpTransportType.STDIO) {
            return List.of("Upstream '" + name + "' runs as a local stdio command; the bundle image cannot "
                    + "start it, so only Streamable HTTP upstreams can ship");
        }
        if (upstream.mcpTransportType() != McpTransportType.STREAMABLE_HTTP) {
            return List.of("Upstream '" + name + "' uses " + upstream.mcpTransportType().getValue()
                    + "; Shipyard only ships Streamable HTTP upstreams");
        }
        Map<String, Object> connection = connectionOf(upstream);
        List<String> blockers = new ArrayList<>();
        if (connection.get("oauth") != null) {
            blockers.add("Upstream '" + name + "' signs in with OAuth; its tokens stay on this machine, so it "
                    + "cannot ship yet");
        }
        headersOf(connection).forEach((header, value) -> {
            if (BundleSecrets.literalSecretHeader(header, value)) {
                blockers.add("Upstream '" + name + "' header '" + header + "' holds a literal secret; reference "
                        + "it as ${ENV_VAR} instead");
            }
        });
        for (String parameter : queryParametersOf(String.valueOf(connection.getOrDefault("url", "")))) {
            int separator = parameter.indexOf('=');
            if (separator <= 0) continue;
            String parameterName = parameter.substring(0, separator);
            if (BundleSecrets.literalSecretHeader(parameterName, parameter.substring(separator + 1))) {
                blockers.add("Upstream '" + name + "' URL parameter '" + parameterName
                        + "' holds a literal secret; reference it as ${ENV_VAR} instead");
            }
        }
        return blockers;
    }

    private List<BundleManifest.EnvVar> envRequirements(McpServerInfo upstream) {
        Map<String, Object> connection = connectionOf(upstream);
        Set<String> headerRefs = new LinkedHashSet<>();
        headersOf(connection).values().forEach(value -> headerRefs.addAll(EnvVarResolver.findRefs(value)));
        Set<String> refs = new LinkedHashSet<>(headerRefs);
        for (String key : List.of("url", "endpoint")) {
            refs.addAll(EnvVarResolver.findRefs(String.valueOf(connection.getOrDefault(key, ""))));
        }
        if (connection.get("requiredEnv") instanceof List<?> required) {
            required.forEach(name -> refs.add(String.valueOf(name)));
        }
        return refs.stream().map(ref -> new BundleManifest.EnvVar(ref,
                UPSTREAM_PURPOSE_PREFIX + " '" + upstream.serverName() + "'",
                headerRefs.contains(ref) || BundleSecrets.secretName(ref))).toList();
    }

    @Override
    public void write(ShipRequest request, BundleWriter writer) throws IOException {
        List<McpComposition.Member> members = request.composedTools();
        for (McpServerInfo upstream : upstreamsOf(members)) {
            String stem = upstreamFileStem(upstream);
            McpServerInfo snapshot = new McpServerInfo(upstream.mcpTransportType(), upstream.serverName(),
                    upstream.description(), upstream.createTimestamp(), upstream.updateTimestamp(),
                    connectionReachableFromContainer(upstream), upstream.category(), upstream.tags(), null);
            writer.write("mcp/save/" + stem + ".json", PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(
                    PersistenceServiceInterface.OBJECT_MAPPER.convertValue(snapshot,
                            PersistenceServiceInterface.MAP_TYPE_REFERENCE)));
            writer.upstreamServer(stem);
        }
        if (!members.isEmpty()) {
            writer.composedTools(new BundleManifest.ComposedTools(McpCompositionService.EXPOSED_ID, members,
                    request.composedToolsMaxRisk().name()));
        }
    }

    @Override
    public Map<String, String> fingerprints(ShipRequest request) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (McpServerInfo upstream : upstreamsOf(request.composedTools())) {
            fingerprints.put(SOURCE_PREFIX + upstream.serverName(), BundleExportService.sha256(
                    Objects.requireNonNullElse(upstream.connectionAsJson(), "").getBytes(StandardCharsets.UTF_8)));
        }
        return fingerprints;
    }

    @Override
    public void verify(BundleManifest manifest, List<String> problems) {}

    @Override
    public List<McpServerInfo> serve(BundleManifest manifest) {
        List<McpComposition.Member> members = manifest.contents().composedMembers();
        if (members.isEmpty()) return List.of();
        List<McpServerInfo> upstreams = connectUpstreams(manifest);
        McpCompositionService compositionService = this.compositionServiceProvider.getIfAvailable();
        if (compositionService != null) {
            String maxRisk = manifest.contents().composedTools().maxRiskLevel();
            compositionService.upsertExposed(members, maxRisk == null ? RiskLevel.L5 : RiskLevel.valueOf(maxRisk));
        }
        return upstreams;
    }

    private List<McpServerInfo> connectUpstreams(BundleManifest manifest) {
        McpServerInfoService infoService = this.mcpServerInfoServiceProvider.getIfAvailable();
        McpClientService clientService = this.mcpClientServiceProvider.getIfAvailable();
        if (infoService == null || clientService == null) return List.of();
        Set<String> stems = new LinkedHashSet<>(manifest.requires().upstreamServers());
        List<McpServerInfo> upstreams = infoService.getMcpServerInfos().values().stream()
                .flatMap(List::stream)
                .filter(upstream -> stems.contains(upstreamFileStem(upstream)))
                .toList();
        upstreams.stream().filter(upstream -> !clientService.isConnecting(upstream)).forEach(upstream -> {
            try {
                clientService.startMcpClient(upstream);
            } catch (RuntimeException e) {
                logger.debug("Bundle upstream '{}' did not connect at startup: {}", upstream.serverName(),
                        e.getMessage());
            }
        });
        return upstreams;
    }

    private List<McpServerInfo> upstreamsOf(List<McpComposition.Member> members) {
        Map<String, McpServerInfo> upstreams = new LinkedHashMap<>();
        for (McpComposition.Member member : members) {
            findUpstream(member.serverId()).ifPresent(upstream -> upstreams.putIfAbsent(member.serverId(), upstream));
        }
        return List.copyOf(upstreams.values());
    }

    private Optional<McpServerInfo> findUpstream(String serverName) {
        McpServerInfoService infoService = this.mcpServerInfoServiceProvider.getIfAvailable();
        if (infoService == null || serverName == null) return Optional.empty();
        McpServerInfo builtIn = infoService.getDefaultMcpServerInfo();
        return infoService.getMcpServerInfos().values().stream()
                .flatMap(List::stream)
                .filter(info -> serverName.equals(info.serverName()) && !info.equals(builtIn))
                .findFirst();
    }

    public static Map<String, String> upstreamUrls(Path bundleDir, BundleManifest manifest) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String stem : manifest.requires().upstreamServers()) {
            Path snapshot = bundleDir.resolve("home").resolve("mcp").resolve("save").resolve(stem + ".json");
            try {
                McpServerInfo upstream = PersistenceServiceInterface.OBJECT_MAPPER.readValue(
                        Files.readAllBytes(snapshot), McpServerInfo.class);
                Object url = connectionOf(upstream).get("url");
                if (url != null) urls.put(upstream.serverName(), url.toString());
            } catch (IOException | RuntimeException e) {
                logger.debug("Could not read upstream snapshot {}: {}", snapshot, e.getMessage());
            }
        }
        return urls;
    }

    private static String connectionReachableFromContainer(McpServerInfo upstream) {
        Map<String, Object> connection = connectionOf(upstream);
        String url = String.valueOf(connection.getOrDefault("url", ""));
        String reachable = RunPlanner.reachableFromContainer(url);
        if (reachable.equals(url)) return upstream.connectionAsJson();
        Map<String, Object> rewritten = new LinkedHashMap<>(connection);
        rewritten.put("url", reachable);
        return PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(rewritten);
    }

    static String upstreamFileStem(McpServerInfo upstream) {
        return upstream.mcpTransportType() + "-" + upstream.serverName();
    }

    private static Map<String, Object> connectionOf(McpServerInfo upstream) {
        if (upstream.connectionAsJson() == null || upstream.connectionAsJson().isBlank()) return Map.of();
        return PersistenceServiceInterface.OBJECT_MAPPER.readValue(upstream.connectionAsJson(),
                PersistenceServiceInterface.MAP_TYPE_REFERENCE);
    }

    private static Map<String, String> headersOf(Map<String, Object> connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (connection.get("headers") instanceof Map<?, ?> raw) {
            raw.forEach((header, value) -> headers.put(String.valueOf(header),
                    value == null ? "" : String.valueOf(value)));
        }
        return headers;
    }

    private static List<String> queryParametersOf(String url) {
        int query = url.indexOf('?');
        return query < 0 ? List.of() : List.of(url.substring(query + 1).split("&"));
    }

}
