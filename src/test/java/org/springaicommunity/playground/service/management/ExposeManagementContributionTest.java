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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange;
import org.springaicommunity.playground.service.mcp.risk.ExposureChange.Operation;
import org.springaicommunity.playground.service.mcp.risk.ExposureOutcome;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.Change;
import org.springaicommunity.playground.service.mcp.risk.ExposurePlan.PlannedMember;
import org.springaicommunity.playground.service.mcp.risk.HitlPolicy;
import org.springaicommunity.playground.service.mcp.risk.McpExposureService;
import org.springaicommunity.playground.service.mcp.risk.ServerSelection;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExposeManagementContributionTest {

    private final McpExposureService exposureService = mock(McpExposureService.class);
    private final ExposeManagementContribution contribution = new ExposeManagementContribution(exposureService);

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static ExposurePlan plan(Operation operation, List<PlannedMember> members,
            List<ExposurePlan.Rejection> rejections) {
        return new ExposurePlan(operation, false, RiskLevel.L5, members, List.of(), rejections, List.of(), 0, true);
    }

    private static PlannedMember member(String alias, boolean hitl, RiskLevel risk, Change change) {
        return new PlannedMember("wiki", alias, alias, null, hitl, risk, change);
    }

    @Test
    void mixedToolFormsAndHitlRulesAreConvertedForSeveralServers() {
        when(exposureService.commit(any())).thenReturn(new ExposureOutcome(plan(Operation.ADD, List.of(), List.of()),
                false, List.of()));
        Map<String, Object> arguments = Map.of(
                "selections", List.of(
                        Map.of("server", "wiki",
                                "tools", List.of("a", Map.of("name", "b", "alias", "wiki_b", "description", "B",
                                        "hitl", false)),
                                "hitl", Map.of("default", "all", "except", List.of("a"))),
                        Map.of("server", "files", "tools", "all",
                                "hitl", Map.of("default", "risk", "atLeast", "L4"))),
                "aliasPrefix", "x_", "maxRisk", "L3",
                "acknowledgeChanged", List.of(Map.of("server", "wiki", "tool", "a")));

        tool("saip_expose_add_tools").handle(arguments, ManagementCaller.ANONYMOUS);

        ArgumentCaptor<ExposureChange> captor = ArgumentCaptor.forClass(ExposureChange.class);
        verify(exposureService).commit(captor.capture());
        ExposureChange change = captor.getValue();
        assertThat(change.operation()).isEqualTo(Operation.ADD);
        assertThat(change.aliasPrefix()).isEqualTo("x_");
        assertThat(change.maxRisk()).isEqualTo(RiskLevel.L3);
        assertThat(change.acknowledged()).containsExactly("wiki::a");
        ServerSelection wiki = change.selections().get(0);
        assertThat(wiki.all()).isFalse();
        assertThat(wiki.tools()).extracting("name", "alias", "description", "hitl")
                .containsExactly(tuple("a", null, null, null),
                        tuple("b", "wiki_b", "B", false));
        assertThat(wiki.hitl()).isEqualTo(new HitlPolicy(HitlPolicy.Mode.ALL, null, Set.of("a")));
        ServerSelection files = change.selections().get(1);
        assertThat(files.all()).isTrue();
        assertThat(files.hitl()).isEqualTo(new HitlPolicy(HitlPolicy.Mode.RISK, RiskLevel.L4, Set.of()));
    }

    @Test
    void setHitlNeedsARuleOrAPerToolFlag() {
        Map<String, Object> arguments = Map.of("selections",
                List.of(Map.of("server", "wiki", "tools", List.of("a"))));

        assertThatThrownBy(() -> tool("saip_expose_set_hitl").handle(arguments, ManagementCaller.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hitl");
    }

    @Test
    void unknownToolFieldsAndMissingToolsAreRejected() {
        Map<String, Object> typo = Map.of("selections", List.of(Map.of("server", "wiki",
                "tools", List.of(Map.of("name", "a", "hittl", true)))));
        Map<String, Object> missing = Map.of("selections", List.of(Map.of("server", "wiki")));

        assertThatThrownBy(() -> tool("saip_expose_add_tools").handle(typo, ManagementCaller.ANONYMOUS))
                .hasMessageContaining("Unknown tool field 'hittl'");
        assertThatThrownBy(() -> tool("saip_expose_add_tools").handle(missing, ManagementCaller.ANONYMOUS))
                .hasMessageContaining("must be \"all\"");
        assertThatThrownBy(() -> tool("saip_expose_add_tools").handle(Map.of("selections", List.of()),
                ManagementCaller.ANONYMOUS)).hasMessageContaining("at least one server");
    }

    @Test
    void refusedChangesReturnAFailureWithTheReasonsAndTheData() {
        ExposurePlan refused = plan(Operation.ADD, List.of(), List.of(
                new ExposurePlan.Rejection("wiki", "a", "Inherent risk L4 is above the cap L3.")));
        when(exposureService.commit(any())).thenReturn(new ExposureOutcome(refused, false, List.of()));

        ManagementResult result = tool("saip_expose_add_tools").handle(Map.of("selections",
                List.of(Map.of("server", "wiki", "tools", "all"))), ManagementCaller.ANONYMOUS);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Nothing was changed").contains("above the cap");
        assertThat(result.data()).isInstanceOf(ExposeManagementContribution.ChangeData.class);
    }

    @Test
    void appliedChangesArePersistedAndWithheldToolsBecomeWarnings() {
        ExposurePlan accepted = plan(Operation.ADD, List.of(member("wiki_a", true, RiskLevel.L1, Change.ADDED)),
                List.of());
        when(exposureService.commit(any())).thenReturn(new ExposureOutcome(accepted, true, List.of("wiki_a")));

        ManagementResult result = tool("saip_expose_add_tools").handle(Map.of("selections",
                List.of(Map.of("server", "wiki", "tools", "all"))), ManagementCaller.ANONYMOUS);

        assertThat(result.ok()).isTrue();
        assertThat(result.persisted()).isTrue();
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning).contains("wiki_a"));
    }

    @Test
    void approvalListsWhatIsAddedAndWhatHasNoHumanApproval() {
        ExposurePlan accepted = plan(Operation.ADD, List.of(
                member("wiki_a", true, RiskLevel.L1, Change.ADDED),
                member("wiki_b", false, RiskLevel.L3, Change.ADDED),
                member("wiki_c", true, RiskLevel.L2, Change.UNCHANGED)), List.of());
        when(exposureService.plan(any())).thenReturn(accepted);

        ManagementApproval approval = tool("saip_expose_add_tools").approvalFor(Map.of("selections",
                List.of(Map.of("server", "wiki", "tools", "all"))));

        assertThat(approval.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        assertThat(approval.highRisk()).isFalse();
        assertThat(approval.summary()).contains("Added: wiki_a (L1) HITL, wiki_b (L3)")
                .contains("WITHOUT human approval: wiki_b (L3)").contains("Risk cap L5");
    }

    @Test
    void highRiskAdditionsMarkTheApprovalAsHighRisk() {
        when(exposureService.plan(any())).thenReturn(plan(Operation.ADD,
                List.of(member("wiki_a", true, RiskLevel.L4, Change.ADDED)), List.of()));

        ManagementApproval approval = tool("saip_expose_add_tools").approvalFor(Map.of("selections",
                List.of(Map.of("server", "wiki", "tools", "all"))));

        assertThat(approval.highRisk()).isTrue();
    }

    @Test
    void refusedOrEmptyPlansDoNotAskForApproval() {
        when(exposureService.plan(any())).thenReturn(plan(Operation.ADD, List.of(),
                List.of(new ExposurePlan.Rejection("wiki", "a", "no"))));
        Map<String, Object> arguments = Map.of("selections", List.of(Map.of("server", "wiki", "tools", "all")));

        assertThat(tool("saip_expose_add_tools").approvalFor(arguments).level()).isEqualTo(ApprovalLevel.READ);

        when(exposureService.plan(any())).thenReturn(plan(Operation.ADD,
                List.of(member("wiki_a", true, RiskLevel.L1, Change.UNCHANGED)), List.of()));

        assertThat(tool("saip_expose_add_tools").approvalFor(arguments).level()).isEqualTo(ApprovalLevel.READ);
    }

    @Test
    void previewNeverCommits() {
        when(exposureService.plan(any())).thenReturn(plan(Operation.REMOVE,
                List.of(member("wiki_a", true, RiskLevel.L1, Change.REMOVED)), List.of()));

        ManagementResult result = tool("saip_expose_preview").handle(Map.of("operation", "remove", "selections",
                List.of(Map.of("server", "wiki", "tools", "all"))), ManagementCaller.ANONYMOUS);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).startsWith("Preview:");
        verify(exposureService, never()).commit(any());
    }

}
