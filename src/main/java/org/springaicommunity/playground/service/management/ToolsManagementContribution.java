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
package org.springaicommunity.playground.service.management;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.service.tool.ToolManifest.HumanInTheLoop;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.SandboxOverrides;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureSelection;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolDraftInput;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolReadiness;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolSaveResult;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolTestResult;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator.Explanation;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator.Reason;
import org.springaicommunity.playground.service.tool.runtime.JsRuntimeGlobals;
import org.springaicommunity.playground.service.tool.runtime.JsToolExecutor.JsExecutionResult;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ToolsManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "tools";

    private static final int LIMIT_DEFAULT = 50;
    private static final int LIMIT_MAX = 200;
    private static final int DESCRIPTION_LIMIT = 160;
    private static final int RESULT_LIMIT = 8_000;
    private static final int CONSOLE_LIMIT = 4_000;
    private static final int SUMMARY_NAMES = 10;
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final JsonMapper RESULT_MAPPER = JsonMapper.builder().build();
    private static final String DRAFT_NOTE = "Drafts are saved on this app but are not visible to MCP clients.";

    private final ToolSpecService toolSpecService;
    private final ToolCategoryCatalog categoryCatalog;
    private final SpringAiPlaygroundOptions options;

    public ToolsManagementContribution(ToolSpecService toolSpecService, ToolCategoryCatalog categoryCatalog,
            SpringAiPlaygroundOptions options) {
        this.toolSpecService = toolSpecService;
        this.categoryCatalog = categoryCatalog;
        this.options = options;
    }

    public enum Scope {
        CUSTOM, BUILTIN, ALL
    }

    public enum Status {
        DRAFT, PUBLISHED
    }

    public enum Origin {
        CUSTOM, BUILTIN
    }

    public enum NetworkMode {
        BLOCKED, ALLOWLIST, STRICT, OPEN
    }

    public record NoInput() {
    }

    public record ListInput(
            @Nullable @JsonPropertyDescription("CUSTOM (default): tools people or AI created, BUILTIN: the bundled catalog, ALL.") Scope scope,
            @Nullable @JsonPropertyDescription("Only DRAFT or only PUBLISHED tools.") Status status,
            @Nullable @JsonPropertyDescription("Only tools whose name, description or tags contain this text.") String query,
            @Nullable @JsonPropertyDescription("Only tools of this category id, for example WEB or DATA.") String category,
            @Nullable @JsonPropertyDescription("Maximum tools, default 50, at most 200.") Integer limit) {
    }

    public record NameInput(@JsonPropertyDescription("Tool name.") String name) {
    }

    public record ParamSpec(
            @JsonPropertyDescription("Parameter name. It becomes a global variable in the code, so use a plain identifier such as amount or fromCurrency.") String name,
            @Nullable @JsonPropertyDescription("What the parameter means and an example value. The model calling the tool reads it.") String description,
            @Nullable @JsonPropertyDescription("Whether the caller must pass it. Default true.") Boolean required,
            @Nullable @JsonPropertyDescription("STRING (default), NUMBER, INTEGER, BOOLEAN, ARRAY or OBJECT.") JsonSchemaType type,
            @Nullable @JsonPropertyDescription("Sample value used by saip_tools_test: a string, number, boolean, or a JSON array or object. Give one for every parameter the code needs.") Object testValue) {
    }

    public record VariableSpec(
            @JsonPropertyDescription("Variable name, available as a global in the code.") String name,
            @JsonPropertyDescription("Fixed value. Secrets must be an environment variable reference such as ${API_KEY}, never the secret itself.") String value) {
    }

    public record SandboxSpec(
            @Nullable @JsonPropertyDescription("Network access: BLOCKED (default, no fetch), ALLOWLIST (only hostsAllow, risk L3, use it whenever the hosts are known), STRICT (any public host, L3, only when the host comes from the caller at run time; private and local addresses stay blocked) or OPEN (any host, L4, needs approval).") NetworkMode networkMode,
            @Nullable @JsonPropertyDescription("With ALLOWLIST: the exact host names the code may call, for example [\"open.er-api.com\"]. Avoid '*'.") Set<String> hostsAllow,
            @Nullable @JsonPropertyDescription("Allow reading files (risk L3).") Boolean fileRead,
            @Nullable @JsonPropertyDescription("Allow writing files in the tool workspace (risk L4, needs approval).") Boolean fileWrite,
            @Nullable @JsonPropertyDescription("Absolute directory that file access is limited to. Leave out for the default workspace.") String fsBasePath,
            @Nullable @JsonPropertyDescription("Declare that the tool changes or deletes things outside itself (risk L5, needs approval).") Boolean destructive,
            @Nullable @JsonPropertyDescription("Extra Java classes to allow (needs approval). Almost never needed, the runtime helpers cover it.") Set<String> addAllowClasses) {
    }

    public record DraftInput(
            @JsonPropertyDescription("Tool name: letters, digits and _, starting with a letter. Must not start with saip_.") String name,
            @JsonPropertyDescription("What the tool does and when to use it. Written for the model that will call it.") String description,
            @Nullable @JsonPropertyDescription("Input parameters. Each one becomes a global variable in the code.") List<ParamSpec> params,
            @Nullable @JsonPropertyDescription("Fixed values, for example API keys as ${ENV_NAME} references.") List<VariableSpec> staticVariables,
            @JsonPropertyDescription("JavaScript body of the tool. Read the guide saip://guide/tool-authoring first.") String code,
            @Nullable @JsonPropertyDescription("Category id: TEXT, DATA, DATETIME, MATH, ENCODING, CRYPTO, SECURITY, FILE, WEB, PRODUCTIVITY, MESSAGING, AI or CUSTOM.") String category,
            @Nullable @JsonPropertyDescription("Free tags, at most two are useful.") Set<String> tags,
            @Nullable @JsonPropertyDescription("Sandbox capabilities. Leave out for the locked default (risk L0). Ask for the least that works.") SandboxSpec sandbox,
            @Nullable @JsonPropertyDescription("Whether a person must approve every call of the tool. Leave out for the default: true when the risk level is above L0, false at L0. Setting false above L0 removes that safeguard and is stressed when a person approves publishing.") Boolean humanApproval) {
    }

    public record TestInput(
            @Nullable @JsonPropertyDescription("Name of a saved draft to test. Give this or draft.") String name,
            @Nullable @JsonPropertyDescription("A tool definition to test without saving it: a nested object with the same fields as saip_tools_save_draft, never a string holding JSON. Give this or name.") DraftInput draft) {
    }

    public record SetExposureInput(
            @Nullable @JsonPropertyDescription("Expose newly published tools automatically. Keeps the current value when omitted.") Boolean autoAdd,
            @Nullable @JsonPropertyDescription("Names of published tools to start exposing, custom or bundled. Tools that are already exposed stay exposed.") List<String> expose,
            @Nullable @JsonPropertyDescription("Names of tools to stop exposing. Everything not listed here or in expose stays as it is.") List<String> stopExposing) {
    }

    public record CheckRiskInput(
            @Nullable @JsonPropertyDescription("Sandbox capabilities to evaluate. Leave out for the locked default.") SandboxSpec sandbox,
            @Nullable @JsonPropertyDescription("Optional JavaScript to scan for capabilities it uses but the sandbox does not grant.") String code) {
    }

    public record ToolSummary(String name, String description, Origin origin, boolean draft, ToolReadiness readiness,
                              String riskLevel, String category, List<String> tags, boolean exposed,
                              boolean humanApproval) {
    }

    public record ListData(List<ToolSummary> tools, int total, boolean truncated) {
    }

    public record ToolDetail(String name, String description, Origin origin, boolean draft, ToolReadiness readiness,
                             String riskLevel, String category, List<String> tags, boolean exposed,
                             boolean humanApproval, String code, List<ParamSpec> params,
                             List<VariableSpec> staticVariables, SandboxSpec sandbox) {
    }

    public record DraftSavedData(String name, boolean draft, String riskLevel, boolean elevated,
                                 boolean humanApproval, boolean replacedDraft, List<String> next) {
    }

    public record TestData(boolean localPass, long durationMs, String result, String error, String console,
                           String riskLevel, boolean elevated, List<String> testedParams,
                           List<String> skippedParams, List<String> hints, List<String> next) {

        TestData withNext(List<String> next) {
            return new TestData(this.localPass, this.durationMs, this.result, this.error, this.console,
                    this.riskLevel, this.elevated, this.testedParams, this.skippedParams, this.hints, next);
        }
    }

    public record PublishData(String name, boolean published, boolean exposed, boolean humanApproval,
                              String riskLevel, String note, TestData test) {
    }

    public record DeleteData(String name, boolean wasPublished, boolean wasExposed) {
    }

    public record ExposureData(boolean autoAdd, ExposureMode mode, List<String> customExposed,
                               List<String> customNotExposed, List<String> builtinExposed, int builtinAvailable) {
    }

    public record ReasonData(String setting, String raisesTo, String why) {
    }

    public record RiskData(String riskLevel, List<ReasonData> reasons, List<String> suggestions, boolean elevated,
                           boolean humanApprovalByDefault, String testApproval, String publishApproval,
                           List<String> warnings) {
    }

    private record ExposurePlan(ExposureSelection selection, boolean autoAdd, List<String> added,
                                List<String> removed, boolean highRisk) {

        boolean unchanged(boolean beforeAutoAdd) {
            return this.added.isEmpty() && this.removed.isEmpty() && this.autoAdd == beforeAutoAdd;
        }
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(list(), get(), saveDraft(), test(), publish(), delete(), getExposure(), setExposure(),
                checkRisk());
    }

    private ManagementTool list() {
        return ManagementTool.named("saip_tools_list", CATEGORY, ListInput.class).data(ListData.class)
                .description("List Tool Studio tools with state, risk level and whether each is exposed on the "
                        + "built-in MCP server. Shows custom tools by default. Use it to avoid name clashes.")
                .idempotent()
                .handle((input, caller) -> {
                    Scope scope = input.scope() == null ? Scope.CUSTOM : input.scope();
                    Set<String> builtinIds = this.toolSpecService.defaultToolIds();
                    Set<String> exposedNames = exposedNames();
                    List<ToolSpec> matching = this.toolSpecService.getToolSpecList().stream()
                            .filter(spec -> scope == Scope.ALL
                                    || (scope == Scope.BUILTIN) == builtinIds.contains(spec.toolId()))
                            .filter(spec -> input.status() == null || (input.status() == Status.DRAFT) == spec.draft())
                            .filter(spec -> input.category() == null
                                    || input.category().equalsIgnoreCase(spec.category()))
                            .filter(spec -> matchesQuery(spec, input.query()))
                            .sorted(Comparator.comparing(ToolSpec::name))
                            .toList();
                    int limit = limitOf(input.limit());
                    List<ToolSummary> page = matching.stream().limit(limit)
                            .map(spec -> summaryOf(spec, builtinIds, exposedNames)).toList();
                    return ManagementResult.ok(matching.size() + " tool(s).",
                            new ListData(page, matching.size(), matching.size() > page.size()));
                });
    }

    private ManagementTool get() {
        return ManagementTool.named("saip_tools_get", CATEGORY, NameInput.class).data(ToolDetail.class)
                .description("Show one tool completely: description, parameters, static variables, sandbox settings "
                        + "and the JavaScript code. Also works for bundled catalog tools, which are good examples.")
                .idempotent()
                .handle((input, caller) -> {
                    ToolSpec spec = requireTool(input.name());
                    Set<String> builtinIds = this.toolSpecService.defaultToolIds();
                    ToolSummary summary = summaryOf(spec, builtinIds, exposedNames());
                    return ManagementResult.ok("Tool '" + spec.name() + "'.",
                            new ToolDetail(summary.name(), spec.description(), summary.origin(), summary.draft(),
                                    summary.readiness(), summary.riskLevel(), summary.category(), summary.tags(),
                                    summary.exposed(), summary.humanApproval(), spec.code(),
                                    paramsOf(spec.params()), variablesOf(spec.staticVariables()),
                                    sandboxOf(spec.sandboxOverrides())));
                });
    }

    private ManagementTool saveDraft() {
        return ManagementTool.named("saip_tools_save_draft", CATEGORY, DraftInput.class)
                .data(DraftSavedData.class)
                .description("Save a tool as a draft, or replace the draft of the same name. Drafts are private: "
                        + "they are not offered to MCP clients and cannot be called. Read "
                        + "saip://guide/tool-authoring before writing one, then run saip_tools_test.")
                .approval(ApprovalLevel.WRITE, input -> ManagementApproval.write("Save the draft tool '"
                        + input.name() + "' (" + sandboxLine(explain(sandboxOverrides(input.sandbox()))) + ")."))
                .handle((input, caller) -> {
                    Optional<ToolSpec> existing = this.toolSpecService.getToolSpecAsOpt(input.name());
                    existing.ifPresent(this::rejectNotADraft);
                    ToolDraftInput draft = draftInput(input, existing.map(ToolSpec::toolId).orElse(null));
                    this.toolSpecService.saveDraft(draft);
                    Explanation explanation = explain(draft.sandboxOverrides());
                    boolean humanApproval = requiresApproval(draft.humanInTheLoop());
                    return ManagementResult.persisted("Saved draft '" + input.name() + "' (risk "
                                    + explanation.level() + ", " + callApprovalLine(humanApproval, explanation)
                                    + "). " + DRAFT_NOTE,
                            new DraftSavedData(input.name(), true, explanation.level().name(),
                                    explanation.elevated(), humanApproval, existing.isPresent(),
                                    List.of("saip_tools_test with name '" + input.name() + "'",
                                            "saip_tools_publish when the test passes")));
                });
    }

    private ManagementTool test() {
        return ManagementTool.named("saip_tools_test", CATEGORY, TestInput.class).data(TestData.class)
                .description("Run a tool once in the sandbox with the testValue of each parameter and report the "
                        + "result, error, console output and hints to fix a failure. Test a saved draft by name or "
                        + "an unsaved definition in draft. This is the Local Pass that publishing requires.")
                .approval(ApprovalLevel.WRITE, input -> {
                    ToolDraftInput subject = testSubject(input);
                    Explanation explanation = explain(subject.sandboxOverrides());
                    ManagementApproval approval = ManagementApproval.write("Run the tool code of '"
                            + subject.name() + "' in the sandbox (" + sandboxLine(explanation) + ").");
                    return explanation.elevated() ? approval.markHighRisk() : approval;
                })
                .handle((input, caller) -> {
                    TestData data = runTest(testSubject(input));
                    if (!data.localPass()) {
                        return ManagementResult.failure("Test failed. " + firstHint(data), data);
                    }
                    List<String> next = nextAfterPassedTest(input);
                    return ManagementResult.ok("Test passed (Local Pass) in " + data.durationMs()
                            + " ms. Do not test it again, next: " + next.getFirst() + ".", data.withNext(next));
                });
    }

    private ManagementTool publish() {
        return ManagementTool.named("saip_tools_publish", CATEGORY, NameInput.class).data(PublishData.class)
                .description("Publish a saved draft: it is tested again and, only if that passes, becomes a live "
                        + "tool. Whether MCP clients can see it depends on the exposure settings, see "
                        + "saip_tools_get_exposure. Publishing never changes exposure settings.")
                .approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    ToolSpec spec = requirePublishable(input.name());
                    Explanation explanation = explain(spec.sandboxOverrides());
                    boolean visible = this.toolSpecService.exposureMode().includesBuiltin()
                            && this.toolSpecService.autoAddsOnPublish(spec);
                    boolean humanApproval = requiresApproval(spec.humanInTheLoop());
                    ManagementApproval approval = ManagementApproval.destructive("Publish the tool '" + spec.name()
                            + "' (" + sandboxLine(explanation) + "). "
                            + (visible ? "It will be visible to MCP clients as soon as it is published."
                                    : "It will not be visible to MCP clients until it is exposed.")
                            + " " + capitalized(callApprovalLine(humanApproval, explanation)) + ".");
                    return explanation.elevated() || weakensCallApproval(humanApproval, explanation)
                            ? approval.markHighRisk() : approval;
                })
                .handle((input, caller) -> {
                    ToolSpec spec = requirePublishable(input.name());
                    ToolDraftInput draft = this.toolSpecService.draftInputOf(spec.name()).orElseThrow();
                    ToolSaveResult saved = this.toolSpecService.testAndPublish(draft);
                    TestData test = testData(draft, saved.test());
                    if (!saved.saved()) {
                        return ManagementResult.failure("Not published: the test failed. " + firstHint(test),
                                new PublishData(spec.name(), false, false,
                                        requiresApproval(spec.humanInTheLoop()), test.riskLevel(), null, test));
                    }
                    boolean exposed = exposedNames().contains(spec.name());
                    String note = exposed ? "Visible to MCP clients."
                            : "Not exposed on the built-in MCP server yet. To expose it call "
                                    + "saip_tools_set_exposure with expose [\"" + spec.name() + "\"].";
                    return ManagementResult.persisted("Published tool '" + spec.name() + "'. " + note,
                            new PublishData(spec.name(), true, exposed, requiresApproval(spec.humanInTheLoop()),
                                    test.riskLevel(), note, test));
                });
    }

    private ManagementTool delete() {
        return ManagementTool.named("saip_tools_delete", CATEGORY, NameInput.class).data(DeleteData.class)
                .description("Delete a custom tool or draft. Bundled catalog tools cannot be deleted here.")
                .destructive(input -> {
                    ToolSpec spec = requireDeletable(input.name());
                    return "Delete the tool '" + spec.name() + "'"
                            + (spec.draft() ? " (draft)." : exposedNames().contains(spec.name())
                                    ? " (published and visible to MCP clients)." : " (published).");
                })
                .handle((input, caller) -> {
                    ToolSpec spec = requireDeletable(input.name());
                    boolean exposed = exposedNames().contains(spec.name());
                    this.toolSpecService.deleteToolSpec(spec.toolId());
                    return ManagementResult.persisted("Deleted tool '" + spec.name() + "'.",
                            new DeleteData(spec.name(), !spec.draft(), exposed));
                });
    }

    private ManagementTool getExposure() {
        return ManagementTool.named("saip_tools_get_exposure", CATEGORY, NoInput.class).data(ExposureData.class)
                .description("Show which tools the built-in MCP server offers to clients: the automatic-exposure "
                        + "switch, exposed custom tools, published custom tools that are not exposed, and exposed "
                        + "bundled catalog tools.")
                .idempotent()
                .handle((input, caller) -> {
                    ExposureData data = exposureData();
                    return ManagementResult.ok(data.customExposed().size() + " custom and "
                            + data.builtinExposed().size() + " bundled tool(s) exposed, autoAdd "
                            + (data.autoAdd() ? "on" : "off") + ".", data);
                });
    }

    private ManagementTool setExposure() {
        return ManagementTool.named("saip_tools_set_exposure", CATEGORY, SetExposureInput.class)
                .data(ExposureData.class)
                .description("Change which tools the built-in MCP server offers to clients, and whether newly "
                        + "published tools are exposed automatically. Only the tools you name change: expose "
                        + "starts exposing them, stopExposing stops. Drafts cannot be exposed.")
                .approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    ExposurePlan plan = exposurePlan(input);
                    ManagementApproval approval = ManagementApproval.destructive(exposureSummary(plan));
                    return plan.highRisk() ? approval.markHighRisk() : approval;
                })
                .handle((input, caller) -> {
                    ExposurePlan plan = exposurePlan(input);
                    ExposureData before = exposureData();
                    if (plan.unchanged(before.autoAdd())) {
                        return ManagementResult.ok("Exposure is already as requested.", before);
                    }
                    this.toolSpecService.updateExposure(plan.selection());
                    ExposureData after = exposureData();
                    return ManagementResult.persisted("Exposure updated: " + plan.added().size() + " added, "
                            + plan.removed().size() + " removed, autoAdd " + (after.autoAdd() ? "on" : "off") + ".",
                            after);
                });
    }

    private ManagementTool checkRisk() {
        return ManagementTool.named("saip_tools_check_risk", CATEGORY, CheckRiskInput.class).data(RiskData.class)
                .description("Preview the risk level of a sandbox setting before saving anything: the level, the "
                        + "reasons behind it, cheaper alternatives, and whether testing or publishing would need "
                        + "human approval. Nothing is saved or run.")
                .idempotent()
                .handle((input, caller) -> {
                    SandboxOverrides overrides = sandboxOverrides(input.sandbox());
                    Explanation explanation = explain(overrides);
                    List<ReasonData> reasons = explanation.reasons().stream()
                            .map(reason -> new ReasonData(reason.setting(), reason.raisesTo().name(), reason.why()))
                            .toList();
                    RiskData data = new RiskData(explanation.level().name(), reasons, explanation.suggestions(),
                            explanation.elevated(),
                            HumanInTheLoop.Mode.defaultFor(explanation.level()) == HumanInTheLoop.Mode.REQUIRED,
                            testApproval(explanation), publishApproval(explanation),
                            codeWarnings(input.code(), overrides));
                    return ManagementResult.ok("Risk " + explanation.level() + (explanation.elevated()
                            ? ", above the sandbox baseline." : "."), data);
                });
    }

    private ToolDraftInput testSubject(TestInput input) {
        boolean byName = input.name() != null && !input.name().isBlank();
        if (byName == (input.draft() != null)) {
            throw new IllegalArgumentException("Give either name (a saved draft) or draft (an unsaved definition), "
                    + "not both and not neither.");
        }
        if (input.draft() != null) {
            return draftInput(input.draft(), null);
        }
        ToolSpec spec = requireTool(input.name());
        if (this.toolSpecService.defaultToolIds().contains(spec.toolId())) {
            throw new IllegalArgumentException("'" + spec.name() + "' is a bundled tool. Copy it into a draft with "
                    + "another name to change it.");
        }
        return this.toolSpecService.draftInputOf(spec.name()).orElseThrow();
    }

    private TestData runTest(ToolDraftInput draft) {
        return testData(draft, this.toolSpecService.testRun(draft));
    }

    private TestData testData(ToolDraftInput draft, ToolTestResult test) {
        JsExecutionResult result = test.result();
        List<String> tested = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        draft.params().stream().filter(param -> param.name() != null && !param.name().isBlank())
                .forEach(param -> (param.testValue() == null || param.testValue().isBlank() ? skipped : tested)
                        .add(param.name()));
        String output = result == null ? "" : text(result.result());
        String error = result == null || result.isOk() ? null : blankToNull(result.error());
        boolean returnedNothing = result != null && result.isOk() && output.isBlank();
        Explanation explanation = explain(draft.sandboxOverrides());
        return new TestData(test.ok(), test.endedAtMs() - test.startedAtMs(), truncate(output, RESULT_LIMIT),
                truncate(error == null ? null : ManagementSecrets.maskText(error), RESULT_LIMIT),
                truncate(result == null ? null : ManagementSecrets.maskText(blankToNull(result.debugInfo())),
                        CONSOLE_LIMIT),
                explanation.level().name(), explanation.elevated(), tested, skipped,
                ToolTestDiagnosis.hints(error, draft.code(), draft.sandboxOverrides(), skipped, returnedNothing),
                List.of());
    }

    private static List<String> nextAfterPassedTest(TestInput input) {
        return input.name() != null
                ? List.of("saip_tools_publish with name '" + input.name() + "'")
                : List.of("saip_tools_save_draft to keep this definition", "saip_tools_publish when it is saved");
    }

    private ToolDraftInput draftInput(DraftInput input, String toolId) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        if (input.code() == null || input.code().isBlank()) {
            throw new IllegalArgumentException("code is required.");
        }
        if (input.description() == null || input.description().isBlank()) {
            throw new IllegalArgumentException("description is required: the model that calls the tool reads it.");
        }
        List<ToolSpec.ToolParamSpec> params = paramSpecs(input.params());
        List<Map.Entry<String, String>> variables = variableEntries(input.staticVariables());
        Set<String> declared = new LinkedHashSet<>();
        params.forEach(param -> declared.add(param.name()));
        variables.forEach(variable -> {
            if (!declared.add(variable.getKey())) {
                throw new IllegalArgumentException("'" + variable.getKey()
                        + "' is used twice among parameters and static variables.");
            }
        });
        String category = input.category() == null || input.category().isBlank() ? null
                : input.category().toUpperCase(Locale.ROOT);
        if (category != null && !this.categoryCatalog.isKnown(category)) {
            throw new IllegalArgumentException("Unknown category '" + input.category() + "'. Use one of "
                    + new TreeSet<>(this.categoryCatalog.categoryIds()) + ".");
        }
        SandboxOverrides overrides = sandboxOverrides(input.sandbox());
        return new ToolDraftInput(toolId, input.name(), input.description(), variables, params, input.code(),
                category, input.tags(), overrides, new HumanInTheLoop(callApprovalMode(input.humanApproval(),
                        overrides), null));
    }

    private HumanInTheLoop.Mode callApprovalMode(Boolean humanApproval, SandboxOverrides overrides) {
        if (humanApproval == null) {
            return HumanInTheLoop.Mode.defaultFor(explain(overrides).level());
        }
        return humanApproval ? HumanInTheLoop.Mode.REQUIRED : HumanInTheLoop.Mode.DISABLED;
    }

    private static boolean requiresApproval(HumanInTheLoop humanInTheLoop) {
        return humanInTheLoop != null && humanInTheLoop.mode() == HumanInTheLoop.Mode.REQUIRED;
    }

    private static boolean weakensCallApproval(boolean humanApproval, Explanation explanation) {
        return !humanApproval && HumanInTheLoop.Mode.defaultFor(explanation.level()) == HumanInTheLoop.Mode.REQUIRED;
    }

    private static String callApprovalLine(boolean humanApproval, Explanation explanation) {
        if (humanApproval) {
            return "a person approves every call";
        }
        return weakensCallApproval(false, explanation)
                ? "calls run WITHOUT human approval although the risk is " + explanation.level()
                : "calls run without human approval";
    }

    private static String capitalized(String text) {
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private static List<ToolSpec.ToolParamSpec> paramSpecs(List<ParamSpec> params) {
        List<ToolSpec.ToolParamSpec> specs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ParamSpec param : params == null ? List.<ParamSpec>of() : params) {
            requireIdentifier("Parameter", param.name());
            if (!names.add(param.name())) {
                throw new IllegalArgumentException("Parameter '" + param.name() + "' is declared twice.");
            }
            specs.add(new ToolSpec.ToolParamSpec(param.name(), param.description(),
                    param.required() == null || param.required(),
                    param.type() == null ? JsonSchemaType.STRING : param.type(), testValueText(param.testValue())));
        }
        return specs;
    }

    private static String testValueText(Object value) {
        return value == null ? null : text(value);
    }

    private static List<Map.Entry<String, String>> variableEntries(List<VariableSpec> variables) {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (VariableSpec variable : variables == null ? List.<VariableSpec>of() : variables) {
            requireIdentifier("Static variable", variable.name());
            ManagementSecrets.requireReference("Static variable", variable.name(), variable.value());
            entries.add(Map.entry(variable.name(), variable.value() == null ? "" : variable.value()));
        }
        return entries;
    }

    private static void requireIdentifier(String what, String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " name '" + name + "' must be a plain identifier: letters, "
                    + "digits, _ or $, not starting with a digit.");
        }
        if (JsRuntimeGlobals.INJECTED_NAMES.contains(name)) {
            throw new IllegalArgumentException(what + " name '" + name + "' clashes with a runtime global ("
                    + new TreeSet<>(JsRuntimeGlobals.INJECTED_NAMES) + "). Pick another name.");
        }
    }

    private static SandboxOverrides sandboxOverrides(SandboxSpec sandbox) {
        if (sandbox == null) {
            return null;
        }
        Set<String> hosts = sandbox.hostsAllow() == null ? Set.of() : sandbox.hostsAllow();
        NetworkMode mode = sandbox.networkMode();
        if (mode == NetworkMode.ALLOWLIST && hosts.isEmpty()) {
            throw new IllegalArgumentException("networkMode ALLOWLIST needs hostsAllow with at least one host.");
        }
        if (!hosts.isEmpty() && mode != NetworkMode.ALLOWLIST) {
            throw new IllegalArgumentException("hostsAllow only applies to networkMode ALLOWLIST.");
        }
        String basePath = sandbox.fsBasePath() == null || sandbox.fsBasePath().isBlank() ? null : sandbox.fsBasePath();
        if (basePath != null && !Path.of(basePath).isAbsolute()) {
            throw new IllegalArgumentException("fsBasePath must be an absolute directory.");
        }
        return new SandboxOverrides(sandbox.addAllowClasses() == null ? Set.of() : sandbox.addAllowClasses(),
                Set.of(), Set.of(), Set.of(), mode == null ? null : mode.name().toLowerCase(Locale.ROOT), hosts,
                sandbox.fileRead(), sandbox.fileWrite(), basePath, sandbox.destructive());
    }

    private static SandboxSpec sandboxOf(SandboxOverrides overrides) {
        if (overrides == null || overrides.equals(SandboxOverrides.empty())) {
            return null;
        }
        NetworkMode mode = overrides.networkMode() == null ? null
                : NetworkMode.valueOf(overrides.networkMode().toUpperCase(Locale.ROOT));
        return new SandboxSpec(mode, emptyToNull(overrides.hostsAllow()), overrides.fileRead(), overrides.fileWrite(),
                overrides.fsBasePath(), overrides.destructive(), emptyToNull(overrides.addAllowClasses()));
    }

    private Explanation explain(SandboxOverrides overrides) {
        return this.toolSpecService.explainRisk(overrides);
    }

    private static String sandboxLine(Explanation explanation) {
        return "risk " + explanation.level() + (explanation.reasons().isEmpty() ? ", locked sandbox"
                : ", " + explanation.reasons().stream().map(Reason::setting).collect(Collectors.joining("; ")));
    }

    private String testApproval(Explanation explanation) {
        ApprovalMode mode = this.options.management().approval();
        if (explanation.elevated()) {
            return mode == ApprovalMode.NONE
                    ? "Refused: the sandbox is above the baseline and approval is disabled, so a person must do it "
                            + "in the app."
                    : "Asks the user to approve, because the sandbox is above the baseline.";
        }
        return mode == ApprovalMode.ALL ? "Asks the user to approve." : "Runs without asking.";
    }

    private String publishApproval(Explanation explanation) {
        ApprovalMode mode = this.options.management().approval();
        if (mode == ApprovalMode.NONE) {
            return explanation.elevated()
                    ? "Refused: the sandbox is above the baseline and approval is disabled, so a person must "
                            + "publish it in the app."
                    : "Runs without asking (approval is disabled).";
        }
        return "Asks the user to approve" + (explanation.elevated() ? ", stressing the raised sandbox." : ".");
    }

    private static List<String> codeWarnings(String code, SandboxOverrides overrides) {
        List<String> warnings = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return warnings;
        }
        boolean network = overrides != null && overrides.networkMode() != null
                && !"blocked".equals(overrides.networkMode());
        boolean files = overrides != null
                && (Boolean.TRUE.equals(overrides.fileRead()) || Boolean.TRUE.equals(overrides.fileWrite()));
        if (code.contains("fetch(") && !network) {
            warnings.add("The code calls fetch but the sandbox blocks the network. Use networkMode ALLOWLIST "
                    + "with hostsAllow.");
        }
        if (code.contains("safety.fs") && !files) {
            warnings.add("The code uses safety.fs but the sandbox grants no file access.");
        }
        if (code.contains("Java.type")) {
            warnings.add("Java.type needs classes the sandbox may deny. Prefer the runtime helpers.");
        }
        return warnings;
    }

    private ToolSpec requireTool(String name) {
        return this.toolSpecService.getToolSpecAsOpt(name).orElseThrow(() -> new IllegalArgumentException(
                "No tool named '" + name + "'. Use saip_tools_list to see the names."));
    }

    private ToolSpec requirePublishable(String name) {
        ToolSpec spec = requireTool(name);
        if (this.toolSpecService.defaultToolIds().contains(spec.toolId())) {
            throw new IllegalArgumentException("'" + name + "' is a bundled tool and cannot be published here.");
        }
        if (!spec.draft()) {
            throw new IllegalArgumentException("'" + name + "' is already published.");
        }
        return spec;
    }

    private ToolSpec requireDeletable(String name) {
        ToolSpec spec = requireTool(name);
        if (this.toolSpecService.defaultToolIds().contains(spec.toolId())) {
            throw new IllegalArgumentException("'" + name + "' is a bundled tool and cannot be deleted here.");
        }
        return spec;
    }

    private void rejectNotADraft(ToolSpec existing) {
        if (this.toolSpecService.defaultToolIds().contains(existing.toolId())) {
            throw new IllegalStateException("'" + existing.name() + "' is a bundled tool. Save your version under "
                    + "another name.");
        }
        if (!existing.draft()) {
            throw new IllegalStateException("'" + existing.name() + "' is already published and is not changed "
                    + "here. Save the new version as a draft under another name, publish it, then delete the old "
                    + "tool with saip_tools_delete.");
        }
    }

    private Set<String> exposedNames() {
        return this.toolSpecService.getMcpToolList().stream().map(McpSchema.Tool::name)
                .collect(Collectors.toSet());
    }

    private ToolSummary summaryOf(ToolSpec spec, Set<String> builtinIds, Set<String> exposedNames) {
        return new ToolSummary(spec.name(), truncate(spec.description(), DESCRIPTION_LIMIT),
                builtinIds.contains(spec.toolId()) ? Origin.BUILTIN : Origin.CUSTOM, spec.draft(),
                this.toolSpecService.readiness(spec), this.toolSpecService.riskLevelOf(spec),
                spec.category() == null ? "CUSTOM" : spec.category(),
                spec.tags() == null ? List.of() : new TreeSet<>(spec.tags()).stream().toList(),
                exposedNames.contains(spec.name()),
                this.toolSpecService.requiresApproval(spec));
    }

    private static List<ParamSpec> paramsOf(List<ToolSpec.ToolParamSpec> params) {
        return params == null ? List.of() : params.stream().map(param -> new ParamSpec(param.name(),
                param.description(), param.required(), param.type(), param.testValue())).toList();
    }

    private static List<VariableSpec> variablesOf(List<Map.Entry<String, String>> variables) {
        return variables == null ? List.of() : variables.stream().map(entry -> new VariableSpec(entry.getKey(),
                ManagementSecrets.isSensitiveName(entry.getKey()) && !ManagementSecrets.isEnvReference(entry.getValue())
                        && entry.getValue() != null && !entry.getValue().isBlank()
                        ? ManagementSecrets.MASK : entry.getValue())).toList();
    }

    private ExposureData exposureData() {
        Set<String> builtinIds = this.toolSpecService.defaultToolIds();
        Set<String> exposedIds = this.toolSpecService.getToolMcpServerSetting().exposedToolIds();
        List<ToolSpec> specs = this.toolSpecService.getToolSpecList();
        List<String> customExposed = new ArrayList<>();
        List<String> customNotExposed = new ArrayList<>();
        specs.stream().filter(spec -> !builtinIds.contains(spec.toolId()) && !spec.draft())
                .sorted(Comparator.comparing(ToolSpec::name))
                .forEach(spec -> (exposedIds.contains(spec.toolId()) ? customExposed : customNotExposed)
                        .add(spec.name()));
        List<String> builtinExposed = this.toolSpecService.exposableBuiltins().stream()
                .filter(spec -> exposedIds.contains(spec.toolId())).map(ToolSpec::name).sorted().toList();
        return new ExposureData(this.toolSpecService.getToolMcpServerSetting().autoAdd(),
                this.toolSpecService.exposureMode(), customExposed, customNotExposed, builtinExposed,
                this.toolSpecService.exposableBuiltins().size());
    }

    private ExposurePlan exposurePlan(SetExposureInput input) {
        Set<String> builtinIds = this.toolSpecService.defaultToolIds();
        Set<String> exposableBuiltinIds = this.toolSpecService.exposableBuiltins().stream().map(ToolSpec::toolId)
                .collect(Collectors.toSet());
        Set<String> currentExposable = this.toolSpecService.getToolMcpServerSetting().exposedToolIds().stream()
                .filter(id -> !builtinIds.contains(id) || exposableBuiltinIds.contains(id))
                .collect(Collectors.toSet());
        Set<String> toExpose = resolveIds(input.expose(), true, builtinIds, exposableBuiltinIds);
        Set<String> toStop = resolveIds(input.stopExposing(), false, builtinIds, exposableBuiltinIds);
        if (toExpose.stream().anyMatch(toStop::contains)) {
            throw new IllegalArgumentException("A tool is listed in both expose and stopExposing.");
        }
        Set<String> desired = new LinkedHashSet<>(currentExposable);
        desired.addAll(toExpose);
        desired.removeAll(toStop);
        Set<String> customIds = desired.stream().filter(id -> !builtinIds.contains(id))
                .collect(Collectors.toSet());
        Set<String> builtinSelected = desired.stream().filter(builtinIds::contains).collect(Collectors.toSet());
        boolean autoAdd = input.autoAdd() == null ? this.toolSpecService.getToolMcpServerSetting().autoAdd()
                : input.autoAdd();
        List<String> added = desired.stream().filter(id -> !currentExposable.contains(id)).map(this::nameOf)
                .sorted().toList();
        List<String> removed = currentExposable.stream().filter(id -> !desired.contains(id)).map(this::nameOf)
                .filter(Objects::nonNull).sorted().toList();
        boolean highRisk = desired.stream().filter(id -> !currentExposable.contains(id))
                .map(id -> this.toolSpecService.getToolSpecList().stream()
                        .filter(spec -> id.equals(spec.toolId())).findFirst())
                .flatMap(Optional::stream)
                .anyMatch(spec -> RiskLevel.valueOf(this.toolSpecService.riskLevelOf(spec))
                        .ordinal() >= RiskLevel.L4.ordinal());
        return new ExposurePlan(new ExposureSelection(autoAdd, customIds, builtinSelected), autoAdd, added, removed,
                highRisk);
    }

    private Set<String> resolveIds(Collection<String> names, boolean forExposing, Set<String> builtinIds,
            Set<String> exposableBuiltinIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            ToolSpec spec = requireTool(name);
            if (forExposing && spec.draft()) {
                throw new IllegalArgumentException("'" + name + "' is a draft. Publish it first, drafts cannot be "
                        + "exposed.");
            }
            if (forExposing && builtinIds.contains(spec.toolId()) && !exposableBuiltinIds.contains(spec.toolId())) {
                throw new IllegalArgumentException("The bundled tool '" + name + "' cannot be exposed.");
            }
            ids.add(spec.toolId());
        }
        return ids;
    }

    private String nameOf(String toolId) {
        return this.toolSpecService.getToolSpecList().stream().filter(spec -> toolId.equals(spec.toolId()))
                .map(ToolSpec::name).findFirst().orElse(null);
    }

    private String exposureSummary(ExposurePlan plan) {
        Map<String, String> parts = new LinkedHashMap<>();
        if (!plan.added().isEmpty()) {
            parts.put("expose", names(plan.added()));
        }
        if (!plan.removed().isEmpty()) {
            parts.put("stop exposing", names(plan.removed()));
        }
        parts.put("automatic exposure of new tools", plan.autoAdd() ? "on" : "off");
        String summary = parts.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
        return "Change what the built-in MCP server offers to clients. " + summary + "."
                + (plan.highRisk() ? " This exposes tools rated L4 or higher." : "");
    }

    private static String names(List<String> names) {
        return names.size() <= SUMMARY_NAMES ? names.toString()
                : names.subList(0, SUMMARY_NAMES) + " and " + (names.size() - SUMMARY_NAMES) + " more";
    }

    private static boolean matchesQuery(ToolSpec spec, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return spec.name().toLowerCase(Locale.ROOT).contains(needle)
                || (spec.description() != null && spec.description().toLowerCase(Locale.ROOT).contains(needle))
                || (spec.tags() != null && spec.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT)
                        .contains(needle)));
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return RESULT_MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    private static String firstHint(TestData data) {
        String cause = data.error() == null ? "The code returned nothing." : "Error: " + data.error() + ".";
        return data.hints().isEmpty() ? cause : cause + " Hint: " + data.hints().get(0);
    }

    private static String truncate(String text, int limit) {
        return text == null || text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static <T> Set<T> emptyToNull(Set<T> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private static int limitOf(Integer requested) {
        return requested == null ? LIMIT_DEFAULT : Math.max(1, Math.min(requested, LIMIT_MAX));
    }

}
