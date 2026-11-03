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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springaicommunity.playground.service.mcp.risk.McpComposition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleManifest(String bundleVersion, Server server, Requires requires, Contents contents,
        Risk risk, Integrity integrity, Provenance provenance) {

    public static final String FILE_NAME = "saip-bundle.json";
    public static final String CURRENT_BUNDLE_VERSION = "1";
    public static final String HOME_DIR = "home";

    private static Map<String, String> ordered(Map<String, String> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public BundleManifest {
        if (requires == null) requires = new Requires(null, null, null, null);
        if (contents == null) contents = new Contents(null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Server(String name, String description, String version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requires(String minRuntimeVersion, List<EnvVar> env, Models models,
            List<String> upstreamServers) {

        public Requires {
            env = env == null ? List.of() : List.copyOf(env);
            upstreamServers = upstreamServers == null ? List.of() : List.copyOf(upstreamServers);
            if (models == null) models = new Models(null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnvVar(String name, String purpose, boolean secret) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Models(ModelRef chat, ModelRef embedding) {

        public boolean modelFree() {
            return chat == null && embedding == null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelRef(String provider, String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contents(List<String> tools, ComposedTools composedTools,
            List<RagPipelineRef> ragPipelines, List<AgentRef> agents) {

        public Contents {
            tools = tools == null ? List.of() : List.copyOf(tools);
            ragPipelines = ragPipelines == null ? List.of() : List.copyOf(ragPipelines);
            agents = agents == null ? List.of() : List.copyOf(agents);
        }

        public List<McpComposition.Member> composedMembers() {
            return composedTools == null ? List.of() : composedTools.members();
        }

        public int toolCount() {
            return tools.size() + composedMembers().size() + ragPipelines.size();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComposedTools(String compositionId, List<McpComposition.Member> members, String maxRiskLevel) {

        public ComposedTools {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagPipelineRef(String id, Expose expose, String alias, String description, String source,
            Integer topK, List<DocumentRef> documents) {

        public RagPipelineRef {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public RagPipelineRef(String id, Expose expose, String alias, String description) {
            this(id, expose, alias, description, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentRef(String docInfoId, String title, int chunks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expose(boolean search, boolean ask) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRef(String specFile, String toolName, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Risk(String maxLevel, List<ToolRisk> summary) {

        public Risk {
            summary = summary == null ? List.of() : List.copyOf(summary);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolRisk(String tool, String level, boolean hitl, String kind, String description) {

        public ToolRisk(String tool, String level, boolean hitl) {
            this(tool, level, hitl, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Integrity(String scheme, Map<String, String> files, Map<String, String> tools) {

        public Integrity {
            files = ordered(files);
            tools = ordered(tools);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provenance(String exportedBy, String exportedAt, Map<String, String> sources) {

        public Provenance {
            sources = ordered(sources);
        }

        public Provenance(String exportedBy, String exportedAt) {
            this(exportedBy, exportedAt, null);
        }
    }

}
