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
package org.springaicommunity.playground.service.mcp.risk;

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.ManagementToolNames;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.Change;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.PlannedMember;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.Rejection;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger.Fingerprint;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class McpExposureService {

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final RiskLevel HITL_RECOMMENDED_FROM = RiskLevel.L3;

    private final McpCompositionService compositionService;
    private final McpExposedToolService exposedToolService;
    private final McpClientService clientService;
    private final McpToolRiskEvaluator riskEvaluator;
    private final ToolSpecService toolSpecService;
    private final McpToolHashLedger hashLedger;

    public McpExposureService(McpCompositionService compositionService, McpExposedToolService exposedToolService,
            McpClientService clientService, McpToolRiskEvaluator riskEvaluator, ToolSpecService toolSpecService,
            McpToolHashLedger hashLedger) {
        this.compositionService = compositionService;
        this.exposedToolService = exposedToolService;
        this.clientService = clientService;
        this.riskEvaluator = riskEvaluator;
        this.toolSpecService = toolSpecService;
        this.hashLedger = hashLedger;
    }

    private record ServerContext(McpServerInfo server, Map<String, McpSchema.Tool> tools) {
    }

    public ExposureView view() {
        Optional<McpComposition> exposed = this.compositionService.getExposed();
        Set<String> exposedNames = this.toolSpecService.getExternalMcpToolNames();
        Map<String, Optional<ServerContext>> contexts = new HashMap<>();
        List<ExposureView.ViewMember> members = new ArrayList<>();
        for (McpComposition.Member member : exposed.map(McpComposition::members).orElse(List.of())) {
            Optional<ServerContext> context = contexts.computeIfAbsent(member.serverId(), this::contextOf);
            RiskLevel inherent = context.map(found -> found.tools().get(member.toolName()))
                    .map(tool -> this.riskEvaluator.evaluateTool(context.get().server(), tool).inherentLevel())
                    .orElse(null);
            String fingerprint = this.hashLedger.get(member.serverId(), member.toolName())
                    .map(found -> found.status().name()).orElse("NEW");
            members.add(new ExposureView.ViewMember(member.serverId(), member.toolName(), member.exposedAlias(),
                    member.descriptionOverride(), member.hitl(), inherent, fingerprint, context.isPresent(),
                    exposedNames.contains(member.exposedAlias())));
        }
        return new ExposureView(this.toolSpecService.exposureMode(),
                exposed.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5),
                this.compositionService.exposedManagedByConfiguration(), members);
    }

    public ExposurePlan plan(ExposureChange change) {
        Optional<McpComposition> exposed = this.compositionService.getExposed();
        List<McpComposition.Member> current = exposed.map(McpComposition::members).orElse(List.of());
        RiskLevel cap = change.maxRisk() != null ? change.maxRisk()
                : exposed.map(McpComposition::maxRiskLevel).orElse(RiskLevel.L5);
        boolean managed = this.compositionService.exposedManagedByConfiguration();
        boolean composedVisible = this.toolSpecService.exposureMode().includesComposed();
        List<Rejection> rejections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (managed) {
            rejections.add(new Rejection(null, null, "Composed tools are managed by configuration ("
                    + McpExposedToolService.COMPOSED_TOOLS_PROPERTY + "); edit the configuration and restart to "
                    + "change them."));
            return new ExposurePlan(change.operation(), true, cap, List.of(), current, rejections, warnings,
                    current.size(), composedVisible);
        }
        Map<String, PlannedMember> planned = new LinkedHashMap<>();
        Map<String, McpComposition.Member> resulting = new LinkedHashMap<>();
        Map<String, Optional<ServerContext>> contexts = new HashMap<>();
        for (McpComposition.Member member : current) {
            String key = keyOf(member.serverId(), member.toolName());
            if (change.operation() == ExposureChange.Operation.REPLACE) {
                planned.put(key, plannedOf(member, null, Change.REMOVED));
            } else {
                planned.put(key, plannedOf(member, null, Change.UNCHANGED));
                resulting.put(key, member);
            }
        }
        for (ServerSelection selection : change.selections()) {
            switch (change.operation()) {
                case REMOVE -> planRemoval(selection, current, planned, resulting, warnings);
                case SET_HITL -> planHitl(selection, contexts, planned, resulting, rejections);
                case ADD, REPLACE -> planAddition(change, selection, cap, contexts, planned, resulting, rejections);
            }
        }
        checkAliases(resulting, planned, rejections);
        planned.forEach((key, member) -> warnIfUnprotected(member, warnings));
        if (!composedVisible) {
            warnings.add("The exposure mode hides composed tools, so nothing proxied is visible to MCP clients "
                    + "until the mode includes them (spring.ai.playground.built-in-mcp-server.exposure-mode).");
        }
        return new ExposurePlan(change.operation(), false, cap, List.copyOf(planned.values()),
                List.copyOf(resulting.values()), rejections, warnings, current.size(), composedVisible);
    }

    public synchronized ExposureOutcome commit(ExposureChange change) {
        ExposurePlan plan = plan(change);
        if (!plan.accepted()) {
            return new ExposureOutcome(plan, false, List.of());
        }
        boolean changed = plan.members().stream().anyMatch(member -> member.change() != Change.UNCHANGED);
        if (!changed) {
            return new ExposureOutcome(plan, false, List.of());
        }
        this.exposedToolService.apply(plan.resultingMembers(), plan.maxRisk(), change.acknowledged());
        Set<String> exposedNames = this.toolSpecService.getExternalMcpToolNames();
        List<String> withheld = plan.resultingMembers().stream().map(McpComposition.Member::exposedAlias)
                .filter(alias -> !exposedNames.contains(alias)).toList();
        return new ExposureOutcome(plan, true, withheld);
    }

    private void planAddition(ExposureChange change, ServerSelection selection, RiskLevel cap,
            Map<String, Optional<ServerContext>> contexts, Map<String, PlannedMember> planned,
            Map<String, McpComposition.Member> resulting, List<Rejection> rejections) {
        Optional<ServerContext> context = contexts.computeIfAbsent(selection.server(), this::contextOf);
        if (context.isEmpty()) {
            rejections.add(new Rejection(selection.server(), null, "Server is not registered or not connected."));
            return;
        }
        List<ToolPick> picks = selection.all()
                ? context.get().tools().keySet().stream().map(ToolPick::of).toList() : selection.tools();
        for (ToolPick pick : picks) {
            McpSchema.Tool tool = context.get().tools().get(pick.name());
            if (tool == null) {
                rejections.add(new Rejection(selection.server(), pick.name(),
                        "The server does not offer this tool."));
                continue;
            }
            RiskLevel inherent = this.riskEvaluator.evaluateTool(context.get().server(), tool).inherentLevel();
            if (inherent.ordinal() > cap.ordinal()) {
                rejections.add(new Rejection(selection.server(), pick.name(), "Inherent risk " + inherent
                        + " is above the cap " + cap + " (HITL does not lift the cap)."));
                continue;
            }
            String key = keyOf(selection.server(), pick.name());
            McpComposition.Member existing = resulting.get(key);
            boolean hitl = pick.hitl() != null ? pick.hitl() : selection.hitl().requires(pick.name(), inherent);
            String alias = aliasOf(change.aliasPrefix(), selection.server(), pick, existing);
            McpComposition.Member member = McpComposition.Member.of(selection.server(), pick.name(), alias,
                    pick.description(), tool.description(), hitl);
            if (existing != null) {
                member = new McpComposition.Member(member.serverId(), member.toolName(), member.exposedAlias(),
                        pick.description() == null ? existing.descriptionOverride() : member.descriptionOverride(),
                        hitl, existing.contentHash());
            }
            rejectUnacknowledgedChange(change, member, rejections);
            Change kind = existing == null ? Change.ADDED
                    : existing.equals(member) ? Change.UNCHANGED : Change.UPDATED;
            resulting.put(key, member);
            planned.put(key, plannedOf(member, inherent, kind));
        }
    }

    private void planRemoval(ServerSelection selection, List<McpComposition.Member> current,
            Map<String, PlannedMember> planned, Map<String, McpComposition.Member> resulting,
            List<String> warnings) {
        List<String> names = selection.all()
                ? current.stream().filter(member -> member.serverId().equals(selection.server()))
                        .map(McpComposition.Member::toolName).toList()
                : selection.tools().stream().map(ToolPick::name).toList();
        for (String name : names) {
            String key = keyOf(selection.server(), name);
            McpComposition.Member existing = resulting.remove(key);
            if (existing == null) {
                warnings.add("'" + name + "' on '" + selection.server() + "' is not exposed, nothing to remove.");
                continue;
            }
            planned.put(key, plannedOf(existing, null, Change.REMOVED));
        }
    }

    private void planHitl(ServerSelection selection, Map<String, Optional<ServerContext>> contexts,
            Map<String, PlannedMember> planned, Map<String, McpComposition.Member> resulting,
            List<Rejection> rejections) {
        Optional<ServerContext> context = contexts.computeIfAbsent(selection.server(), this::contextOf);
        List<ToolPick> picks = selection.all()
                ? resulting.values().stream().filter(member -> member.serverId().equals(selection.server()))
                        .map(member -> ToolPick.of(member.toolName())).toList()
                : selection.tools();
        for (ToolPick pick : picks) {
            String key = keyOf(selection.server(), pick.name());
            McpComposition.Member existing = resulting.get(key);
            if (existing == null) {
                rejections.add(new Rejection(selection.server(), pick.name(), "This tool is not exposed."));
                continue;
            }
            RiskLevel inherent = context.map(found -> found.tools().get(pick.name()))
                    .map(tool -> this.riskEvaluator.evaluateTool(context.get().server(), tool).inherentLevel())
                    .orElse(null);
            if (pick.hitl() == null && selection.hitl().mode() == HitlPolicy.Mode.RISK && inherent == null) {
                rejections.add(new Rejection(selection.server(), pick.name(),
                        "The risk of this tool is unknown because its server is not connected."));
                continue;
            }
            boolean hitl = pick.hitl() != null ? pick.hitl() : selection.hitl().requires(pick.name(), inherent);
            McpComposition.Member updated = new McpComposition.Member(existing.serverId(), existing.toolName(),
                    existing.exposedAlias(), existing.descriptionOverride(), hitl, existing.contentHash());
            resulting.put(key, updated);
            planned.put(key, plannedOf(updated, inherent, existing.hitl() == hitl ? Change.UNCHANGED
                    : Change.UPDATED));
        }
    }

    private void rejectUnacknowledgedChange(ExposureChange change, McpComposition.Member member,
            List<Rejection> rejections) {
        String key = keyOf(member.serverId(), member.toolName());
        Fingerprint.LifecycleStatus status = this.hashLedger.get(member.serverId(), member.toolName())
                .map(Fingerprint::status).orElse(Fingerprint.LifecycleStatus.ACTIVE);
        if (status != Fingerprint.LifecycleStatus.ACTIVE && !change.acknowledged().contains(key)) {
            rejections.add(new Rejection(member.serverId(), member.toolName(), "The tool definition changed since "
                    + "it was last approved (" + status + "). Review its current description and schema with "
                    + "the server tools listing, then pass it in acknowledgeChanged to accept it."));
        }
    }

    private void checkAliases(Map<String, McpComposition.Member> resulting, Map<String, PlannedMember> planned,
            List<Rejection> rejections) {
        Set<String> externalNames = this.toolSpecService.getExternalMcpToolNames();
        Set<String> nativeNames = new HashSet<>();
        this.toolSpecService.getMcpToolList().forEach(tool -> nativeNames.add(tool.name()));
        nativeNames.removeAll(externalNames);
        nativeNames.addAll(this.toolSpecService.getServedToolNames());
        Map<String, McpComposition.Member> byAlias = new HashMap<>();
        for (McpComposition.Member member : resulting.values()) {
            PlannedMember plannedMember = planned.get(keyOf(member.serverId(), member.toolName()));
            boolean touched = plannedMember != null && plannedMember.change() != Change.UNCHANGED;
            String alias = member.exposedAlias();
            if (touched && !ALIAS_PATTERN.matcher(alias).matches()) {
                rejections.add(new Rejection(member.serverId(), member.toolName(), "Alias '" + alias
                        + "' must be 1 to 64 characters of letters, digits, '_' or '-'."));
            }
            if (touched && ManagementToolNames.isReserved(alias)) {
                rejections.add(new Rejection(member.serverId(), member.toolName(),
                        ManagementToolNames.reservedMessage("Alias", alias)));
            }
            if (touched && nativeNames.contains(alias)) {
                rejections.add(new Rejection(member.serverId(), member.toolName(), "Alias '" + alias
                        + "' is already used by a tool on this server."));
            }
            McpComposition.Member other = byAlias.put(alias, member);
            if (other != null) {
                rejections.add(new Rejection(member.serverId(), member.toolName(), "Alias '" + alias
                        + "' is used by both " + other.serverId() + "/" + other.toolName() + " and "
                        + member.serverId() + "/" + member.toolName() + "."));
            }
        }
    }

    private void warnIfUnprotected(PlannedMember member, List<String> warnings) {
        if (member.change() == Change.REMOVED || member.hitl() || member.inherentRisk() == null) {
            return;
        }
        if (member.inherentRisk().ordinal() >= HITL_RECOMMENDED_FROM.ordinal()) {
            warnings.add("'" + member.alias() + "' has risk " + member.inherentRisk()
                    + " and is exposed without human approval (HITL).");
        }
    }

    private static String aliasOf(String aliasPrefix, String server, ToolPick pick, McpComposition.Member existing) {
        if (pick.alias() != null && !pick.alias().isBlank()) {
            return pick.alias().trim();
        }
        if (existing != null) {
            return existing.exposedAlias();
        }
        return aliasPrefix == null || aliasPrefix.isBlank()
                ? McpComposition.Member.defaultAlias(server, pick.name()) : aliasPrefix.trim() + pick.name();
    }

    private static PlannedMember plannedOf(McpComposition.Member member, RiskLevel inherent, Change change) {
        return new PlannedMember(member.serverId(), member.toolName(), member.exposedAlias(),
                member.descriptionOverride(), member.hitl(), inherent, change);
    }

    private static String keyOf(String server, String tool) {
        return Fingerprint.keyOf(server, tool);
    }

    private Optional<ServerContext> contextOf(String serverName) {
        Optional<McpServerInfo> server = this.exposedToolService.composableServers().stream()
                .filter(info -> info.serverName().equals(serverName)).findFirst();
        if (server.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<McpSchema.Tool>> tools = this.clientService.getToolListAsOpt(server.get());
        if (tools.isEmpty()) {
            return Optional.empty();
        }
        Map<String, McpSchema.Tool> byName = new LinkedHashMap<>();
        tools.get().forEach(tool -> byName.put(tool.name(), tool));
        return Optional.of(new ServerContext(server.get(), byName));
    }

}
