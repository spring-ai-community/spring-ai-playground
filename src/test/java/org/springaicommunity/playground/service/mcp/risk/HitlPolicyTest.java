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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HitlPolicyTest {

    @Test
    void allWithExceptionsFlipsOnlyTheListedTools() {
        HitlPolicy policy = new HitlPolicy(HitlPolicy.Mode.ALL, null, Set.of("read"));

        assertThat(policy.requires("read", RiskLevel.L1)).isFalse();
        assertThat(policy.requires("write", RiskLevel.L1)).isTrue();
    }

    @Test
    void noneWithExceptionsRequiresApprovalOnlyForTheListedTools() {
        HitlPolicy policy = new HitlPolicy(HitlPolicy.Mode.NONE, null, Set.of("delete"));

        assertThat(policy.requires("delete", RiskLevel.L1)).isTrue();
        assertThat(policy.requires("read", RiskLevel.L5)).isFalse();
    }

    @Test
    void riskModeUsesTheThresholdAndDefaultsToL3() {
        HitlPolicy policy = new HitlPolicy(HitlPolicy.Mode.RISK, null, Set.of());

        assertThat(policy.atLeast()).isEqualTo(RiskLevel.L3);
        assertThat(policy.requires("a", RiskLevel.L2)).isFalse();
        assertThat(policy.requires("b", RiskLevel.L3)).isTrue();
        assertThat(policy.requires("c", RiskLevel.L5)).isTrue();
    }

    @Test
    void anUnsetModeMeansEveryToolNeedsApproval() {
        assertThat(new HitlPolicy(null, null, null).requires("any", RiskLevel.L0)).isTrue();
        assertThat(HitlPolicy.ALL_TOOLS.requires("any", RiskLevel.L0)).isTrue();
    }

}
