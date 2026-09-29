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
import org.springaicommunity.playground.service.agent.AgentLoopHarness;
import org.springaicommunity.playground.service.chat.ChatService.ChatMeta;
import org.springaicommunity.playground.service.chat.ChatService.RoundUsage;
import org.springaicommunity.playground.service.chat.ChatTurnRecorder.TokenUsage;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnRecorderTest {

    private static final String BLOCK = "```saip-action\n{\"type\":\"map\"}\n```";

    private ChatTurnRecorder recorder() {
        ChatHistory chatHistory = mock(ChatHistory.class);
        when(chatHistory.conversationId()).thenReturn("Chat-1");
        ChatTurnRecorder recorder = new ChatTurnRecorder(chatHistory, 1_000L);
        verify(chatHistory).updateLastMessageTimestamp(1_000L);
        return recorder;
    }

    @Test
    void stagesAccumulateAndFinishWritesEveryMetadataKey() {
        ChatTurnRecorder recorder = recorder();
        recorder.ragDocumentsRetrieved(2, List.of("a.txt", "b.txt"));
        recorder.ragProcess(1_100L, "searching\n\n", false);
        recorder.ragProcess(1_200L, "done\n\n", true);
        recorder.thinkProcess(1_300L, "hmm");
        recorder.mcpProcess(1_400L, "call\n\n", new AgentLoopHarness.McpAssistantToolCall("assistant",
                List.of(new AgentLoopHarness.McpToolCall("1", "getWeather", Map.of()),
                        new AgentLoopHarness.McpToolCall("2", "getWeather", Map.of()))), false);
        recorder.mcpProcess(1_500L, "result\n\n", new AgentLoopHarness.McpToolResult("tool", "getWeather", "1",
                "ok\n\n" + BLOCK), false);
        recorder.mcpProcess(1_600L, "completed\n\n", AgentLoopHarness.MCP_TOOL_EXECUTION_COMPLETED_MESSAGE, true);
        recorder.applyRoundUsage(new RoundUsage(10, 5, 15, true, true));
        recorder.applyRoundUsage(new RoundUsage(20, 7, 27, true, false));
        recorder.applyRoundUsage(new RoundUsage(30, 9, 39, false, false));
        recorder.assistantStreaming();
        recorder.assistantStarted(2_000L);

        assertThat(recorder.stage()).isEqualTo(ChatTurnRecorder.STAGE_ASSISTANT);
        assertThat(recorder.ragDocCount()).isEqualTo(2);
        assertThat(recorder.ragDocTitles()).containsExactly("a.txt", "b.txt");
        assertThat(recorder.mcpCallCount()).isEqualTo(2);
        assertThat(recorder.mcpToolNames()).containsExactly("getWeather");
        assertThat(recorder.mcpUsage()).contains(new TokenUsage(20, 7));
        assertThat(recorder.thinkUsage()).contains(new TokenUsage(10, 5));
        assertThat(recorder.hasPendingActionBlocks()).isTrue();
        assertThat(recorder.takeActionBlocks("prose")).containsExactly(BLOCK);
        assertThat(recorder.hasPendingActionBlocks()).isFalse();

        Map<String, Object> userMetadata = new HashMap<>();
        recorder.stampUserMessage(userMetadata);
        assertThat(userMetadata).containsEntry(ChatHistoryPersistenceService.CONVERSATION_ID, "Chat-1")
                .containsEntry(ChatHistory.TIMESTAMP, 1_000L);

        Map<String, Object> metadata = new HashMap<>();
        recorder.finish(metadata, List.of(BLOCK), new ChatMeta("qwen3", null, List.of()), 3_000L);

        assertThat(metadata)
                .containsEntry(ChatTurnRecorder.RAG_PROCESS_TIMESTAMP, 1_100L)
                .containsEntry(ChatTurnRecorder.RAG_PROCESS_END_TIMESTAMP, 1_200L)
                .containsEntry(ChatTurnRecorder.RAG_PROCESS_DOC_COUNT, 2)
                .containsEntry(ChatTurnRecorder.RAG_PROCESS_DOC_TITLES, List.of("a.txt", "b.txt"))
                .containsEntry(ChatTurnRecorder.RAG_PROCESS_MESSAGES, "searching\n\ndone\n\n")
                .containsEntry(ChatTurnRecorder.THINK_PROCESS_TIMESTAMP, 1_300L)
                .containsEntry(ChatTurnRecorder.THINK_PROCESS_END_TIMESTAMP, 3_000L)
                .containsEntry(ChatTurnRecorder.THINK_PROCESS_MESSAGES, "hmm")
                .containsEntry(ChatTurnRecorder.THINK_PROCESS_PROMPT_TOKENS, 10L)
                .containsEntry(ChatTurnRecorder.THINK_PROCESS_COMPLETION_TOKENS, 5L)
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_TIMESTAMP, 1_400L)
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_END_TIMESTAMP, 1_600L)
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_CALL_COUNT, 2)
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_TOOL_NAMES, List.of("getWeather"))
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_PROMPT_TOKENS, 20L)
                .containsEntry(ChatTurnRecorder.MCP_TOOL_PROCESS_COMPLETION_TOKENS, 7L)
                .containsEntry(ChatTurnRecorder.ACTION_BLOCKS, List.of(BLOCK))
                .containsEntry(ChatHistory.TIMESTAMP, 2_000L)
                .containsEntry(ChatTurnRecorder.RESPONSE_DURATION_MS, 2_000L)
                .containsEntry(ChatTurnRecorder.RESPONSE_MODEL, "qwen3")
                .containsEntry(ChatTurnRecorder.RESPONSE_PROMPT_TOKENS, 60L)
                .containsEntry(ChatTurnRecorder.RESPONSE_COMPLETION_TOKENS, 21L)
                .containsEntry(ChatTurnRecorder.RESPONSE_TOTAL_TOKENS, 81L)
                .doesNotContainKey(ChatTurnRecorder.STREAM_STATUS);
    }

    @Test
    void usageFallsBackToChatMetaAndStatusIsWrittenWithTheStage() {
        ChatTurnRecorder recorder = recorder();
        recorder.thinkProcess(1_100L, "thinking");
        recorder.markError("boom");
        Map<String, Object> metadata = new HashMap<>();

        recorder.finish(metadata, List.of(), new ChatMeta("m", new DefaultUsage(3, 4, 7), List.of()), 5_000L);

        assertThat(recorder.responded()).isFalse();
        assertThat(metadata).containsEntry(ChatHistory.TIMESTAMP, 5_000L)
                .containsEntry(ChatTurnRecorder.STREAM_STATUS, ChatTurnRecorder.STATUS_ERROR)
                .containsEntry(ChatTurnRecorder.STREAM_STATUS_STAGE, ChatTurnRecorder.STAGE_THINK)
                .containsEntry(ChatTurnRecorder.STREAM_STATUS_MESSAGE, "boom")
                .doesNotContainKey(ChatTurnRecorder.RESPONSE_DURATION_MS)
                .doesNotContainKey(ChatTurnRecorder.RAG_PROCESS_MESSAGES);

        ChatTurnRecorder responded = recorder();
        responded.assistantStarted(1_500L);
        responded.markStopped();
        Map<String, Object> respondedMetadata = new HashMap<>();
        responded.finish(respondedMetadata, null, new ChatMeta("m", new DefaultUsage(3, 4, 7), List.of()), 2_500L);
        assertThat(respondedMetadata).containsEntry(ChatTurnRecorder.RESPONSE_PROMPT_TOKENS, 3)
                .containsEntry(ChatTurnRecorder.RESPONSE_COMPLETION_TOKENS, 4)
                .containsEntry(ChatTurnRecorder.RESPONSE_TOTAL_TOKENS, 7)
                .containsEntry(ChatTurnRecorder.RESPONSE_DURATION_MS, 1_500L)
                .containsEntry(ChatTurnRecorder.STREAM_STATUS, ChatTurnRecorder.STATUS_STOPPED)
                .containsEntry(ChatTurnRecorder.STREAM_STATUS_STAGE, ChatTurnRecorder.STAGE_STARTING)
                .doesNotContainKey(ChatTurnRecorder.STREAM_STATUS_MESSAGE);
    }
}
