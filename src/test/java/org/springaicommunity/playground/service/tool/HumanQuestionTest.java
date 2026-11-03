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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.HumanQuestion.Option;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HumanQuestionTest {

    @Test
    void approvalQuestionsDefaultToDeclineAndRecogniseTheApproveOption() {
        HumanQuestion approval = HumanQuestion.approval("q1", "Approve action", "Run rm?", RiskLevel.L5);

        assertThat(approval.defaultAnswer()).isEqualTo("Decline");
        assertThat(approval.approveLabel()).isEqualTo("Approve");
        assertThat(approval.isApproval("Approve")).isTrue();
        assertThat(approval.isApproval("Decline")).isFalse();
        assertThat(approval.requiresAcknowledgement()).isTrue();
        assertThat(approval.isElevatedRisk()).isTrue();
        assertThat(approval.caution()).startsWith("Critical risk");
    }

    @Test
    void customOptionsUseTheLastOptionAsTheDefaultAndLowRiskHasNoCaution() {
        HumanQuestion pick = new HumanQuestion("q2", null, "Pick one",
                List.of(new Option("Yes", null), new Option("Later", null), new Option("Never", null)),
                false, RiskLevel.L4);
        HumanQuestion bare = new HumanQuestion("q3", null, "Empty", List.of(), false, RiskLevel.L1);

        assertThat(pick.defaultAnswer()).isEqualTo("Never");
        assertThat(pick.isApproval("Yes")).isTrue();
        assertThat(pick.requiresAcknowledgement()).isFalse();
        assertThat(pick.isElevatedRisk()).isTrue();
        assertThat(pick.caution()).startsWith("High risk");
        assertThat(bare.defaultAnswer()).isEmpty();
        assertThat(bare.approveLabel()).isEmpty();
        assertThat(bare.isApproval("")).isFalse();
        assertThat(bare.isElevatedRisk()).isFalse();
        assertThat(bare.caution()).isNull();
    }

    @Test
    void handlerHelpersAnswerEveryQuestionWithItsDefaultOrTheTimeoutMarker() {
        List<HumanQuestion> questions = List.of(
                HumanQuestion.approval("a", null, "one"),
                new HumanQuestion("b", null, "two", List.of(new Option("Go", null), new Option("Stop", null)),
                        false, null));

        assertThat(HumanQuestionHandler.defaultAnswers(questions))
                .containsExactly(Map.entry("a", "Decline"), Map.entry("b", "Stop"));
        assertThat(HumanQuestionHandler.timeoutAnswers(questions))
                .containsExactly(Map.entry("a", HumanQuestionHandler.TIMEOUT_ANSWER),
                        Map.entry("b", HumanQuestionHandler.TIMEOUT_ANSWER));
    }
}
