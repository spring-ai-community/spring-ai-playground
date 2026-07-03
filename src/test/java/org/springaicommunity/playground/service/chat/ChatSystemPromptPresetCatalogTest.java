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
package org.springaicommunity.playground.service.chat;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.Preset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSystemPromptPresetCatalogTest {

    @Test
    void loadsShippedPresetsInOrder() throws IOException {
        ChatSystemPromptPresetCatalog catalog = new ChatSystemPromptPresetCatalog();
        assertThat(catalog.presets()).extracting(Preset::id)
                .containsExactly("self-equipping-agent",
                        "skill-agent", "domain-expert", "custom-role", "structured-output",
                        "summarizer", "translator", "socratic-tutor", "decision-matrix",
                        "general-assistant", "daily-assistant", "research-agent",
                        "data-wrangler", "korea-concierge", "github-repo-analyst",
                        "release-notes-writer", "log-detective", "crypto-market-watch",
                        "trip-planner", "tech-pulse", "data-visualizer",
                        "market-charts", "data-analysis", "diff-inspector", "workspace-organizer");
    }

    @Test
    void everyPresetCarriesANonBlankPrompt() throws IOException {
        ChatSystemPromptPresetCatalog catalog = new ChatSystemPromptPresetCatalog();
        assertThat(catalog.presets()).allSatisfy(preset -> assertThat(preset.prompt()).isNotBlank());
    }

    @Test
    void findByIdReturnsPresetOrEmpty() throws IOException {
        ChatSystemPromptPresetCatalog catalog = new ChatSystemPromptPresetCatalog();
        assertThat(catalog.findById("research-agent")).isPresent();
        assertThat(catalog.findById("nope")).isEmpty();
        assertThat(catalog.findById(null)).isEmpty();
    }

    @Test
    void nullPromptIsNormalizedToEmpty() {
        assertThat(new Preset("x", "X", "desc", null, null, null, false).prompt()).isEmpty();
        assertThat(new Preset("x", "X", "desc", null, null, null, false).tools()).isEmpty();
    }

    @Test
    void presetsCarryTheirRequiredTools() throws IOException {
        ChatSystemPromptPresetCatalog catalog = new ChatSystemPromptPresetCatalog();
        assertThat(catalog.findById("log-detective").orElseThrow().tools())
                .contains("findFiles", "grepFile", "sliceFile", "stats");
        assertThat(catalog.findById("skill-agent").orElseThrow().tools()).isEmpty();
    }

    @Test
    void everyPresetToolResolvesToAShippedBuiltinTool() throws IOException {
        Set<String> toolNames = shippedToolNames();
        ChatSystemPromptPresetCatalog catalog = new ChatSystemPromptPresetCatalog();
        for (Preset preset : catalog.presets()) {
            assertThat(toolNames)
                    .as("preset '%s' names a tool that no shipped spec file defines", preset.id())
                    .containsAll(preset.tools());
        }
    }

    private static Set<String> shippedToolNames() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> names = new HashSet<>();
        for (String file : List.of(
                "/tool/default-tool-specs.json",
                "/tool/default-tool-specs-builtin.json",
                "/tool/default-tool-specs-builtin-helpers.json",
                "/tool/default-tool-specs-builtin-fs.json",
                "/tool/default-tool-specs-network.json",
                "/tool/default-tool-specs-kr.json")) {
            try (InputStream in = ChatSystemPromptPresetCatalogTest.class.getResourceAsStream(file)) {
                if (in == null) continue;
                for (JsonNode node : mapper.readTree(in)) {
                    if (node.has("name")) names.add(node.get("name").asText());
                }
            }
        }
        return names;
    }

    @Test
    void selfEquippingAgentPresetUsesDynamicDiscovery() throws IOException {
        Preset preset = new ChatSystemPromptPresetCatalog().findById("self-equipping-agent").orElseThrow();
        assertThat(preset.dynamicTools()).isTrue();
        assertThat(preset.tools()).isEmpty();
    }

    @Test
    void nonDynamicPresetsDefaultToFalse() throws IOException {
        assertThat(new ChatSystemPromptPresetCatalog().findById("data-wrangler").orElseThrow().dynamicTools())
                .isFalse();
    }

    @Test
    void nullDynamicToolsNormalizesToFalse() {
        assertThat(new Preset("x", "X", "desc", null, null, null, null).dynamicTools()).isFalse();
    }
}
