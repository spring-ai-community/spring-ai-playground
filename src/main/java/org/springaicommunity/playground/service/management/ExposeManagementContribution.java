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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange.Operation;
import org.springaicommunity.playground.service.mcp.risk.ExposureOutcome;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.Change;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.PlannedMember;
import org.springaicommunity.playground.service.mcp.risk.ExposureView;
import org.springaicommunity.playground.service.mcp.risk.HitlPolicy;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger.Fingerprint;
import org.springaicommunity.playground.service.mcp.risk.ServerSelection;
import org.springaicommunity.playground.service.mcp.risk.ToolPick;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ExposeManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "expose";

    private static final Set<String> TOOL_KEYS = Set.of("name", "alias", "description", "hitl");
    private static final int SUMMARY_NAMES = 10;
    private static final String TOOLS_HELP = "Which tools: the string \"all\", a list of tool names, or a list of "
            + "objects {name, alias?, description?, hitl?} to set the exposed name, description and human approval "
            + "per tool.";

    private final McpExposureService exposureService;

    public ExposeManagementContribution(McpExposureService exposureService) {
        this.exposureService = exposureService;
    }

    public record NoInput() {
    }

    public record HitlInput(
            @Nullable @JsonProperty("default") @JsonPropertyDescription("ALL (default): every tool needs human approval, RISK: only tools at or above atLeast, NONE: no approval. Tools in except get the opposite.") HitlPolicy.Mode defaultMode,
            @Nullable @JsonPropertyDescription("With default RISK: the lowest risk level that needs approval, default L3.") RiskLevel atLeast,
            @Nullable @JsonPropertyDescription("Tool names that get the opposite of the default.") List<String> except) {
    }

    public record SelectionInput(
            @JsonPropertyDescription("Registered MCP server name.") String server,
            @Nullable @JsonPropertyDescription(TOOLS_HELP) Object tools,
            @Nullable @JsonPropertyDescription("Human approval rule for the tools of this server. Omit it to require approval for all of them.") HitlInput hitl) {
    }

    public record Acknowledgement(String server, String tool) {
    }

    public record ChangeInput(
            @JsonPropertyDescription("One entry per server. Several servers can be combined in one call.") List<SelectionInput> selections,
            @Nullable @JsonPropertyDescription("Prefix for exposed names instead of <server>_<tool>.") String aliasPrefix,
            @Nullable @JsonPropertyDescription("Highest risk level allowed to be exposed. Keeps the current cap when omitted.") RiskLevel maxRisk,
            @Nullable @JsonPropertyDescription("Tools whose definition changed since they were last approved. List a tool here only after reviewing its current description with saip_mcp_server_tools.") List<Acknowledgement> acknowledgeChanged) {
    }

    public record PreviewInput(
            @JsonPropertyDescription("ADD, REMOVE, SET_HITL or REPLACE.") Operation operation,
            @JsonPropertyDescription("Same as in the matching saip_expose tool.") List<SelectionInput> selections,
            @Nullable String aliasPrefix, @Nullable RiskLevel maxRisk,
            @Nullable List<Acknowledgement> acknowledgeChanged) {
    }

    public record MemberChange(String server, String tool, String exposedAs, boolean hitl, RiskLevel risk,
                               Change change, String description) {
    }

    public record RejectionData(String server, String tool, String reason) {
    }

    public record ChangeData(String operation, boolean applied, int exposedBefore, int exposedAfter,
                             int unchanged, List<MemberChange> changes, List<RejectionData> rejections,
                             List<String> withheld, boolean composedToolsVisible, RiskLevel maxRisk) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(get(), preview(), change("saip_expose_add_tools", Operation.ADD,
                "Expose tools of registered MCP servers on this app's MCP server, keeping what is already "
                        + "exposed. Pick all or some tools, combine several servers in one call, and choose human "
                        + "approval per tool. Tools above the risk cap or with a changed definition are refused."),
                change("saip_expose_remove_tools", Operation.REMOVE,
                        "Stop exposing tools. Other exposed tools stay."),
                change("saip_expose_set_hitl", Operation.SET_HITL,
                        "Change the human approval requirement of tools that are already exposed. Turning "
                                + "approval off is a weakening and needs confirmation."),
                change("saip_expose_apply", Operation.REPLACE,
                        "Replace the whole exposed set with exactly the given selections. Everything not listed "
                                + "is removed. Prefer saip_expose_add_tools or saip_expose_remove_tools for "
                                + "incremental changes."));
    }

    private ManagementTool get() {
        return ManagementTool.named("saip_expose_get", CATEGORY, NoInput.class).data(ExposureView.class)
                .description("Show which tools of registered MCP servers are exposed on this app's MCP server: "
                        + "exposed name, human approval, risk, fingerprint status, whether each is actually "
                        + "visible, the risk cap, the exposure mode and whether the set is managed by "
                        + "configuration.")
                .idempotent()
                .handle((input, caller) -> {
                    ExposureView view = this.exposureService.view();
                    long servers = view.members().stream().map(ExposureView.ViewMember::server).distinct().count();
                    return ManagementResult.ok(view.members().size() + " tool(s) exposed from " + servers
                            + " server(s), mode " + view.mode() + ", risk cap " + view.maxRisk()
                            + (view.managedByConfiguration() ? ", managed by configuration." : "."), view);
                });
    }

    private ManagementTool preview() {
        return ManagementTool.named("saip_expose_preview", CATEGORY, PreviewInput.class).data(ChangeData.class)
                .description("Dry run of saip_expose_add_tools, remove_tools, set_hitl or apply: shows what "
                        + "would change and what would be refused, without changing anything.")
                .idempotent()
                .handle((input, caller) -> {
                    ExposurePlan plan = this.exposureService.plan(changeOf(input.operation(), input.selections(),
                            input.aliasPrefix(), input.maxRisk(), input.acknowledgeChanged()));
                    ChangeData data = dataOf(plan, false, List.of());
                    String message = plan.accepted() ? "Preview: " + describe(plan)
                            : "Preview: this would be refused, " + plan.rejections().size() + " problem(s).";
                    return ManagementResult.ok(message, data).withWarnings(plan.warnings());
                });
    }

    private ManagementTool change(String name, Operation operation, String description) {
        return ManagementTool.named(name, CATEGORY, ChangeInput.class).data(ChangeData.class)
                .description(description)
                .approval(ApprovalLevel.DESTRUCTIVE, input -> approvalOf(changeOf(operation, input)))
                .handle((input, caller) -> {
                    ExposureOutcome outcome = this.exposureService.commit(changeOf(operation, input));
                    ExposurePlan plan = outcome.plan();
                    ChangeData data = dataOf(plan, outcome.applied(), outcome.withheld());
                    if (!plan.accepted()) {
                        return ManagementResult.failure("Nothing was changed: " + rejectionSummary(plan), data);
                    }
                    List<String> warnings = new ArrayList<>(plan.warnings());
                    outcome.withheld().forEach(alias -> warnings.add("'" + alias + "' is configured but not "
                            + "visible yet: its server is disconnected or its definition awaits review."));
                    ManagementResult result = outcome.applied()
                            ? ManagementResult.persisted(describe(plan), data)
                            : ManagementResult.ok("No change: the exposure already matches.", data);
                    return result.withWarnings(warnings);
                });
    }

    private static ExposureChange changeOf(Operation operation, ChangeInput input) {
        return changeOf(operation, input.selections(), input.aliasPrefix(), input.maxRisk(),
                input.acknowledgeChanged());
    }

    private static ExposureChange changeOf(Operation operation, List<SelectionInput> selections,
            String aliasPrefix, RiskLevel maxRisk, List<Acknowledgement> acknowledged) {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("selections must list at least one server.");
        }
        List<ServerSelection> converted = selections.stream().map(selection -> selectionOf(operation, selection))
                .toList();
        Set<String> keys = new HashSet<>();
        if (acknowledged != null) {
            acknowledged.forEach(item -> keys.add(Fingerprint.keyOf(item.server(), item.tool())));
        }
        return new ExposureChange(operation, converted, aliasPrefix, maxRisk, keys);
    }

    private static ServerSelection selectionOf(Operation operation, SelectionInput input) {
        HitlPolicy policy = input.hitl() == null ? null : new HitlPolicy(input.hitl().defaultMode(),
                input.hitl().atLeast(), input.hitl().except() == null ? null : Set.copyOf(input.hitl().except()));
        Object tools = input.tools();
        if (tools instanceof String text && "all".equalsIgnoreCase(text.trim())) {
            requirePolicyForHitlChange(operation, input, policy, List.of());
            return ServerSelection.allTools(input.server(), policy);
        }
        if (tools == null && operation == Operation.SET_HITL) {
            requirePolicyForHitlChange(operation, input, policy, List.of());
            return ServerSelection.allTools(input.server(), policy);
        }
        if (!(tools instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("tools for '" + input.server() + "' must be \"all\", a list of "
                    + "tool names or a list of {name, alias, description, hitl} objects.");
        }
        List<ToolPick> picks = items.stream().map(item -> pickOf(input.server(), item)).toList();
        requirePolicyForHitlChange(operation, input, policy, picks);
        return ServerSelection.of(input.server(), picks, policy);
    }

    private static void requirePolicyForHitlChange(Operation operation, SelectionInput input, HitlPolicy policy,
            List<ToolPick> picks) {
        if (operation == Operation.SET_HITL && policy == null && picks.stream().noneMatch(pick -> pick.hitl() != null)) {
            throw new IllegalArgumentException("For '" + input.server() + "' give a hitl rule or a hitl flag per "
                    + "tool.");
        }
    }

    private static ToolPick pickOf(String server, Object item) {
        if (item instanceof String name) {
            return ToolPick.of(name);
        }
        if (item instanceof Map<?, ?> values) {
            values.keySet().forEach(key -> {
                if (!TOOL_KEYS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("Unknown tool field '" + key + "' for '" + server
                            + "'. Allowed: name, alias, description, hitl.");
                }
            });
            Object name = values.get("name");
            if (name == null || String.valueOf(name).isBlank()) {
                throw new IllegalArgumentException("Every tool object for '" + server + "' needs a name.");
            }
            Object hitl = values.get("hitl");
            return new ToolPick(String.valueOf(name), textOf(values.get("alias")), textOf(values.get("description")),
                    hitl == null ? null : Boolean.valueOf(String.valueOf(hitl)));
        }
        throw new IllegalArgumentException("Tools for '" + server + "' must be names or objects.");
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ManagementApproval approvalOf(ExposureChange change, ExposurePlan plan) {
        if (!plan.accepted()) {
            return ManagementApproval.READ_ONLY;
        }
        List<PlannedMember> touched = plan.members().stream().filter(member -> member.change() != Change.UNCHANGED)
                .toList();
        if (touched.isEmpty()) {
            return ManagementApproval.READ_ONLY;
        }
        StringBuilder text = new StringBuilder("Change what this app exposes to MCP clients (")
                .append(change.operation()).append("): ").append(describe(plan)).append(". ");
        appendNames(text, "Added", touched, Change.ADDED, true);
        appendNames(text, "Updated", touched, Change.UPDATED, true);
        appendNames(text, "Removed", touched, Change.REMOVED, false);
        List<PlannedMember> unprotected = touched.stream().filter(member -> member.change() != Change.REMOVED
                && !member.hitl()).toList();
        if (!unprotected.isEmpty()) {
            text.append("WITHOUT human approval: ").append(unprotected.stream()
                    .map(member -> member.alias() + riskSuffix(member)).limit(SUMMARY_NAMES)
                    .collect(Collectors.joining(", "))).append(". ");
        }
        text.append("Risk cap ").append(plan.maxRisk()).append(".");
        ManagementApproval approval = ManagementApproval.destructive(text.toString());
        boolean high = touched.stream().anyMatch(member -> member.change() != Change.REMOVED
                && member.inherentRisk() != null && member.inherentRisk().ordinal() >= RiskLevel.L4.ordinal());
        return high ? approval.markHighRisk() : approval;
    }

    private ManagementApproval approvalOf(ExposureChange change) {
        return approvalOf(change, this.exposureService.plan(change));
    }

    private static void appendNames(StringBuilder text, String label, List<PlannedMember> members, Change kind,
            boolean withDetails) {
        List<PlannedMember> matching = members.stream().filter(member -> member.change() == kind).toList();
        if (matching.isEmpty()) {
            return;
        }
        text.append(label).append(": ").append(matching.stream().limit(SUMMARY_NAMES)
                .map(member -> withDetails ? member.alias() + riskSuffix(member) + (member.hitl() ? " HITL" : "")
                        : member.alias())
                .collect(Collectors.joining(", ")));
        if (matching.size() > SUMMARY_NAMES) {
            text.append(" and ").append(matching.size() - SUMMARY_NAMES).append(" more");
        }
        text.append(". ");
    }

    private static String riskSuffix(PlannedMember member) {
        return member.inherentRisk() == null ? "" : " (" + member.inherentRisk() + ")";
    }

    private static String describe(ExposurePlan plan) {
        long added = plan.members().stream().filter(member -> member.change() == Change.ADDED).count();
        long updated = plan.members().stream().filter(member -> member.change() == Change.UPDATED).count();
        long removed = plan.members().stream().filter(member -> member.change() == Change.REMOVED).count();
        return "+" + added + " added, " + updated + " updated, -" + removed + " removed, " + plan.exposedAfter()
                + " exposed afterwards";
    }

    private static String rejectionSummary(ExposurePlan plan) {
        return plan.rejections().stream()
                .map(rejection -> (rejection.server() == null ? "" : rejection.server()
                        + (rejection.tool() == null ? "" : "/" + rejection.tool()) + ": ") + rejection.reason())
                .limit(SUMMARY_NAMES).collect(Collectors.joining(" | "));
    }

    private static ChangeData dataOf(ExposurePlan plan, boolean applied, List<String> withheld) {
        List<MemberChange> changes = plan.members().stream().filter(member -> member.change() != Change.UNCHANGED)
                .map(member -> new MemberChange(member.server(), member.tool(), member.alias(), member.hitl(),
                        member.inherentRisk(), member.change(), member.description()))
                .toList();
        int unchanged = (int) plan.members().stream().filter(member -> member.change() == Change.UNCHANGED).count();
        List<RejectionData> rejections = plan.rejections().stream()
                .map(rejection -> new RejectionData(rejection.server(), rejection.tool(), rejection.reason()))
                .toList();
        return new ChangeData(plan.operation().name(), applied, plan.exposedBefore(), plan.exposedAfter(), unchanged,
                changes, rejections, withheld, plan.composedToolsVisible(), plan.maxRisk());
    }

}
