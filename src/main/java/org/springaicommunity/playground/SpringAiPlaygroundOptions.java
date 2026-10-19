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

import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.vectorstore.DocumentSpec;
import org.springaicommunity.playground.service.vectorstore.PipelineSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "spring.ai.playground")
public record SpringAiPlaygroundOptions(@NestedConfigurationProperty ToolStudio toolStudio, boolean persistence,
                                        String userHome, @NestedConfigurationProperty Chat chat,
                                        @NestedConfigurationProperty BuiltInMcpServer builtInMcpServer,
                                        @NestedConfigurationProperty McpServer mcpServer,
                                        @NestedConfigurationProperty Hitl hitl,
                                        @NestedConfigurationProperty Shipyard shipyard,
                                        @NestedConfigurationProperty Management management,
                                        @NestedConfigurationProperty Rag rag) {

    @ConstructorBinding
    public SpringAiPlaygroundOptions {
        if (builtInMcpServer == null) builtInMcpServer = new BuiltInMcpServer(null, null, null);
        if (mcpServer == null) mcpServer = new McpServer(null, null, null);
        if (hitl == null) hitl = new Hitl(null);
        if (shipyard == null) shipyard = new Shipyard(null, null, null, null, null);
        if (management == null) management = new Management(null, null, null, null, null);
        if (rag == null) rag = new Rag(null, null, null);
    }

    public record Rag(List<String> ingestRoots, List<DocumentSpec> documents, List<PipelineSpec> pipelines) {

        public Rag {
            ingestRoots = ingestRoots == null ? List.of() : List.copyOf(ingestRoots);
            documents = documents == null ? List.of() : List.copyOf(documents);
            pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
        }
    }

    public enum ApprovalMode {
        DESTRUCTIVE, ALL, NONE
    }

    public record Management(Boolean enabled, Set<String> tools, Set<String> exclude, ApprovalMode approval,
                             Integer auditCapacity) {

        public static final int DEFAULT_AUDIT_CAPACITY = 500;

        public Management {
            if (enabled == null) enabled = Boolean.TRUE;
            tools = tools == null ? Set.of() : Set.copyOf(tools);
            exclude = exclude == null ? Set.of() : Set.copyOf(exclude);
            if (approval == null) approval = ApprovalMode.DESTRUCTIVE;
            if (auditCapacity == null || auditCapacity <= 0) auditCapacity = DEFAULT_AUDIT_CAPACITY;
        }

        public static final Set<String> OPT_IN_CATEGORIES = Set.of("mcp-register");

        public boolean categoryEnabled(String category) {
            return this.tools.isEmpty() ? !OPT_IN_CATEGORIES.contains(category) : this.tools.contains(category);
        }

        public boolean toolExcluded(String toolName) {
            return this.exclude.contains(toolName);
        }
    }

    public record Hitl(Integer approvalWaitSeconds) {
        private static final int DEFAULT_APPROVAL_WAIT_SECONDS = 180;
        private static final int STREAM_SAFE_MAX_WAIT_SECONDS = 240;
        private static final Duration TRANSPORT_MARGIN = Duration.ofSeconds(30);

        public Hitl {
            if (approvalWaitSeconds == null || approvalWaitSeconds <= 0) {
                approvalWaitSeconds = DEFAULT_APPROVAL_WAIT_SECONDS;
            }
            approvalWaitSeconds = Math.min(approvalWaitSeconds, STREAM_SAFE_MAX_WAIT_SECONDS);
        }

        public Duration approvalWait() {
            return Duration.ofSeconds(approvalWaitSeconds);
        }

        public Duration transportTimeout() {
            return approvalWait().plus(TRANSPORT_MARGIN);
        }
    }

    public record Shipyard(String dockerExecutable, String imageRepository, String imageTag,
                           List<ShipyardServer> servers, List<ShipyardTarget> targets) {

        public static final String DEFAULT_IMAGE_REPOSITORY = "ghcr.io/spring-ai-community/spring-ai-playground";

        public Shipyard {
            if (imageRepository == null || imageRepository.isBlank()) imageRepository = DEFAULT_IMAGE_REPOSITORY;
            servers = servers == null ? List.of() : List.copyOf(servers);
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        public boolean manages(String serverName) {
            return servers.stream().anyMatch(server -> server.name() != null && server.name().equals(serverName));
        }
    }

    public record ShipyardServer(String name, String description, String version, List<String> tools,
                                 List<ComposedTool> composedTools, RiskLevel composedToolsMaxRisk,
                                 Transport transport, Integer hostPort,
                                 @NestedConfigurationProperty ShipyardModel chatModel,
                                 @NestedConfigurationProperty ShipyardModel embeddingModel, String target,
                                 List<ShipyardKnowledge> knowledge, Boolean autoRun) {

        public ShipyardServer {
            tools = tools == null ? List.of() : List.copyOf(tools);
            composedTools = composedTools == null ? List.of() : List.copyOf(composedTools);
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        }

        public boolean autoRuns() {
            return Boolean.TRUE.equals(autoRun);
        }
    }

    public record ShipyardKnowledge(String source, String name, String description, Integer topK) {}

    public record ShipyardModel(String provider, String model) {}

    public record ShipyardTarget(String name, String dockerContext, String dockerHost, String publicHost) {}

    public record BuiltInMcpServer(String name, String description, ExposureMode exposureMode) {
        public BuiltInMcpServer {
            if (name == null || name.isBlank()) name = "spring-ai-playground-built-in-mcp";
            if (description == null || description.isBlank()) {
                description = "Spring AI Playground's built-in MCP server.";
            }
            if (exposureMode == null) exposureMode = ExposureMode.BOTH;
        }
    }

    public record McpServer(RiskLevel composedToolsMaxRisk, List<ComposedTool> composedTools, String authToken) {
        public McpServer {
            if (composedToolsMaxRisk == null) composedToolsMaxRisk = RiskLevel.L5;
            composedTools = composedTools == null ? List.of() : List.copyOf(composedTools);
        }

        public boolean authTokenRequired() {
            return authToken != null && !authToken.isBlank();
        }
    }

    public record ComposedTool(String server, String tool, String alias, String description, boolean hitl) {}

    public record DefaultTools(String preset,
                               @NestedConfigurationProperty SelectionRule include,
                               @NestedConfigurationProperty SelectionRule exclude) {}

    public record SelectionRule(Set<String> names, Set<String> tags, Set<String> categories) {}

    public record ToolStudio(Long timeoutSeconds, @NestedConfigurationProperty JsSandbox jsSandbox,
                             @NestedConfigurationProperty FsConfig fs,
                             @NestedConfigurationProperty DefaultTools defaultTools) {}

    public record JsSandbox(boolean allowNetworkIo, boolean allowFileIo, boolean allowNativeAccess,
                            boolean allowCreateThread, Long maxStatements, Set<String> denyClasses,
                            Set<String> allowClasses, Map<String, SandboxProfile> profiles) {}

    public record SandboxProfile(String level, String extendsProfile, Boolean javaInterop,
                                 Set<String> allowClasses, Set<String> denyClasses,
                                 @NestedConfigurationProperty NetworkPolicy network,
                                 String fileMode,
                                 Long maxStatements, Long timeoutMs) {}

    public record NetworkPolicy(String egressLevel, Set<String> hostsAllow, Set<String> hostsDeny) {}

    public record FsConfig(String basePath) {}

    public record Chat(String systemPrompt, List<String> models,
                       @NestedConfigurationProperty ChatOptionsConfig chatOptions, Integer toolResultMaxChars,
                       Integer memoryMaxMessages, Integer historyMaxMessages, Integer defaultMaxTokens,
                       @NestedConfigurationProperty ToolSearch toolSearch, String defaultPreset,
                       @NestedConfigurationProperty AgentLoop agentLoop) {
        public Chat {
            if (toolResultMaxChars == null) toolResultMaxChars = 12_000;
            if (memoryMaxMessages == null || memoryMaxMessages <= 0) memoryMaxMessages = 10;
            if (historyMaxMessages == null || historyMaxMessages <= 0) historyMaxMessages = 2_000;
            if (defaultMaxTokens == null || defaultMaxTokens <= 0) defaultMaxTokens = 8_192;
            if (toolSearch == null) toolSearch = new ToolSearch(null, null, null, null, null);
            if (agentLoop == null) agentLoop = new AgentLoop(null, null, null, null);
        }
    }

    public record AgentLoop(Integer softMaxRounds, Integer hardMaxRounds, Integer maxIdenticalCalls,
                            Integer interactionsPerRound) {

        public AgentLoop {
            if (softMaxRounds == null || softMaxRounds <= 0) softMaxRounds = 16;
            if (hardMaxRounds == null || hardMaxRounds <= 0) hardMaxRounds = 18;
            if (hardMaxRounds < softMaxRounds) hardMaxRounds = softMaxRounds;
            if (maxIdenticalCalls == null || maxIdenticalCalls <= 0) maxIdenticalCalls = 3;
            if (interactionsPerRound == null || interactionsPerRound <= 0) interactionsPerRound = 1;
        }
    }

    public record ChatOptionsConfig(String model, Double temperature, Integer maxTokens, Double topP,
                                    Integer topK, Double frequencyPenalty, Double presencePenalty,
                                    List<String> stopSequences) {}

    public record ToolSearch(Boolean enabled, Integer minTools, Integer maxResults,
                             IndexType indexType, VectorStoreMode vectorStore) {
        public enum IndexType { HYBRID, VECTOR }

        public enum VectorStoreMode { DEDICATED, SHARED }

        public ToolSearch {
            if (enabled == null) enabled = true;
            if (minTools == null || minTools < 0) minTools = 10;
            if (maxResults == null || maxResults <= 0) maxResults = 3;
            if (indexType == null) indexType = IndexType.HYBRID;
            if (vectorStore == null) vectorStore = VectorStoreMode.DEDICATED;
        }
    }
}
