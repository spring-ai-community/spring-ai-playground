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

import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange.Operation;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.Change;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger.Fingerprint;
import org.springaicommunity.playground.service.mcp.risk.McpToolPoisoningScanner.ScanResult;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator.ToolRiskView;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExposureServiceTest {

    private final McpCompositionService compositionService = mock(McpCompositionService.class);
    private final McpExposedToolService exposedToolService = mock(McpExposedToolService.class);
    private final McpClientService clientService = mock(McpClientService.class);
    private final McpToolRiskEvaluator riskEvaluator = mock(McpToolRiskEvaluator.class);
    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final McpToolHashLedger hashLedger = mock(McpToolHashLedger.class);
    private final McpExposureService service = new McpExposureService(compositionService, exposedToolService,
            clientService, riskEvaluator, toolSpecService, hashLedger);
    private final McpServerInfo wiki = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "wiki", "", 0L, 0L, "{}");
    private final McpServerInfo files = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "files", "", 0L, 0L, "{}");
    private final Map<String, RiskLevel> risks = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(compositionService.exposedManagedByConfiguration()).thenReturn(false);
        when(compositionService.getExposed()).thenReturn(Optional.empty());
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BOTH);
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of());
        when(toolSpecService.getMcpToolList()).thenReturn(List.of());
        when(toolSpecService.getServedToolNames()).thenReturn(Set.of());
        when(hashLedger.get(any(), any())).thenReturn(Optional.empty());
        when(exposedToolService.composableServers()).thenReturn(List.of(wiki, files));
        offer(wiki, "read_structure", RiskLevel.L1);
        offer(wiki, "read_contents", RiskLevel.L2);
        offer(files, "delete_file", RiskLevel.L4);
        offer(files, "read_file", RiskLevel.L2);
        when(riskEvaluator.evaluateTool(any(), any(Tool.class))).thenAnswer(invocation -> {
            Tool tool = invocation.getArgument(1);
            RiskLevel level = risks.get(tool.name());
            return new ToolRiskView(level, level, level, null, new ScanResult(true, List.of()));
        });
    }

    private void offer(McpServerInfo server, String toolName, RiskLevel level) {
        List<Tool> tools = new ArrayList<>(clientService.getToolListAsOpt(server).orElse(List.of()));
        tools.add(Tool.builder().name(toolName).description(toolName + " description")
                .inputSchema(Map.of("type", "object")).build());
        when(clientService.getToolListAsOpt(server)).thenReturn(Optional.of(tools));
        risks.put(toolName, level);
    }

    private void exposeAlready(McpComposition.Member... members) {
        when(compositionService.getExposed()).thenReturn(Optional.of(new McpComposition(
                McpCompositionService.EXPOSED_ID, "Exposed Tools", "", List.of(members), true, RiskLevel.L5,
                0L, 0L, 0L)));
    }

    private static ExposureChange add(ServerSelection... selections) {
        return ExposureChange.of(Operation.ADD, List.of(selections));
    }

    @Test
    void addingAllToolsOfAServerDefaultsToHumanApprovalForEveryTool() {
        ExposurePlan plan = service.plan(add(ServerSelection.allTools("wiki", null)));

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.members()).extracting(ExposurePlan.PlannedMember::alias)
                .containsExactlyInAnyOrder("wiki_read_structure", "wiki_read_contents");
        assertThat(plan.members()).allMatch(ExposurePlan.PlannedMember::hitl);
        assertThat(plan.members()).allMatch(member -> member.change() == Change.ADDED);
    }

    @Test
    void severalServersAreCombinedWithTheirOwnHitlRules() {
        ExposurePlan plan = service.plan(add(
                ServerSelection.of("wiki", List.of(ToolPick.of("read_structure"), ToolPick.of("read_contents")),
                        new HitlPolicy(HitlPolicy.Mode.ALL, null, Set.of("read_structure"))),
                ServerSelection.allTools("files", new HitlPolicy(HitlPolicy.Mode.RISK, RiskLevel.L3, Set.of()))));

        assertThat(plan.accepted()).isTrue();
        Map<String, Boolean> hitlByAlias = new HashMap<>();
        plan.members().forEach(member -> hitlByAlias.put(member.alias(), member.hitl()));
        assertThat(hitlByAlias).containsEntry("wiki_read_structure", false)
                .containsEntry("wiki_read_contents", true)
                .containsEntry("files_delete_file", true)
                .containsEntry("files_read_file", false);
    }

    @Test
    void perToolOverridesBeatThePolicyAndCustomAliasAndDescriptionAreKept() {
        ExposurePlan plan = service.plan(add(ServerSelection.of("wiki",
                List.of(new ToolPick("read_contents", "wiki_read", "Read a page", false)),
                HitlPolicy.ALL_TOOLS)));

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.members()).singleElement().satisfies(member -> {
            assertThat(member.alias()).isEqualTo("wiki_read");
            assertThat(member.hitl()).isFalse();
            assertThat(member.description()).isEqualTo("Read a page");
        });
        assertThat(plan.resultingMembers()).singleElement()
                .satisfies(member -> assertThat(member.descriptionOverride()).isEqualTo("Read a page"));
    }

    @Test
    void toolsAboveTheRiskCapAreRejectedAndNothingIsApplied() {
        ExposureChange change = new ExposureChange(Operation.ADD, List.of(ServerSelection.allTools("files", null)),
                null, RiskLevel.L3, Set.of());

        ExposureOutcome outcome = service.commit(change);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.plan().rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.tool()).isEqualTo("delete_file"));
        verify(exposedToolService, never()).apply(any(), any(), any());
    }

    @Test
    void unknownServersAndToolsAreRejected() {
        ExposurePlan plan = service.plan(add(ServerSelection.allTools("ghost", null),
                ServerSelection.of("wiki", List.of(ToolPick.of("nope")), null)));

        assertThat(plan.rejections()).extracting(ExposurePlan.Rejection::reason)
                .anyMatch(reason -> reason.contains("not registered or not connected"))
                .anyMatch(reason -> reason.contains("does not offer"));
    }

    @Test
    void duplicateAliasesAndReservedAliasesAreRejected() {
        ExposurePlan duplicate = service.plan(add(ServerSelection.of("wiki", List.of(
                new ToolPick("read_structure", "same", null, null),
                new ToolPick("read_contents", "same", null, null)), null)));
        ExposurePlan reserved = service.plan(add(ServerSelection.of("wiki", List.of(
                new ToolPick("read_structure", "saip_read", null, null)), null)));
        ExposurePlan malformed = service.plan(add(ServerSelection.of("wiki", List.of(
                new ToolPick("read_structure", "bad name", null, null)), null)));

        assertThat(duplicate.rejections()).anyMatch(rejection -> rejection.reason().contains("used by both"));
        assertThat(reserved.rejections()).anyMatch(rejection -> rejection.reason().contains("reserved"));
        assertThat(malformed.rejections()).anyMatch(rejection -> rejection.reason().contains("must be 1 to 64"));
    }

    @Test
    void anAliasThatClashesWithANativeToolIsRejected() {
        when(toolSpecService.getMcpToolList()).thenReturn(List.of(
                Tool.builder().name("wiki_read_structure").description("native").inputSchema(Map.of("type", "object"))
                        .build()));

        ExposurePlan plan = service.plan(add(ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")),
                null)));

        assertThat(plan.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason()).contains("already used by a tool"));
    }

    @Test
    void addingKeepsWhatIsAlreadyExposed() {
        McpComposition.Member existing = new McpComposition.Member("wiki", "read_structure", "wiki_read_structure",
                null, true, "hash");
        exposeAlready(existing);

        ExposurePlan plan = service.plan(add(ServerSelection.of("files", List.of(ToolPick.of("read_file")), null)));

        assertThat(plan.resultingMembers()).hasSize(2).contains(existing);
        assertThat(plan.exposedBefore()).isEqualTo(1);
        assertThat(plan.exposedAfter()).isEqualTo(2);
    }

    @Test
    void removingOneToolLeavesTheOthersAndAllRemovesAServer() {
        McpComposition.Member first = new McpComposition.Member("wiki", "read_structure", "a", null, true, "");
        McpComposition.Member second = new McpComposition.Member("wiki", "read_contents", "b", null, true, "");
        McpComposition.Member other = new McpComposition.Member("files", "read_file", "c", null, true, "");
        exposeAlready(first, second, other);

        ExposurePlan one = service.plan(ExposureChange.of(Operation.REMOVE,
                List.of(ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")), null))));
        ExposurePlan all = service.plan(ExposureChange.of(Operation.REMOVE,
                List.of(ServerSelection.allTools("wiki", null))));

        assertThat(one.resultingMembers()).containsExactlyInAnyOrder(second, other);
        assertThat(all.resultingMembers()).containsExactly(other);
    }

    @Test
    void removingSomethingNotExposedOnlyWarns() {
        ExposurePlan plan = service.plan(ExposureChange.of(Operation.REMOVE,
                List.of(ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")), null))));

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.warnings()).anyMatch(warning -> warning.contains("not exposed"));
    }

    @Test
    void settingHitlChangesOnlyTheFlagOfExposedTools() {
        McpComposition.Member existing = new McpComposition.Member("wiki", "read_contents", "b", "custom", true, "h");
        exposeAlready(existing);

        ExposurePlan plan = service.plan(ExposureChange.of(Operation.SET_HITL, List.of(ServerSelection.of("wiki",
                List.of(new ToolPick("read_contents", null, null, false)), null))));

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.resultingMembers()).singleElement().satisfies(member -> {
            assertThat(member.hitl()).isFalse();
            assertThat(member.exposedAlias()).isEqualTo("b");
            assertThat(member.descriptionOverride()).isEqualTo("custom");
            assertThat(member.contentHash()).isEqualTo("h");
        });
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void settingHitlOnANotExposedToolIsRejected() {
        ExposurePlan plan = service.plan(ExposureChange.of(Operation.SET_HITL, List.of(ServerSelection.of("wiki",
                List.of(new ToolPick("read_contents", null, null, false)), null))));

        assertThat(plan.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason()).contains("not exposed"));
    }

    @Test
    void replaceDropsEverythingNotListed() {
        McpComposition.Member old = new McpComposition.Member("files", "read_file", "c", null, true, "");
        exposeAlready(old);

        ExposurePlan plan = service.plan(ExposureChange.of(Operation.REPLACE,
                List.of(ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")), null))));

        assertThat(plan.resultingMembers()).singleElement()
                .satisfies(member -> assertThat(member.toolName()).isEqualTo("read_structure"));
        assertThat(plan.members()).anyMatch(member -> member.tool().equals("read_file")
                && member.change() == Change.REMOVED);
    }

    @Test
    void aChangedDefinitionNeedsAnExplicitAcknowledgement() {
        when(hashLedger.get("wiki", "read_structure")).thenReturn(Optional.of(new Fingerprint("wiki",
                "read_structure", "hash", null, 0L, null, null, Fingerprint.LifecycleStatus.AWAITING_REREVIEW)));
        ServerSelection selection = ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")), null);

        ExposurePlan refused = service.plan(add(selection));
        ExposurePlan accepted = service.plan(new ExposureChange(Operation.ADD, List.of(selection), null, null,
                Set.of(Fingerprint.keyOf("wiki", "read_structure"))));

        assertThat(refused.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason()).contains("acknowledgeChanged"));
        assertThat(accepted.accepted()).isTrue();
    }

    @Test
    void commitAppliesOnlyAcknowledgedTools() {
        ServerSelection selection = ServerSelection.of("wiki", List.of(ToolPick.of("read_structure")), null);
        Set<String> keys = Set.of(Fingerprint.keyOf("wiki", "read_structure"));

        ExposureOutcome outcome = service.commit(new ExposureChange(Operation.ADD, List.of(selection), null, null,
                keys));

        assertThat(outcome.applied()).isTrue();
        verify(exposedToolService).apply(any(), any(), eq(keys));
        assertThat(outcome.withheld()).containsExactly("wiki_read_structure");
    }

    @Test
    void commitReportsToolsThatAreConfiguredButNotVisible() {
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("wiki_read_contents"));

        ExposureOutcome outcome = service.commit(add(ServerSelection.of("wiki",
                List.of(ToolPick.of("read_structure"), ToolPick.of("read_contents")), null)));

        assertThat(outcome.withheld()).containsExactly("wiki_read_structure");
    }

    @Test
    void anUnchangedRequestAppliesNothing() {
        McpComposition.Member existing = new McpComposition.Member("wiki", "read_structure", "wiki_read_structure",
                null, true, "h");
        exposeAlready(existing);

        ExposureOutcome outcome = service.commit(add(ServerSelection.of("wiki",
                List.of(ToolPick.of("read_structure")), null)));

        assertThat(outcome.applied()).isFalse();
        verify(exposedToolService, never()).apply(any(), any(), anySet());
    }

    @Test
    void everythingIsRejectedWhileConfigurationManagesTheSet() {
        when(compositionService.exposedManagedByConfiguration()).thenReturn(true);

        ExposurePlan plan = service.plan(add(ServerSelection.allTools("wiki", null)));

        assertThat(plan.managedByConfiguration()).isTrue();
        assertThat(plan.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason())
                        .contains(McpExposedToolService.COMPOSED_TOOLS_PROPERTY));
    }

    @Test
    void warningsCoverUnprotectedRiskyToolsAndHiddenComposedTools() {
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BUILTIN_ONLY);
        ExposureChange change = new ExposureChange(Operation.ADD, List.of(ServerSelection.of("files",
                List.of(ToolPick.of("delete_file")), new HitlPolicy(HitlPolicy.Mode.NONE, null, Set.of()))),
                null, RiskLevel.L5, Set.of());

        ExposurePlan plan = service.plan(change);

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.warnings()).anyMatch(warning -> warning.contains("exposed without human approval"))
                .anyMatch(warning -> warning.contains("hides composed tools"));
    }

    @Test
    void theViewShowsExposedTools() {
        McpComposition.Member existing = new McpComposition.Member("wiki", "read_structure", "wiki_read_structure",
                null, true, "h");
        exposeAlready(existing);
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("wiki_read_structure"));

        ExposureView view = service.view();

        assertThat(view.members()).singleElement().satisfies(member -> {
            assertThat(member.exposed()).isTrue();
            assertThat(member.serverConnected()).isTrue();
            assertThat(member.inherentRisk()).isEqualTo(RiskLevel.L1);
            assertThat(member.fingerprint()).isEqualTo("NEW");
        });
        assertThat(view.mode()).isEqualTo(ExposureMode.BOTH);
    }

}
