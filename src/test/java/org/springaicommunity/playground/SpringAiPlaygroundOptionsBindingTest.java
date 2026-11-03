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
package org.springaicommunity.playground;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiPlaygroundOptionsBindingTest {

    private static SpringAiPlaygroundOptions bindFromEnvironmentVariables(Map<String, String> variables) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(variables)));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.ai.playground", SpringAiPlaygroundOptions.class).get();
    }

    @Test
    void shipyardServersAndDockerSettingsBindFromEnvironmentVariables() {
        SpringAiPlaygroundOptions options = bindFromEnvironmentVariables(Map.ofEntries(
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_DOCKEREXECUTABLE", "/usr/local/bin/podman"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_IMAGEREPOSITORY", "registry.example.com/saip"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_IMAGETAG", "0.2.0"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_NAME", "hotspots"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_TOOLS_0", "accidentHotspotBriefing"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_TOOLS_1", "getCurrentTime"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_TRANSPORT", "http"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_HOSTPORT", "18400"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_CHATMODEL_PROVIDER", "ollama"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_CHATMODEL_MODEL", "qwen3.5:4b"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_COMPOSEDTOOLS_0_SERVER", "DeepWiki"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_COMPOSEDTOOLS_0_TOOL", "read_wiki_structure"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_COMPOSEDTOOLSMAXRISK", "L3"),
                Map.entry("SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_AUTORUN", "true")));

        assertThat(options.shipyard().dockerExecutable()).isEqualTo("/usr/local/bin/podman");
        assertThat(options.shipyard().imageRepository()).isEqualTo("registry.example.com/saip");
        assertThat(options.shipyard().imageTag()).isEqualTo("0.2.0");
        ShipyardServer server = options.shipyard().servers().getFirst();
        assertThat(server.name()).isEqualTo("hotspots");
        assertThat(server.tools()).containsExactly("accidentHotspotBriefing", "getCurrentTime");
        assertThat(server.transport()).isEqualTo(Transport.HTTP);
        assertThat(server.hostPort()).isEqualTo(18400);
        assertThat(server.chatModel().provider()).isEqualTo("ollama");
        assertThat(server.chatModel().model()).isEqualTo("qwen3.5:4b");
        assertThat(server.composedTools().getFirst().server()).isEqualTo("DeepWiki");
        assertThat(server.composedTools().getFirst().tool()).isEqualTo("read_wiki_structure");
        assertThat(server.composedToolsMaxRisk()).isEqualTo(RiskLevel.L3);
        assertThat(server.autoRuns()).isTrue();
        assertThat(options.shipyard().manages("hotspots")).isTrue();
    }

    @Test
    void composedToolsBindFromEnvironmentVariables() {
        SpringAiPlaygroundOptions options = bindFromEnvironmentVariables(Map.of(
                "SPRING_AI_PLAYGROUND_MCPSERVER_COMPOSEDTOOLSMAXRISK", "L3",
                "SPRING_AI_PLAYGROUND_MCPSERVER_COMPOSEDTOOLS_0_SERVER", "DeepWiki",
                "SPRING_AI_PLAYGROUND_MCPSERVER_COMPOSEDTOOLS_0_TOOL", "ask_question",
                "SPRING_AI_PLAYGROUND_MCPSERVER_COMPOSEDTOOLS_0_ALIAS", "deepwiki_ask",
                "SPRING_AI_PLAYGROUND_MCPSERVER_COMPOSEDTOOLS_0_HITL", "true"));

        assertThat(options.mcpServer().composedToolsMaxRisk()).isEqualTo(RiskLevel.L3);
        SpringAiPlaygroundOptions.ComposedTool tool = options.mcpServer().composedTools().getFirst();
        assertThat(tool.server()).isEqualTo("DeepWiki");
        assertThat(tool.tool()).isEqualTo("ask_question");
        assertThat(tool.alias()).isEqualTo("deepwiki_ask");
        assertThat(tool.hitl()).isTrue();
    }

    @Test
    void shipyardDefaultsWhenNothingIsConfigured() {
        SpringAiPlaygroundOptions options = bindFromEnvironmentVariables(Map.of(
                "SPRING_AI_PLAYGROUND_PERSISTENCE", "true"));

        assertThat(options.shipyard().imageRepository())
                .isEqualTo(SpringAiPlaygroundOptions.Shipyard.DEFAULT_IMAGE_REPOSITORY);
        assertThat(options.shipyard().dockerExecutable()).isNull();
        assertThat(options.shipyard().servers()).isEmpty();
        assertThat(options.shipyard().targets()).isEmpty();
    }

    @Test
    void shipyardTargetsAndAServerTargetBindFromEnvironmentVariables() {
        SpringAiPlaygroundOptions options = bindFromEnvironmentVariables(Map.of(
                "SPRING_AI_PLAYGROUND_SHIPYARD_TARGETS_0_NAME", "build-box",
                "SPRING_AI_PLAYGROUND_SHIPYARD_TARGETS_0_DOCKERHOST", "ssh://ops@build.example.com",
                "SPRING_AI_PLAYGROUND_SHIPYARD_TARGETS_0_PUBLICHOST", "mcp.example.com",
                "SPRING_AI_PLAYGROUND_SHIPYARD_TARGETS_1_NAME", "prod",
                "SPRING_AI_PLAYGROUND_SHIPYARD_TARGETS_1_DOCKERCONTEXT", "prod-context",
                "SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_NAME", "hotspots",
                "SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_TARGET", "build-box",
                "SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_KNOWLEDGE_0_SOURCE", "Product FAQ",
                "SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_KNOWLEDGE_0_NAME", "search_faq",
                "SPRING_AI_PLAYGROUND_SHIPYARD_SERVERS_0_KNOWLEDGE_0_TOPK", "6"));

        assertThat(options.shipyard().targets()).containsExactly(
                new SpringAiPlaygroundOptions.ShipyardTarget("build-box", null, "ssh://ops@build.example.com",
                        "mcp.example.com"),
                new SpringAiPlaygroundOptions.ShipyardTarget("prod", "prod-context", null, null));
        assertThat(options.shipyard().servers().getFirst().target()).isEqualTo("build-box");
        assertThat(options.shipyard().servers().getFirst().knowledge()).containsExactly(
                new SpringAiPlaygroundOptions.ShipyardKnowledge("Product FAQ", "search_faq", null, 6));
    }

    @Test
    void managementSettingsBindFromEnvironmentVariables() {
        SpringAiPlaygroundOptions options = bindFromEnvironmentVariables(Map.of(
                "SPRING_AI_PLAYGROUND_MANAGEMENT_ENABLED", "false",
                "SPRING_AI_PLAYGROUND_MANAGEMENT_TOOLS_0", "expose",
                "SPRING_AI_PLAYGROUND_MANAGEMENT_TOOLS_1", "monitor",
                "SPRING_AI_PLAYGROUND_MANAGEMENT_EXCLUDE_0", "saip_expose_apply",
                "SPRING_AI_PLAYGROUND_MANAGEMENT_APPROVAL", "all",
                "SPRING_AI_PLAYGROUND_MANAGEMENT_AUDITCAPACITY", "120"));

        SpringAiPlaygroundOptions.Management management = options.management();
        assertThat(management.enabled()).isFalse();
        assertThat(management.tools()).containsExactlyInAnyOrder("expose", "monitor");
        assertThat(management.exclude()).containsExactly("saip_expose_apply");
        assertThat(management.approval()).isEqualTo(SpringAiPlaygroundOptions.ApprovalMode.ALL);
        assertThat(management.auditCapacity()).isEqualTo(120);
        assertThat(management.categoryEnabled("expose")).isTrue();
        assertThat(management.categoryEnabled("mcp")).isFalse();
        assertThat(management.toolExcluded("saip_expose_apply")).isTrue();
    }

    @Test
    void managementDefaultsEnableEverythingExceptRegistrationWithApprovalForDestructiveTools() {
        SpringAiPlaygroundOptions.Management management = bindFromEnvironmentVariables(Map.of(
                "SPRING_AI_PLAYGROUND_PERSISTENCE", "true")).management();

        assertThat(management.enabled()).isTrue();
        assertThat(management.approval()).isEqualTo(SpringAiPlaygroundOptions.ApprovalMode.DESTRUCTIVE);
        assertThat(management.categoryEnabled("mcp")).isTrue();
        assertThat(management.categoryEnabled("expose")).isTrue();
        assertThat(management.categoryEnabled("monitor")).isTrue();
        assertThat(management.categoryEnabled("mcp-register")).isFalse();
        assertThat(management.auditCapacity()).isEqualTo(500);
    }

}
