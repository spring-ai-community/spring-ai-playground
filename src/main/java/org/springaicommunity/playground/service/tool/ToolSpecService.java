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
package org.springaicommunity.playground.service.tool;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.config.MdcIdentityFilter;
import org.springaicommunity.playground.service.chat.ToolIndexWarmup;
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.ExternalClientToolCallObserver;
import org.springaicommunity.playground.service.mcp.risk.WrappedExternalToolCallback;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.risk.DefaultIntegrityVerifier;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.JsSandbox;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.NetworkPolicy;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor.JsExecutionParams;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor.JsExecutionResult;
import org.springaicommunity.playground.service.tool.policy.EffectivePolicyResolver;
import org.springaicommunity.playground.service.tool.policy.EffectivePolicyResolver.EffectivePolicy;
import org.springaicommunity.playground.service.tool.policy.EffectivePolicyResolver.Overrides;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator.State;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ToolSpecService {

    public enum ExposureMode {
        BUILTIN_ONLY, COMPOSED_ONLY, BOTH;

        public boolean includesBuiltin() {
            return this != COMPOSED_ONLY;
        }

        public boolean includesComposed() {
            return this != BUILTIN_ONLY;
        }
    }

    public record ToolMcpServerSetting(boolean autoAdd, Set<String> exposedToolIds, Set<String> excludedToolIds) {
        public ToolMcpServerSetting {
            exposedToolIds = exposedToolIds == null ? Set.of() : exposedToolIds;
            excludedToolIds = excludedToolIds == null ? Set.of() : excludedToolIds;
        }
        public ToolMcpServerSetting(boolean autoAdd, Set<String> exposedToolIds) {
            this(autoAdd, exposedToolIds, Set.of());
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ToolSpecService.class);
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final ObjectMapper TEST_VALUE_MAPPER = new ObjectMapper();
    private static final ParameterizedTypeReference<Map<String, Object>>
            MAP_PARAMETERIZED_TYPE_REFERENCE = new ParameterizedTypeReference<>() {};
    private static final ToolManifest.HumanInTheLoop REQUIRED_HITL =
            new ToolManifest.HumanInTheLoop(ToolManifest.HumanInTheLoop.Mode.REQUIRED, null);

    private final McpSyncServer mcpSyncServer;
    private final McpAsyncServer mcpAsyncServer;
    private final McpServerInfoService mcpServerInfoService;
    private final McpServerHitlToolGate hitlGate;
    private final ExternalClientToolCallObserver externalClientToolCallObserver;
    private final ObjectProvider<ToolSpecPersistenceService> toolSpecPersistenceServiceProvider;
    private final ObjectProvider<ToolIndexWarmup> toolIndexWarmupProvider;
    private final Map<String, ToolSpec> toolIdSpecs;
    private final JsToolExecutor jsToolExecutor;
    private final JsSandbox sandboxBaseline;
    private final EffectivePolicyResolver policyResolver;
    private final SandboxPostureCalculator postureCalculator;
    private final ObservationRegistry observationRegistry;
    private final DefaultIntegrityVerifier integrityVerifier;
    private final ToolCategoryCatalog categoryCatalog;
    private final ToolActivationCalculator activationCalculator;
    private final ThreadLocal<Boolean> skipPersist = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Map<String, ToolSpec> externalToolSpecs = new ConcurrentHashMap<>();
    private final Set<String> servedToolNames = ConcurrentHashMap.newKeySet();
    private final Map<String, String> externalToolRiskLevels = new ConcurrentHashMap<>();

    private ToolMcpServerSetting toolMcpServerSetting;
    private volatile ExposureMode exposureMode;

    public ToolSpecService(ObjectProvider<McpSyncServer> syncServerProvider,
            ObjectProvider<McpAsyncServer> asyncServerProvider, McpServerInfoService mcpServerInfoService,
            McpServerHitlToolGate hitlGate,
            ExternalClientToolCallObserver externalClientToolCallObserver,
            ObjectProvider<ToolSpecPersistenceService> toolSpecPersistenceServiceProvider,
            ObjectProvider<ToolIndexWarmup> toolIndexWarmupProvider,
            SpringAiPlaygroundOptions playgroundOptions, EffectivePolicyResolver policyResolver,
            SandboxPostureCalculator postureCalculator,
            ObservationRegistry observationRegistry,
            DefaultIntegrityVerifier integrityVerifier, ToolCategoryCatalog categoryCatalog,
            ToolWorkspace toolWorkspace, ToolActivationCalculator activationCalculator)
            throws ClassNotFoundException {
        this.mcpSyncServer = syncServerProvider.getIfAvailable();
        this.mcpAsyncServer = asyncServerProvider.getIfAvailable();
        this.mcpServerInfoService = mcpServerInfoService;
        this.hitlGate = hitlGate;
        this.externalClientToolCallObserver = externalClientToolCallObserver;
        this.toolSpecPersistenceServiceProvider = toolSpecPersistenceServiceProvider;
        this.toolIndexWarmupProvider = toolIndexWarmupProvider;
        this.toolMcpServerSetting = new ToolMcpServerSetting(true, Set.of());
        this.exposureMode = playgroundOptions.builtInMcpServer().exposureMode();
        this.toolIdSpecs = new ConcurrentHashMap<>();
        this.sandboxBaseline = playgroundOptions.toolStudio().jsSandbox();
        this.policyResolver = policyResolver;
        this.postureCalculator = postureCalculator;
        this.observationRegistry = observationRegistry;
        this.integrityVerifier = integrityVerifier;
        this.categoryCatalog = categoryCatalog;
        this.activationCalculator = activationCalculator;
        this.jsToolExecutor = new JsToolExecutor(playgroundOptions.toolStudio().timeoutSeconds(),
                this.sandboxBaseline, toolWorkspace.base());
    }

    public record ToolDraftInput(
            String toolId,
            String name,
            String description,
            List<Map.Entry<String, String>> staticVariables,
            List<ToolParamSpec> params,
            String code,
            String category,
            Set<String> tags,
            ToolSpec.SandboxOverrides sandboxOverrides,
            ToolManifest.HumanInTheLoop humanInTheLoop) {

        public ToolDraftInput {
            staticVariables = staticVariables == null ? List.of() : List.copyOf(staticVariables);
            params = params == null ? List.of() : List.copyOf(params);
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    public record ToolTestResult(JsExecutionResult result, long startedAtMs, long endedAtMs) {

        public boolean ok() {
            return this.result != null && this.result.isOk();
        }
    }

    public record ToolSaveResult(ToolSpec spec, ToolTestResult test) {

        public boolean saved() {
            return this.spec != null;
        }
    }

    public record ExposureSelection(boolean autoAdd, Set<String> customSelectedIds, Set<String> builtinSelectedIds) {

        public ExposureSelection {
            customSelectedIds = customSelectedIds == null ? Set.of() : Set.copyOf(customSelectedIds);
            builtinSelectedIds = builtinSelectedIds == null ? Set.of() : Set.copyOf(builtinSelectedIds);
        }
    }

    public static Optional<String> toolNameError(String toolName) {
        if (toolName == null || toolName.isBlank()) return Optional.of("Tool Name is required.");
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            return Optional.of("Use letters, numbers, and '_' only. Must start with a letter.");
        }
        if (ManagementToolNames.isReserved(toolName)) {
            return Optional.of("Names starting with '" + ManagementToolNames.PREFIX
                    + "' are reserved for this app's management tools.");
        }
        return Optional.empty();
    }

    public ToolTestResult testRun(ToolDraftInput input) {
        long start = System.currentTimeMillis();
        try {
            LinkedHashMap<String, Object> toolParams = new LinkedHashMap<>();
            List<String> declaredNames = new ArrayList<>();
            for (ToolParamSpec spec : input.params()) {
                if (spec.name() != null && !spec.name().isBlank()) declaredNames.add(spec.name());
                if (spec.testValue() == null || spec.testValue().isBlank()) continue;
                toolParams.put(spec.name(), convertTestValue(spec.testValue(), spec.type()));
            }
            String resolvedName = input.name() == null || input.name().isBlank() ? "draft" : input.name();
            JsExecutionResult result = executeTool(resolvedName, input.staticVariables(), input.code(), toolParams,
                    input.sandboxOverrides(), declaredNames);
            return new ToolTestResult(result, start, System.currentTimeMillis());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Exception: " + e.getMessage(), e);
        }
    }

    static Object convertTestValue(String testValue, JsonSchemaType type) {
        try {
            return switch (type) {
                case STRING -> testValue;
                case NUMBER -> Double.parseDouble(testValue);
                case INTEGER -> Long.parseLong(testValue);
                case BOOLEAN -> Boolean.parseBoolean(testValue);
                case ARRAY -> TEST_VALUE_MAPPER.readValue(testValue, new TypeReference<List<Object>>() { });
                case OBJECT -> TEST_VALUE_MAPPER.readValue(testValue, new TypeReference<Map<String, Object>>() { });
            };
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public ToolSaveResult saveDraft(ToolDraftInput input) {
        validateDraft(input);
        return new ToolSaveResult(persistDraft(input, true), null);
    }

    public ToolSaveResult testAndPublish(ToolDraftInput input) {
        validateDraft(input);
        ToolTestResult test = testRun(input);
        if (!test.ok()) return new ToolSaveResult(null, test);
        return new ToolSaveResult(persistDraft(input, false), test);
    }

    private void validateDraft(ToolDraftInput input) {
        toolNameError(input.name()).ifPresent(error -> {
            throw new IllegalArgumentException(error);
        });
        if (nameTakenByAnotherTool(input.toolId(), input.name())) {
            throw new IllegalStateException("A tool with this name already exists.");
        }
        if (!isDefaultTool(input.toolId()) && this.integrityVerifier.isReservedToolName(input.name())) {
            throw new IllegalArgumentException("'" + input.name()
                    + "' is the name of a built-in default tool. Choose a different name.");
        }
    }

    private boolean nameTakenByAnotherTool(String toolId, String toolName) {
        return this.toolIdSpecs.values().stream()
                .anyMatch(spec -> spec.name().equals(toolName) && !Objects.equals(spec.toolId(), toolId));
    }

    private ToolSpec persistDraft(ToolDraftInput input, boolean draftRequested) {
        ToolSpec existing = input.toolId() == null ? null : this.toolIdSpecs.get(input.toolId());
        boolean startedAsPublished = existing != null && !existing.draft();
        boolean nameChanged = existing != null && !Objects.equals(existing.name(), input.name());
        boolean targetDraft = draftRequested || (startedAsPublished && nameChanged);
        String toolId = input.toolId() != null ? input.toolId() : UUID.randomUUID().toString();
        ToolSpec formSpec = new ToolSpec(toolId, input.name(), input.description(), input.staticVariables(),
                input.params(), input.code(), ToolSpec.CodeType.Javascript, null)
                .withCategory(input.category())
                .withTags(input.tags())
                .withSandboxOverrides(input.sandboxOverrides())
                .withHumanInTheLoop(input.humanInTheLoop())
                .withDraft(targetDraft);
        return update(formSpec);
    }

    public Set<String> defaultToolIds() {
        ToolSpecPersistenceService persistenceService = this.toolSpecPersistenceServiceProvider.getIfAvailable();
        return persistenceService == null ? Set.of() : persistenceService.getDefaultToolIds();
    }

    public List<ToolSpec> customTools() {
        Set<String> defaultIds = defaultToolIds();
        return getToolSpecList().stream().filter(spec -> !defaultIds.contains(spec.toolId())).toList();
    }

    public List<ToolSpec> exposableBuiltins() {
        Set<String> defaultIds = defaultToolIds();
        return getToolSpecList().stream()
                .filter(spec -> defaultIds.contains(spec.toolId()))
                .filter(spec -> !spec.draft())
                .filter(spec -> this.activationCalculator.calculate(spec) != State.MISSING_REQUIREMENTS)
                .toList();
    }

    public enum ToolReadiness { READY, NOT_EXPOSED, NEEDS_SETUP, NOT_ENABLED }

    public record BuiltinEnvSnapshot(int totalTools, long blockedTools, long exposedBuiltins,
            Map<String, List<String>> toolsByVar, Set<String> missingVars) {

        public int totalVars() {
            return this.toolsByVar.size();
        }

        public int setVars() {
            return totalVars() - this.missingVars.size();
        }

        public long localPassed() {
            return this.totalTools - this.blockedTools;
        }
    }

    public record CustomToolSummary(int totalTools, int customTools, long needConfiguration) {}

    public BuiltinEnvSnapshot builtinEnvSnapshot() {
        ToolSpecPersistenceService persistenceService = this.toolSpecPersistenceServiceProvider.getIfAvailable();
        List<ToolSpec> defaults = persistenceService == null ? List.of() : persistenceService.getDefaultToolSpecs();
        Map<String, List<String>> toolsByVar = new TreeMap<>();
        Set<String> missingVars = new LinkedHashSet<>();
        long blockedTools = 0;
        for (ToolSpec spec : defaults) {
            List<String> missing = this.activationCalculator.missingEnvVars(spec);
            if (!missing.isEmpty()) blockedTools++;
            missingVars.addAll(missing);
            for (String envVar : this.activationCalculator.declaredEnvVars(spec)) {
                toolsByVar.computeIfAbsent(envVar, key -> new ArrayList<>()).add(spec.name());
            }
        }
        Set<String> defaultIds = defaultToolIds();
        long exposed = getToolMcpServerSetting().exposedToolIds().stream().filter(defaultIds::contains).count();
        return new BuiltinEnvSnapshot(defaults.size(), blockedTools, exposed, toolsByVar, missingVars);
    }

    public CustomToolSummary customToolSummary() {
        List<ToolSpec> all = getToolSpecList();
        List<ToolSpec> customs = customTools();
        long needConfiguration = customs.stream().filter(ToolSpecService::hasUnsetStaticVariable).count();
        return new CustomToolSummary(all.size(), customs.size(), needConfiguration);
    }

    private static boolean hasUnsetStaticVariable(ToolSpec spec) {
        if (spec.staticVariables() == null) return false;
        return spec.staticVariables().stream()
                .anyMatch(entry -> entry.getValue() == null || entry.getValue().isBlank());
    }

    public List<ToolSpec> defaultTools() {
        ToolSpecPersistenceService persistenceService = this.toolSpecPersistenceServiceProvider.getIfAvailable();
        if (persistenceService == null) return List.of();
        return persistenceService.getDefaultToolSpecs().stream().filter(spec -> spec.name() != null).toList();
    }

    public ToolReadiness readiness(String toolName) {
        return getToolSpecAsOpt(toolName).map(this::readiness).orElse(ToolReadiness.NOT_ENABLED);
    }

    public ToolReadiness readiness(ToolSpec spec) {
        if (this.activationCalculator.calculate(spec) != State.ACTIVE) return ToolReadiness.NEEDS_SETUP;
        return getToolMcpServerSetting().exposedToolIds().contains(spec.toolId())
                ? ToolReadiness.READY : ToolReadiness.NOT_EXPOSED;
    }

    public ToolMcpServerSetting updateExposure(ExposureSelection selection) {
        Set<String> exposed = new HashSet<>(selection.customSelectedIds());
        exposed.addAll(selection.builtinSelectedIds());
        Set<String> excluded = Stream.concat(exposableBuiltins().stream(),
                        customTools().stream().filter(spec -> !spec.draft()))
                .map(ToolSpec::toolId).filter(id -> !exposed.contains(id)).collect(Collectors.toSet());
        updateToolMcpServerSetting(new ToolMcpServerSetting(selection.autoAdd(), exposed, excluded));
        return getToolMcpServerSetting();
    }

    public void loadAll(Runnable loadAction) {
        this.skipPersist.set(Boolean.TRUE);
        try {
            loadAction.run();
        } finally {
            this.skipPersist.remove();
        }
    }

    private void persistAsync() {
        if (Boolean.TRUE.equals(this.skipPersist.get()))
            return;
        ToolSpecPersistenceService persistenceService =
                this.toolSpecPersistenceServiceProvider.getIfAvailable();
        if (persistenceService != null) {
            persistenceService.saveAsync();
        }
    }

    public ToolSpec update(ToolSpec toolSpec) {
        boolean targetDraft = toolSpec.draft();
        ToolSpec existing = toolSpec.toolId() == null ? null : this.toolIdSpecs.get(toolSpec.toolId());
        boolean metadataChanged = existing != null && !sameMetadata(existing, toolSpec);
        boolean approvalGateChanged = existing != null && !sameApprovalGate(existing, toolSpec);
        ToolSpec result = update(toolSpec.toolId(), toolSpec.name(), toolSpec.description(),
                toolSpec.staticVariables(), toolSpec.params(), toolSpec.code(), toolSpec.codeType());
        result.withCategory(toolSpec.category())
                .withTags(toolSpec.tags())
                .withSandboxOverrides(toolSpec.sandboxOverrides())
                .withHumanInTheLoop(toolSpec.humanInTheLoop())
                .withDraft(targetDraft);
        if (result == existing && metadataChanged) persistAsync();
        syncMcpExposureForDraft(result, targetDraft);
        if (!targetDraft && !Boolean.TRUE.equals(this.skipPersist.get())
                && this.toolMcpServerSetting.exposedToolIds().contains(result.toolId())
                && (approvalGateChanged || !isServedOnMcp(result.name()))) {
            reRegisterMcpTool(result);
        }
        refreshToolIndex();
        return result;
    }

    private static boolean sameApprovalGate(ToolSpec left, ToolSpec right) {
        return Objects.equals(left.humanInTheLoop(), right.humanInTheLoop())
                && Objects.equals(left.sandboxOverrides(), right.sandboxOverrides());
    }

    private boolean isServedOnMcp(String toolName) {
        return getMcpToolList().stream().anyMatch(tool -> tool.name().equals(toolName));
    }

    private void reRegisterMcpTool(ToolSpec spec) {
        if (isServedOnMcp(spec.name())) removeMcpTool(spec.name());
        addMcpTool(spec);
    }

    private static boolean sameMetadata(ToolSpec left, ToolSpec right) {
        return Objects.equals(left.category(), right.category()) && Objects.equals(left.tags(), right.tags())
                && Objects.equals(left.sandboxOverrides(), right.sandboxOverrides())
                && Objects.equals(left.humanInTheLoop(), right.humanInTheLoop()) && left.draft() == right.draft();
    }

    private void refreshToolIndex() {
        if (Boolean.TRUE.equals(this.skipPersist.get()))
            return;
        ToolIndexWarmup warmup = this.toolIndexWarmupProvider.getIfAvailable();
        if (warmup != null) {
            warmup.reindexPublishedTools();
        }
    }

    private void syncMcpExposureForDraft(ToolSpec spec, boolean targetDraft) {
        boolean currentlyExposed = getMcpToolList().stream()
                .anyMatch(tool -> tool.name().equals(spec.name()));
        Set<String> excluded = this.toolMcpServerSetting.excludedToolIds();
        boolean changed = false;
        if (targetDraft) {
            if (currentlyExposed) {
                removeMcpTool(spec.name());
            }
            if (this.toolMcpServerSetting.exposedToolIds().contains(spec.toolId())) {
                HashSet<String> ids = new HashSet<>(this.toolMcpServerSetting.exposedToolIds());
                ids.remove(spec.toolId());
                this.toolMcpServerSetting = new ToolMcpServerSetting(
                        this.toolMcpServerSetting.autoAdd(), ids, excluded);
                changed = true;
            }
        } else if (!currentlyExposed
                && this.toolMcpServerSetting.autoAdd()
                && !excluded.contains(spec.toolId())
                && !isDefaultTool(spec.toolId())) {
            addMcpTool(spec);
            HashSet<String> ids = new HashSet<>(this.toolMcpServerSetting.exposedToolIds());
            ids.add(spec.toolId());
            this.toolMcpServerSetting = new ToolMcpServerSetting(true, ids, excluded);
            changed = true;
        }
        if (changed) {
            persistAsync();
        }
    }

    private boolean isDefaultTool(String toolId) {
        ToolSpecPersistenceService persistenceService =
                this.toolSpecPersistenceServiceProvider.getIfAvailable();
        return toolId != null && persistenceService != null
                && persistenceService.getDefaultToolIds().contains(toolId);
    }

    public ToolSpec update(String toolId, String toolName, String toolDescription,
            List<Map.Entry<String, String>> staticVariables, List<ToolParamSpec> toolParamSpecs, String jsCode,
            ToolSpec.CodeType codeType) {
        ManagementToolNames.requireNotReserved("Tool name", toolName);
        boolean isNew = Objects.isNull(toolId);
        ToolSpec toolSpec = isNew ? null : toolIdSpecs.get(toolId);
        if (Objects.isNull(toolSpec) || !Objects.equals(toolSpec.name(), toolName) ||
                !Objects.equals(toolSpec.description(), toolDescription) ||
                !Objects.equals(toolSpec.staticVariables().toString(),
                        staticVariables.toString()) || !Objects.equals(toolSpec.params().toString(),
                toolParamSpecs.toString()) || !Objects.equals(toolSpec.code(), jsCode) ||
                !Objects.equals(toolSpec.codeType(), codeType)) {
            return update(toolId, toolName, toolDescription, staticVariables, toolParamSpecs, jsCode, codeType,
                    toolSpec);
        }
        return toolSpec;
    }

    private ToolSpec update(String toolId, String toolName, String toolDescription,
            List<Map.Entry<String, String>> staticVariables, List<ToolParamSpec> toolParamSpecs, String jsCode,
            ToolSpec.CodeType codeType, ToolSpec toolSpec) {
        List<String> declaredParamNames = new ArrayList<>();
        if (toolParamSpecs != null) {
            for (ToolParamSpec spec : toolParamSpecs) {
                if (spec != null && spec.name() != null && !spec.name().isBlank()) {
                    declaredParamNames.add(spec.name());
                }
            }
        }
        Function<Map<String, Object>, Object> executor = toolParams -> {
            ToolSpec current = this.toolIdSpecs.get(toolId);
            ToolSpec.SandboxOverrides overrides = current == null ? null : current.sandboxOverrides();
            Map<String, Object> filled = new HashMap<>();
            if (toolParams != null) {
                for (Map.Entry<String, Object> entry : toolParams.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) continue;
                    if (value instanceof String s && s.isBlank()) continue;
                    filled.put(entry.getKey(), value);
                }
            }
            JsExecutionResult result = executeTool(toolName, staticVariables, jsCode, filled, overrides,
                    declaredParamNames);
            if (!result.isOk()) {
                String message = result.error() == null || result.error().isBlank()
                        ? "tool " + toolName + " failed" : result.error();
                throw new ToolExecutionException(
                        DefaultToolDefinition.builder().name(toolName).description(toolDescription).inputSchema("{}").build(),
                        new RuntimeException(message));
            }
            return result.result();
        };
        List<ToolParamSpec> safeParamSpecs = toolParamSpecs == null ? List.of() : toolParamSpecs;
        FunctionToolCallback.Builder<Map<String, Object>, Object> callbackBuilder =
                FunctionToolCallback.builder(toolName, executor).description(toolDescription)
                        .inputSchema(toJsonSchema(safeParamSpecs).toPrettyString())
                        .inputType(MAP_PARAMETERIZED_TYPE_REFERENCE);
        if (jsCode != null && jsCode.contains("```saip-action")) {
            callbackBuilder.toolCallResultConverter((output, returnType) -> output == null ? "" : output.toString());
        }
        ToolSpec newToolSpec =
                new ToolSpec(toolId, toolName, toolDescription, staticVariables, safeParamSpecs, jsCode, codeType,
                        callbackBuilder.build());
        toolIdSpecs.put(toolId, newToolSpec);
        logger.info("Update Tool spec: toolId={}, name={}", toolId, toolName);
        if (Objects.nonNull(toolSpec))
            getMcpToolList().stream().filter(tool -> tool.name().equals(toolSpec.name())).findFirst()
                    .map(McpSchema.Tool::name).ifPresent(this::removeMcpTool);
        persistAsync();
        return newToolSpec;
    }

    public void addMcpTool(ToolSpec toolSpec) {
        if (!this.exposureMode.includesBuiltin()) {
            return;
        }
        if (this.integrityVerifier.verifyTool(toolSpec).isBlocked()) {
            return;
        }
        logger.info("Adding MCP tool to server: name={}", toolSpec.name());
        RiskLevel riskLevel = RiskLevel.valueOf(riskLevelOf(toolSpec));
        if (Objects.nonNull(this.mcpSyncServer)) {
            this.mcpSyncServer.addTool(this.hitlGate.decorate(this.externalClientToolCallObserver.observe(
                    McpToolUtils.toSyncToolSpecification(toolSpec.toolCallback()), toolSpec.toolCallback()),
                    toolSpec.humanInTheLoop(), riskLevel));
        } else {
            this.mcpAsyncServer.addTool(this.hitlGate.decorate(this.externalClientToolCallObserver.observe(
                    McpToolUtils.toAsyncToolSpecification(toolSpec.toolCallback()), toolSpec.toolCallback()),
                    toolSpec.humanInTheLoop(), riskLevel));
        }
        this.mcpServerInfoService.updateDefaultMcpTool();
    }

    public void removeMcpTool(String toolName) {
        logger.info("Removing MCP tool from server: name={}", toolName);
        if (Objects.nonNull(this.mcpSyncServer)) {
            this.mcpSyncServer.removeTool(toolName);
        } else {
            this.mcpAsyncServer.removeTool(toolName);
        }
    }

    public List<McpSchema.Tool> getMcpToolList() {
        return Objects.nonNull(this.mcpSyncServer) ? this.mcpSyncServer.listTools() : this.mcpAsyncServer.listTools()
                .toStream().toList();
    }

    public void addExternalMcpTool(ToolCallback callback, boolean hitl) {
        String name = callback.getToolDefinition().name();
        if (this.externalToolSpecs.containsKey(name)) return;
        if (this.integrityVerifier.isReservedToolName(name)) {
            logger.warn("Refusing to expose external MCP tool '{}' — name is a reserved built-in default "
                    + "(impersonation guard)", name);
            return;
        }
        logger.info("Adding external MCP tool to built-in server: name={}, hitl={}", name, hitl);
        ToolSpec externalSpec = new ToolSpec(name, name, null, null, null, null, null, callback)
                .withHumanInTheLoop(hitl ? REQUIRED_HITL : null);
        RiskLevel externalLevel = callback instanceof WrappedExternalToolCallback wrapped
                ? wrapped.riskLevel() : null;
        try {
            if (Objects.nonNull(this.mcpSyncServer)) {
                SyncToolSpecification spec = this.externalClientToolCallObserver.observe(
                        McpToolUtils.toSyncToolSpecification(callback), callback);
                this.mcpSyncServer.addTool(hitl ? this.hitlGate.decorate(spec, REQUIRED_HITL, externalLevel) : spec);
            } else if (Objects.nonNull(this.mcpAsyncServer)) {
                AsyncToolSpecification spec = this.externalClientToolCallObserver.observe(
                        McpToolUtils.toAsyncToolSpecification(callback), callback);
                this.mcpAsyncServer.addTool(hitl ? this.hitlGate.decorate(spec, REQUIRED_HITL, externalLevel) : spec);
            }
            this.externalToolSpecs.put(name, externalSpec);
            if (externalLevel != null) {
                this.externalToolRiskLevels.put(name, externalLevel.name());
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to add external MCP tool: name={}, error={}", name, e.getMessage());
        }
    }

    public void addServedTool(SyncToolSpecification specification) {
        addServedTools(List.of(specification));
    }

    public void addServedTools(List<SyncToolSpecification> specifications) {
        boolean added = false;
        for (SyncToolSpecification specification : specifications) {
            String name = specification.tool().name();
            if (Objects.nonNull(this.mcpSyncServer)) {
                this.mcpSyncServer.addTool(specification);
            } else if (Objects.nonNull(this.mcpAsyncServer)) {
                this.mcpAsyncServer.addTool(AsyncToolSpecification.builder().tool(specification.tool())
                        .callHandler((exchange, request) -> Mono.fromCallable(
                                () -> specification.callHandler().apply(null, request)))
                        .build());
            } else {
                continue;
            }
            this.servedToolNames.add(name);
            added = true;
            logger.info("Added served tool to server: name={}", name);
        }
        if (added) {
            this.mcpServerInfoService.updateDefaultMcpTool();
        }
    }

    public void removeServedTools(Set<String> names) {
        boolean removed = false;
        for (String name : names) {
            if (!this.servedToolNames.remove(name)) {
                continue;
            }
            if (Objects.nonNull(this.mcpSyncServer)) {
                this.mcpSyncServer.removeTool(name);
            } else if (Objects.nonNull(this.mcpAsyncServer)) {
                this.mcpAsyncServer.removeTool(name);
            }
            removed = true;
            logger.info("Removed served tool from server: name={}", name);
        }
        if (removed) {
            this.mcpServerInfoService.updateDefaultMcpTool();
        }
    }

    public Set<String> getServedToolNames() {
        return Set.copyOf(this.servedToolNames);
    }

    public void removeExternalMcpTool(String toolName) {
        logger.info("Removing external MCP tool from built-in server: name={}", toolName);
        try {
            if (Objects.nonNull(this.mcpSyncServer)) {
                this.mcpSyncServer.removeTool(toolName);
            } else if (Objects.nonNull(this.mcpAsyncServer)) {
                this.mcpAsyncServer.removeTool(toolName);
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to remove external MCP tool: name={}, error={}", toolName, e.getMessage());
        }
        this.externalToolSpecs.remove(toolName);
        this.externalToolRiskLevels.remove(toolName);
    }

    public Set<String> getExternalMcpToolNames() {
        return Set.copyOf(this.externalToolSpecs.keySet());
    }

    public List<ToolSpec> getExternalToolSpecs() {
        return List.copyOf(this.externalToolSpecs.values());
    }

    public Optional<ToolDefinition> toolDefinitionOf(String toolName) {
        if (toolName == null) return Optional.empty();
        return Optional.ofNullable(specForGating(toolName)).map(ToolSpec::toolCallback)
                .map(ToolCallback::getToolDefinition);
    }

    public Optional<WrappedExternalToolCallback> proxiedToolCallback(String toolName) {
        if (toolName == null) return Optional.empty();
        return Optional.ofNullable(this.externalToolSpecs.get(toolName)).map(ToolSpec::toolCallback)
                .filter(WrappedExternalToolCallback.class::isInstance).map(WrappedExternalToolCallback.class::cast);
    }

    public Optional<ToolSpec> getToolSpecAsOpt(String name) {
        return toolIdSpecs.values().stream().filter(toolSpec -> toolSpec.name().equals(name)).findFirst();
    }

    public ObjectNode toJsonSchema(List<ToolParamSpec> toolParamSpecs) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode requiredArray = schema.putArray("required");

        for (ToolParamSpec param : toolParamSpecs) {
            ObjectNode paramNode = properties.putObject(param.name());
            paramNode.put("type", param.type().getValue());
            if (StringUtils.hasText(param.description())) {
                paramNode.put("description", param.description());
            }
            if (param.required()) {
                requiredArray.add(param.name());
            }
        }
        return schema;
    }

    public JsExecutionResult executeTool(String toolName, List<Map.Entry<String, String>> staticVariables,
            String jsCode, Map<String, Object> toolParams) {
        return executeTool(toolName, staticVariables, jsCode, toolParams, null, List.of());
    }

    public JsExecutionResult executeTool(String toolName, List<Map.Entry<String, String>> staticVariables,
            String jsCode, Map<String, Object> toolParams, ToolSpec.SandboxOverrides sandboxOverrides) {
        return executeTool(toolName, staticVariables, jsCode, toolParams, sandboxOverrides, List.of());
    }

    public JsExecutionResult executeTool(String toolName, List<Map.Entry<String, String>> staticVariables,
            String jsCode, Map<String, Object> toolParams, ToolSpec.SandboxOverrides sandboxOverrides,
            Collection<String> declaredParamNames) {
        Map<String, Object> mergeParams = new HashMap<>(toolParams);
        staticVariables.forEach(entry -> mergeParams.put(entry.getKey(), entry.getValue()));
        LinkedHashSet<String> mergedDeclaredNames = new LinkedHashSet<>();
        if (declaredParamNames != null) mergedDeclaredNames.addAll(declaredParamNames);
        staticVariables.forEach(entry -> {
            if (entry.getKey() != null && !entry.getKey().isBlank()) mergedDeclaredNames.add(entry.getKey());
        });
        JsExecutionParams jsExecutionParams = new JsExecutionParams(mergeParams, jsCode, mergedDeclaredNames);
        EffectivePolicy policy = this.policyResolver.resolve(this.sandboxBaseline, null,
                toResolverOverrides(sandboxOverrides));
        java.nio.file.Path overrideBase = sandboxOverrides == null
                || sandboxOverrides.fsBasePath() == null || sandboxOverrides.fsBasePath().isBlank()
                ? null
                : java.nio.file.Paths.get(sandboxOverrides.fsBasePath()).toAbsolutePath().normalize();
        String conversationId = MDC.get(MdcIdentityFilter.CONVERSATION_ID);

        String cid = UUID.randomUUID().toString().substring(0, 8);
        Set<String> secretKeys = staticVariables.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        Map<String, Object> safeParams = maskSecrets(mergeParams, secretKeys);
        String level = riskLevelFor(sandboxOverrides);
        String caps = capabilitySummary(sandboxOverrides);
        Set<String> hosts = sandboxOverrides == null || sandboxOverrides.hostsAllow() == null
                ? Set.of() : sandboxOverrides.hostsAllow();

        logger.info("tool.exec.start cid={} tool={} level={} caps={} hosts={} fsBase={} params={}",
                cid, toolName, level, caps, hosts,
                overrideBase == null ? "-" : overrideBase, safeParams);

        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) {
            current.lowCardinalityKeyValue("sandbox.level", level);
            current.highCardinalityKeyValue("sandbox.caps", caps);
            current.highCardinalityKeyValue("sandbox.fs.base",
                    overrideBase == null ? "" : overrideBase.toString());
            current.highCardinalityKeyValue("sandbox.hosts.count", String.valueOf(hosts.size()));
            current.highCardinalityKeyValue("sandbox.cid", cid);
        }

        long startNs = System.nanoTime();
        JsExecutionResult jsExecutionResult;
        try {
            jsExecutionResult = this.jsToolExecutor.execute(jsExecutionParams, policy, overrideBase,
                    conversationId);
        } catch (RuntimeException e) {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.warn("tool.exec.crash cid={} tool={} level={} durationMs={} error={}",
                    cid, toolName, level, durMs, e.getMessage());
            throw e;
        }
        long durMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (jsExecutionResult.isOk()) {
            logger.info("tool.exec.done cid={} tool={} level={} durationMs={} ok=true", cid, toolName, level, durMs);
        } else {
            logger.warn("tool.exec.done cid={} tool={} level={} durationMs={} ok=false error={}",
                    cid, toolName, level, durMs, jsExecutionResult.error());
        }
        logger.debug("tool.exec.result cid={} result={}", cid, jsExecutionResult);
        return jsExecutionResult;
    }

    public String riskLevelOf(ToolSpec spec) {
        if (spec != null) {
            String external = this.externalToolRiskLevels.get(spec.name());
            if (external != null) return external;
        }
        return riskLevelFor(spec == null ? null : spec.sandboxOverrides());
    }

    public String categoryOf(ToolSpec spec) {
        return this.categoryCatalog.resolveOrFallback(spec == null ? null : spec.category()).displayName();
    }

    public boolean requiresApproval(ToolSpec spec) {
        if (spec == null) return false;
        ToolManifest.HumanInTheLoop hitl = spec.humanInTheLoop();
        return hitl != null && hitl.mode() == ToolManifest.HumanInTheLoop.Mode.REQUIRED;
    }

    public boolean requiresApproval(String toolName) {
        return requiresApproval(specForGating(toolName));
    }

    public ToolManifest.HumanInTheLoop humanInTheLoopFor(String toolName) {
        ToolSpec spec = specForGating(toolName);
        return spec == null ? null : spec.humanInTheLoop();
    }

    public RiskLevel gatingRiskLevel(String toolName) {
        ToolSpec spec = specForGating(toolName);
        return spec == null ? null : RiskLevel.valueOf(riskLevelOf(spec));
    }

    private ToolSpec specForGating(String toolName) {
        return getToolSpecAsOpt(toolName).orElseGet(() -> this.externalToolSpecs.get(toolName));
    }

    private String riskLevelFor(ToolSpec.SandboxOverrides sbo) {
        if (sbo == null) return "L0";
        Set<String> baselineDeny = this.sandboxBaseline.denyClasses() == null
                ? Set.of() : this.sandboxBaseline.denyClasses();
        Set<String> baselineAllow = this.sandboxBaseline.allowClasses() == null
                ? Set.of() : this.sandboxBaseline.allowClasses();
        Set<String> effectiveDeny = new HashSet<>(baselineDeny);
        if (sbo.removeDenyClasses() != null) effectiveDeny.removeAll(sbo.removeDenyClasses());
        if (sbo.addDenyClasses() != null) effectiveDeny.addAll(sbo.addDenyClasses());
        Set<String> effectiveAllow = new HashSet<>(baselineAllow);
        if (sbo.removeAllowClasses() != null) effectiveAllow.removeAll(sbo.removeAllowClasses());
        if (sbo.addAllowClasses() != null) effectiveAllow.addAll(sbo.addAllowClasses());
        return this.postureCalculator.compute(new SandboxPostureCalculator.Inputs(
                sbo.networkMode(),
                sbo.hostsAllow() == null ? Set.of() : sbo.hostsAllow(),
                Boolean.TRUE.equals(sbo.fileRead()),
                Boolean.TRUE.equals(sbo.fileWrite()),
                Boolean.TRUE.equals(sbo.destructive()),
                baselineDeny,
                effectiveDeny,
                baselineAllow,
                effectiveAllow)).name();
    }

    private static String capabilitySummary(ToolSpec.SandboxOverrides sbo) {
        if (sbo == null) return "-";
        StringBuilder b = new StringBuilder();
        if (Boolean.TRUE.equals(sbo.fileRead()))  b.append("R");
        if (Boolean.TRUE.equals(sbo.fileWrite())) b.append("W");
        String net = sbo.networkMode();
        if (net != null && !net.isBlank() && !"blocked".equalsIgnoreCase(net)) {
            if (!b.isEmpty()) b.append("+");
            b.append("net:").append(net);
        }
        return b.isEmpty() ? "none" : b.toString();
    }

    static Map<String, Object> maskSecrets(Map<String, Object> merged, Set<String> secretKeys) {
        if (merged == null || merged.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(merged.size());
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            if (secretKeys.contains(e.getKey())) {
                Object v = e.getValue();
                if (v == null) {
                    out.put(e.getKey(), null);
                } else {
                    String s = v.toString();
                    out.put(e.getKey(), s.length() <= 4 ? "***" : "***" + s.substring(s.length() - 2));
                }
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Overrides toResolverOverrides(ToolSpec.SandboxOverrides spec) {
        if (spec == null) return Overrides.empty();
        return new Overrides(spec.addAllowClasses(), spec.removeAllowClasses(),
                spec.addDenyClasses(), spec.removeDenyClasses(),
                mapNetworkPolicy(spec.networkMode(), spec.hostsAllow()),
                deriveNetworkIo(spec.networkMode()),
                spec.fileRead(), spec.fileWrite());
    }

    private static NetworkPolicy mapNetworkPolicy(String mode, Set<String> hostsAllow) {
        if (mode == null || mode.isBlank()) return null;
        String egress = switch (mode.toLowerCase()) {
            case "blocked" -> "blocked";
            case "allowlist" -> "allowlist";
            case "strict" -> "strict";
            case "open" -> "permissive";
            default -> null;
        };
        if (egress == null) return null;
        return new NetworkPolicy(egress, hostsAllow == null ? Set.of() : hostsAllow, Set.of());
    }

    private static Boolean deriveNetworkIo(String mode) {
        if (mode == null || mode.isBlank()) return null;
        return !"blocked".equalsIgnoreCase(mode);
    }

    public List<ToolSpec> getToolSpecList() {
        return toolIdSpecs.values().stream().sorted(Comparator.comparingLong(ToolSpec::updateTimestamp).reversed())
                .toList();
    }

    public void deleteToolSpec(String toolId) {
        logger.info("Deleting tool spec: toolId={}", toolId);
        Optional.ofNullable(toolIdSpecs.remove(toolId)).map(ToolSpec::name).ifPresent(this::removeMcpTool);
        persistAsync();
        refreshToolIndex();
    }

    public ToolMcpServerSetting getToolMcpServerSetting() {
        return toolMcpServerSetting;
    }

    public void setToolMcpServerSetting(ToolMcpServerSetting toolMcpServerSetting) {
        this.toolMcpServerSetting = toolMcpServerSetting;
    }

    public void updateToolMcpServerSetting(ToolMcpServerSetting toolMcpServerSetting) {
        logger.info("Updating Tool MCP server setting: autoAdd={}, exposedToolCount={}",
                toolMcpServerSetting.autoAdd(), toolMcpServerSetting.exposedToolIds().size());
        setToolMcpServerSetting(toolMcpServerSetting);
        reconcileNativeExposure();
        persistAsync();
    }

    public ExposureMode exposureMode() {
        return this.exposureMode;
    }

    public synchronized void setExposureMode(ExposureMode mode) {
        ExposureMode next = mode == null ? ExposureMode.BOTH : mode;
        if (this.exposureMode == next) return;
        this.exposureMode = next;
        reconcileNativeExposure();
    }

    public synchronized void reconcileNativeExposure() {
        Map<String, ToolSpec> desiredSpecs = new LinkedHashMap<>();
        if (this.exposureMode.includesBuiltin()) {
            this.toolMcpServerSetting.exposedToolIds().stream().map(this.toolIdSpecs::get)
                    .filter(Objects::nonNull).filter(spec -> !spec.draft())
                    .filter(spec -> !this.integrityVerifier.verifyTool(spec).isBlocked())
                    .forEach(spec -> desiredSpecs.putIfAbsent(spec.name(), spec));
        }
        Set<String> desired = desiredSpecs.keySet();
        Set<String> external = new HashSet<>(getExternalMcpToolNames());
        external.addAll(this.servedToolNames);
        Set<String> currentNative = getMcpToolList().stream().map(McpSchema.Tool::name)
                .filter(name -> !external.contains(name)).collect(Collectors.toSet());
        currentNative.stream().filter(name -> !desired.contains(name)).forEach(this::removeMcpTool);
        desiredSpecs.values().stream().filter(spec -> !currentNative.contains(spec.name()))
                .forEach(this::addMcpTool);
        refreshDefaultMcpTool();
        logger.info("Native exposure reconciled: mode={}, exposedNativeCount={}", this.exposureMode, desired.size());
    }

    public void refreshDefaultMcpTool() {
        this.mcpServerInfoService.updateDefaultMcpTool();
    }

    @EventListener(ContextClosedEvent.class)
    public void shutdownMcpServers() {
        logger.info("Shutting down MCP servers...");
        if (Objects.nonNull(this.mcpSyncServer)) {
            logger.info("Closing McpSyncServer");
            this.mcpSyncServer.close();
        }
        if (Objects.nonNull(this.mcpAsyncServer)) {
            logger.info("Closing McpAsyncServer");
            this.mcpAsyncServer.close();
        }
    }

}
