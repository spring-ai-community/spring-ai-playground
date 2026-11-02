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

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.mcp.ApprovalConfirmation;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.tool.HumanQuestion;
import org.springaicommunity.playground.service.tool.HumanQuestionHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ElicitationQuestionHandler implements HumanQuestionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ElicitationQuestionHandler.class);

    private final ManagementCaller caller;

    public ElicitationQuestionHandler(ManagementCaller caller) {
        this.caller = caller;
    }

    @Override
    public Map<String, String> ask(List<HumanQuestion> questions) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (HumanQuestion question : questions) {
            answers.put(question.id(), answer(question));
        }
        return answers;
    }

    private String answer(HumanQuestion question) {
        if (!this.caller.supportsElicitation()) {
            logger.info("mgmt.chat.hitl.declined reason=no-elicitation-capability question={}", question.header());
            return question.defaultAnswer();
        }
        Optional<ElicitResult> decision = this.caller.elicitation().confirm(message(question));
        if (decision.isEmpty()) {
            logger.info("mgmt.chat.hitl.declined reason=elicitation-failed question={}", question.header());
            return question.defaultAnswer();
        }
        if (McpServerHitlToolGate.isApprovalExpired(decision.get())) {
            logger.info("mgmt.chat.hitl.timeout question={}", question.header());
            return TIMEOUT_ANSWER;
        }
        if (ApprovalConfirmation.isApproved(decision.get())) {
            logger.info("mgmt.chat.hitl.approved question={}", question.header());
            return question.approveLabel();
        }
        logger.info("mgmt.chat.hitl.declined reason={} question={}", decision.get().action(), question.header());
        return question.defaultAnswer();
    }

    static String message(HumanQuestion question) {
        String risk = question.riskLevel() == null ? "" : "[risk " + question.riskLevel() + "] ";
        String caution = question.isElevatedRisk() ? " " + question.caution() : "";
        return risk + "Chat turn started by this client wants to run a tool. " + question.question() + caution;
    }

}
