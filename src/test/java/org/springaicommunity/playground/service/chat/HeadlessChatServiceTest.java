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
package org.springaicommunity.playground.service.chat;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.agent.AgentLoopHarness;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurn;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnListeners;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnRequest;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatRequest;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatResult;
import org.springaicommunity.playground.service.chat.HeadlessChatService.Stage;
import org.springaicommunity.playground.service.chat.HeadlessChatService.StageKind;
import org.springaicommunity.playground.service.tool.HumanQuestionHandler;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeadlessChatServiceTest {

    private final ChatTurnService chatTurnService = mock(ChatTurnService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final HeadlessChatService service = new HeadlessChatService(chatTurnService, chatHistoryService,
            chatSessionService, chatService, toolSpecService);
    private final List<Message> messages = new ArrayList<>();
    private final HumanQuestionHandler approvals = HumanQuestionHandler::defaultAnswers;

    private ChatHistory history(String conversationId, ChatToolPreferences preferences) {
        DefaultChatOptions options = (DefaultChatOptions) ChatOptions.builder().model("qwen").build();
        return new ChatHistory(conversationId, null, 1L, 1L, "be brief", options, ChatExtraOptions.defaults(),
                "Ollama", preferences, () -> this.messages);
    }

    private ChatTurn turnEmitting(ArgumentCaptor<ChatTurnListeners> listeners, String... chunks) {
        ChatTurn turn = new ChatTurn(Flux.fromArray(chunks), 0);
        when(chatTurnService.send(any(ChatTurnRequest.class), listeners.capture())).thenReturn(turn);
        return turn;
    }

    @Test
    void aNewConversationStartsFromTheDefaultChatAndCollectsTheAnswerAndToolCalls() {
        ChatHistory base = history("Chat-base", ChatToolPreferences.defaults());
        when(chatSessionService.newDefaultChat()).thenReturn(base);
        ChatHistory started = history("Chat-new", ChatToolPreferences.defaults());
        when(chatSessionService.startChat(any())).thenReturn(started);
        ArgumentCaptor<ChatTurnListeners> listeners = ArgumentCaptor.forClass(ChatTurnListeners.class);
        turnEmitting(listeners, "Hel", "lo");
        this.messages.add(new UserMessage("hi"));
        List<Stage> stages = new ArrayList<>();

        HeadlessChatResult result = service.ask(new HeadlessChatRequest("hi", null, null, null, null, null),
                approvals, stages::add);
        ChatTurnListeners captured = listeners.getValue();
        captured.mcpToolProcess().accept(new AgentLoopHarness.McpAssistantToolCall("assistant",
                List.of(new AgentLoopHarness.McpToolCall("c1", "getCurrentTime", Map.of("timeZone", "UTC")))));
        captured.mcpToolProcess().accept(new AgentLoopHarness.McpToolResult("tool", "getCurrentTime", "c1",
                "2026-10-22T10:00:00Z"));
        captured.beforeHistoryCommit().accept(SignalType.ON_COMPLETE);

        assertThat(result.conversationId()).isEqualTo("Chat-new");
        assertThat(result.newConversation()).isTrue();
        assertThat(result.answer()).isEqualTo("Hello");
        assertThat(result.model()).isEqualTo("qwen");
        assertThat(stages).contains(new Stage(StageKind.PROMPT_SENT, "qwen"), new Stage(StageKind.ANSWERING, null),
                new Stage(StageKind.TOOL_CALL, "getCurrentTime"));
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chatTurnService).send(request.capture(), any());
        assertThat(request.getValue().userPrompt()).isEqualTo("hi");
        assertThat(request.getValue().chatHistory()).isSameAs(started);
        assertThat(captured.humanQuestionHandler()).isSameAs(approvals);
        assertThat(captured.fileUploadHandler().requestUpload(null).uploaded()).isFalse();
    }

    @Test
    void toolNamesBecomeToolIdsAndUnknownNamesAreRefused() {
        ChatHistory base = history("Chat-base", ChatToolPreferences.defaults());
        when(chatSessionService.newDefaultChat()).thenReturn(base);
        ToolSpec clock = mock(ToolSpec.class);
        when(clock.name()).thenReturn("getCurrentTime");
        when(clock.toolId()).thenReturn("id-clock");
        when(toolSpecService.getToolSpecList()).thenReturn(List.of(clock));
        when(toolSpecService.getExternalToolSpecs()).thenReturn(List.of());
        when(chatSessionService.startChat(any())).thenReturn(history("Chat-new", ChatToolPreferences.defaults()));
        turnEmitting(ArgumentCaptor.forClass(ChatTurnListeners.class), "ok");

        service.ask(new HeadlessChatRequest("hi", null, null, List.of("getCurrentTime"), null, null), approvals,
                stage -> { });

        ArgumentCaptor<ChatSessionService.NewChatRequest> request =
                ArgumentCaptor.forClass(ChatSessionService.NewChatRequest.class);
        verify(chatSessionService).startChat(request.capture());
        assertThat(request.getValue().toolPreferences().useBuiltinMcp()).isTrue();
        assertThat(request.getValue().toolPreferences().exposedToolIds()).containsExactly("id-clock");
        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest("hi", null, null, List.of("nope"), null,
                null), approvals, stage -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    void anExistingConversationKeepsItsPreferencesAndRefusesAnotherModel() {
        ChatHistory existing = history("Chat-1", new ChatToolPreferences(true, Set.of("id-clock"), null, Map.of(),
                ReasoningEffort.DEFAULT, false));
        when(chatHistoryService.getChatHistory("Chat-1")).thenReturn(existing);
        when(chatTurnService.providerLock(existing)).thenReturn(Optional.empty());
        turnEmitting(ArgumentCaptor.forClass(ChatTurnListeners.class), "again");

        HeadlessChatResult result = service.ask(new HeadlessChatRequest("more", "Chat-1", null, null, null, null),
                approvals, stage -> { });

        assertThat(result.newConversation()).isFalse();
        assertThat(result.answer()).isEqualTo("again");
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chatTurnService).send(request.capture(), any());
        assertThat(request.getValue().chatHistory()).isSameAs(existing);
        verify(chatSessionService, never()).startChat(any());
        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest("more", "Chat-1", "other-model", null, null,
                null), approvals, stage -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("other-model");
        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest("more", "Chat-missing", null, null, null,
                null), approvals, stage -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chat-missing");
    }

    @Test
    void aConversationLockedToAnotherProviderIsRefused() {
        ChatHistory existing = history("Chat-1", ChatToolPreferences.defaults());
        when(chatHistoryService.getChatHistory("Chat-1")).thenReturn(existing);
        when(chatTurnService.providerLock(existing)).thenReturn(Optional.of("read-only"));

        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest("more", "Chat-1", null, null, null, null),
                approvals, stage -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessage("read-only");
    }

    @Test
    void theTimeoutIsCappedAndAnUnknownModelIsRefused() {
        assertThat(new HeadlessChatRequest("hi", null, null, null, null, Duration.ofHours(5)).timeout())
                .isEqualTo(HeadlessChatService.MAX_TIMEOUT);
        assertThat(new HeadlessChatRequest("hi", null, null, null, null, null).timeout())
                .isEqualTo(HeadlessChatService.DEFAULT_TIMEOUT);
        when(chatSessionService.newDefaultChat()).thenReturn(history("Chat-base", ChatToolPreferences.defaults()));
        when(chatService.getModels()).thenReturn(List.of("qwen", "llama"));

        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest("hi", null, "gpt", null, null, null),
                approvals, stage -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gpt");
        assertThatThrownBy(() -> service.ask(new HeadlessChatRequest(" ", null, null, null, null, null),
                approvals, stage -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
