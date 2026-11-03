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
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurn;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnListeners;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnRequest;
import org.springaicommunity.playground.service.tool.ChatImageStore;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ChatTurnServiceTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ChatToolResolver toolResolver = mock(ChatToolResolver.class);
    private final ChatTurnService service = new ChatTurnService(chatService, chatHistoryService, toolResolver);

    private ChatHistory history(String provider) {
        ChatHistory chatHistory = mock(ChatHistory.class);
        when(chatHistory.provider()).thenReturn(provider);
        when(chatHistory.toolPreferences()).thenReturn(new ChatToolPreferences(true, Set.of("id"), "doc:1",
                Map.of(), ReasoningEffort.HIGH, false));
        return chatHistory;
    }

    private static ChatTurnListeners listeners(Consumer<Object> mcp, Consumer<Object> think) {
        return new ChatTurnListeners(mcp, message -> { }, think, round -> { }, signal -> { }, null, null, null,
                chatHistory -> { });
    }

    @Test
    void providerLockOnlyTriggersForADifferentProvider() {
        when(chatService.getChatModelProvider()).thenReturn("Ollama");

        assertThat(service.providerLock(history(null))).isEmpty();
        assertThat(service.providerLock(history("ollama"))).isEmpty();
        assertThat(service.providerLock(history("OpenAi"))).contains("This conversation was created with OpenAi "
                + "but the app is now running Ollama. It is read-only - start a new chat to continue.");
        assertThatThrownBy(() -> service.send(new ChatTurnRequest(history("OpenAi"), "hi", null),
                listeners(message -> { }, message -> { }))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
        verify(chatHistoryService, never()).updateChatHistory(any());
    }

    @Test
    void sendPersistsResolvesToolsAndSavesOnceOnFirstActivity() {
        when(chatService.getChatModelProvider()).thenReturn("Ollama");
        ChatHistory chatHistory = history("Ollama");
        ToolCallback callback = mock(ToolCallback.class);
        when(toolResolver.resolve(any())).thenReturn(List.of(callback));
        ArgumentCaptor<Consumer<Object>> mcpConsumer = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Object>> thinkConsumer = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<List<Media>> media = ArgumentCaptor.forClass(List.class);
        when(chatService.stream(eq(chatHistory), eq("hello"), eq("doc:1"), any(), anyList(), mcpConsumer.capture(),
                any(), thinkConsumer.capture(), any(), any(), isNull(), isNull(), isNull(), eq(ReasoningEffort.HIGH),
                media.capture())).thenReturn(Flux.just("a", "b"));
        List<Object> seenMcp = new ArrayList<>();
        List<Object> seenThink = new ArrayList<>();

        ChatTurn turn = service.send(new ChatTurnRequest(chatHistory, "hello",
                        List.of(new ChatImageStore.Staged("h1", "pic.png", "image/png", new byte[]{1, 2}))),
                listeners(seenMcp::add, seenThink::add));

        assertThat(turn.toolCount()).isEqualTo(1);
        verify(chatHistoryService, times(1)).updateChatHistory(chatHistory);
        assertThat(media.getValue()).singleElement().satisfies(image -> {
            assertThat(image.getId()).isEqualTo("h1");
            assertThat(image.getName()).isEqualTo("pic.png");
            assertThat(image.getMimeType().toString()).isEqualTo("image/png");
        });

        mcpConsumer.getValue().accept("tool call");
        thinkConsumer.getValue().accept("thinking");
        assertThat(turn.content().collectList().block()).containsExactly("a", "b");
        assertThat(seenMcp).containsExactly("tool call");
        assertThat(seenThink).containsExactly("thinking");
        verify(chatHistoryService, times(2)).updateChatHistory(chatHistory);
    }
}
