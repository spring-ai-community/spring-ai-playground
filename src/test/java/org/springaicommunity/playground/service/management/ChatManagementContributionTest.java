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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.chat.HeadlessChatService.ConversationSummary;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatRequest;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatResult;
import org.springaicommunity.playground.service.chat.HeadlessChatService.Stage;
import org.springaicommunity.playground.service.chat.HeadlessChatService.StageKind;
import org.springaicommunity.playground.service.chat.HeadlessChatService.ToolCallSummary;
import org.springaicommunity.playground.service.chat.HeadlessChatService;
import org.springaicommunity.playground.service.management.ChatManagementContribution.AskData;
import org.springaicommunity.playground.service.management.ChatManagementContribution.ConversationsData;
import org.springaicommunity.playground.service.mcp.ApprovalConfirmation;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.tool.HumanQuestion;
import org.springaicommunity.playground.service.tool.HumanQuestionHandler;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatManagementContributionTest {

    private final HeadlessChatService headlessChatService = mock(HeadlessChatService.class);
    private final ChatManagementContribution contribution = new ChatManagementContribution(headlessChatService);

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void askRunsATurnWithAnElicitingApprovalHandlerAndReportsStagesAsProgress() {
        ArgumentCaptor<HeadlessChatRequest> request = ArgumentCaptor.forClass(HeadlessChatRequest.class);
        ArgumentCaptor<HumanQuestionHandler> handler = ArgumentCaptor.forClass(HumanQuestionHandler.class);
        ArgumentCaptor<Consumer<Stage>> stage = ArgumentCaptor.forClass(Consumer.class);
        when(headlessChatService.ask(request.capture(), handler.capture(), stage.capture())).thenReturn(
                new HeadlessChatResult("Chat-1", true, "42", List.of(new ToolCallSummary("getCurrentTime", "c1",
                        Map.of(), "10:00")), "qwen", 12L, 3L, 800L));
        List<String> progress = new ArrayList<>();
        ManagementCaller caller = new ManagementCaller("cursor", "1", false, true, false,
                (value, total, message) -> progress.add(message), message -> Optional.of(
                        ApprovalConfirmation.accepted()));

        ManagementResult result = tool("saip_chat_ask").handle(Map.of("prompt", "what time is it",
                "tools", List.of("getCurrentTime"), "timeoutSeconds", 30), caller);

        assertThat(result.ok()).isTrue();
        assertThat(result.persisted()).isTrue();
        assertThat(result.message()).contains("1 tool call(s)", "Chat-1");
        AskData data = (AskData) result.data();
        assertThat(data.answer()).isEqualTo("42");
        assertThat(data.toolCalls()).singleElement().satisfies(call -> assertThat(call.name())
                .isEqualTo("getCurrentTime"));
        assertThat(request.getValue().tools()).containsExactly("getCurrentTime");
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(handler.getValue()).isInstanceOf(ElicitationQuestionHandler.class);
        stage.getValue().accept(new Stage(StageKind.TOOL_CALL, "getCurrentTime"));
        stage.getValue().accept(new Stage(StageKind.ANSWERING, null));
        assertThat(progress).containsExactly("Calling tool getCurrentTime", "Answering");
        assertThat(ChatManagementContribution.describe(new Stage(StageKind.PROMPT_SENT, "qwen")))
                .isEqualTo("Sending the prompt to qwen");
        assertThat(ChatManagementContribution.describe(new Stage(StageKind.DOCUMENTS_RETRIEVED, "3")))
                .isEqualTo("Retrieved 3 document(s)");
    }

    @Test
    void askIsAWriteLevelToolWhoseSummaryNamesTheConversationToolsAndPrompt() {
        ManagementTool ask = tool("saip_chat_ask");

        ManagementApproval fresh = ask.approvalFor(Map.of("prompt", "hello there"));
        ManagementApproval continued = ask.approvalFor(Map.of("prompt", "x".repeat(200), "conversationId", "Chat-9",
                "tools", List.of("echo")));

        assertThat(ask.level()).isEqualTo(ApprovalLevel.WRITE);
        assertThat(fresh.summary()).contains("new conversation", "without tools", "hello there");
        assertThat(continued.summary()).contains("Chat-9", "[echo]").endsWith("...");
    }

    @Test
    void listConversationsSortsNewestFirstAndPages() {
        when(headlessChatService.conversations()).thenReturn(List.of(
                new ConversationSummary("Chat-old", "old", 10L, "qwen", 2, false),
                new ConversationSummary("Chat-new", "new", 20L, "qwen", 4, false),
                new ConversationSummary("Chat-mid", "mid", 15L, "qwen", 1, true)));

        ConversationsData data = (ConversationsData) tool("saip_chat_list_conversations")
                .handle(Map.of("limit", 2), ManagementCaller.ANONYMOUS).data();

        assertThat(data.conversations()).extracting(ConversationSummary::conversationId)
                .containsExactly("Chat-new", "Chat-mid");
        assertThat(data.truncated()).isTrue();
        assertThat(data.total()).isEqualTo(3);
    }

    @Test
    void theElicitationHandlerMapsEveryDecisionToTheChatAnswer() {
        HumanQuestion question = HumanQuestion.approval("c1", "Tool approval required",
                "Run getCurrentTime with {\"timeZone\":\"UTC\"}?", RiskLevel.L4);
        ManagementCaller noElicitation = new ManagementCaller("desktop", "1", false, false, false);
        ManagementCaller accepting = caller(Optional.of(ApprovalConfirmation.accepted()));
        ManagementCaller declining = caller(Optional.of(new ElicitResult(ElicitResult.Action.DECLINE, Map.of())));
        ManagementCaller bareAccept = caller(Optional.of(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of())));
        ManagementCaller expired = caller(Optional.of(McpServerHitlToolGate.approvalExpired()));
        ManagementCaller failing = caller(Optional.empty());

        assertThat(new ElicitationQuestionHandler(noElicitation).ask(List.of(question))).containsEntry("c1",
                "Decline");
        assertThat(new ElicitationQuestionHandler(accepting).ask(List.of(question))).containsEntry("c1", "Approve");
        assertThat(new ElicitationQuestionHandler(declining).ask(List.of(question))).containsEntry("c1", "Decline");
        assertThat(new ElicitationQuestionHandler(bareAccept).ask(List.of(question))).containsEntry("c1", "Decline");
        assertThat(new ElicitationQuestionHandler(expired).ask(List.of(question))).containsEntry("c1",
                HumanQuestionHandler.TIMEOUT_ANSWER);
        assertThat(new ElicitationQuestionHandler(failing).ask(List.of(question))).containsEntry("c1", "Decline");
        assertThat(ElicitationQuestionHandler.message(question)).startsWith("[risk L4] ")
                .contains("getCurrentTime", question.caution());
    }

    private static ManagementCaller caller(Optional<ElicitResult> decision) {
        return new ManagementCaller("cursor", "1", false, true, false, ManagementProgress.NONE, message -> decision);
    }

}
