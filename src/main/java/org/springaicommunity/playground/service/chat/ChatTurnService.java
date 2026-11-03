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

import org.springaicommunity.playground.service.tool.ChatImageStore;
import org.springaicommunity.playground.service.tool.FileUploadHandler;
import org.springaicommunity.playground.service.tool.HumanQuestionHandler;
import org.springaicommunity.playground.service.tool.ImageReferenceHandler;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class ChatTurnService {

    public record ChatTurnRequest(ChatHistory chatHistory, String userPrompt, List<ChatImageStore.Staged> images) {

        public ChatTurnRequest {
            if (chatHistory == null) throw new IllegalArgumentException("chatHistory is required");
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    public record ChatTurnListeners(Consumer<Object> mcpToolProcess, Consumer<Object> ragProcess,
            Consumer<Object> thinkProcess, Consumer<ChatService.RoundUsage> roundUsage,
            Consumer<SignalType> beforeHistoryCommit, HumanQuestionHandler humanQuestionHandler,
            FileUploadHandler fileUploadHandler, ImageReferenceHandler imageReferenceHandler,
            Consumer<ChatHistory> completeChatHistory) {}

    public record ChatTurn(Flux<String> content, int toolCount) {}

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ChatToolResolver toolResolver;

    public ChatTurnService(ChatService chatService, ChatHistoryService chatHistoryService,
            ChatToolResolver toolResolver) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.toolResolver = toolResolver;
    }

    public Optional<String> providerLock(ChatHistory chatHistory) {
        String savedProvider = chatHistory.provider();
        String currentProvider = this.chatService.getChatModelProvider();
        if (savedProvider == null || savedProvider.isBlank() || savedProvider.equalsIgnoreCase(currentProvider))
            return Optional.empty();
        return Optional.of("This conversation was created with " + savedProvider + " but the app is now running "
                + currentProvider + ". It is read-only - start a new chat to continue.");
    }

    public ChatTurn send(ChatTurnRequest request, ChatTurnListeners listeners) {
        ChatHistory chatHistory = request.chatHistory();
        providerLock(chatHistory).ifPresent(message -> {
            throw new IllegalStateException(message);
        });
        this.chatHistoryService.updateChatHistory(chatHistory);
        ChatToolPreferences preferences = chatHistory.toolPreferences() == null
                ? ChatToolPreferences.defaults() : chatHistory.toolPreferences();
        List<ToolCallback> toolCallbacks = this.toolResolver.resolve(preferences);
        List<Media> media = request.images().stream()
                .map(image -> Media.builder().id(image.hash()).name(image.fileName())
                        .mimeType(MimeType.valueOf(image.mimeType())).data(image.bytes()).build())
                .toList();
        AtomicBoolean liveSaved = new AtomicBoolean();
        Runnable saveOnFirstActivity = () -> {
            if (liveSaved.compareAndSet(false, true)) this.chatHistoryService.updateChatHistory(chatHistory);
        };
        Flux<String> content = this.chatService.stream(chatHistory, request.userPrompt(), preferences.ragSourceId(),
                        listeners.completeChatHistory(), toolCallbacks,
                        processMessage -> {
                            saveOnFirstActivity.run();
                            listeners.mcpToolProcess().accept(processMessage);
                        },
                        listeners.ragProcess(),
                        processMessage -> {
                            saveOnFirstActivity.run();
                            listeners.thinkProcess().accept(processMessage);
                        },
                        listeners.roundUsage(), listeners.beforeHistoryCommit(), listeners.humanQuestionHandler(),
                        listeners.fileUploadHandler(), listeners.imageReferenceHandler(),
                        preferences.reasoningEffort(), media)
                .doOnNext(chunk -> saveOnFirstActivity.run());
        return new ChatTurn(content, toolCallbacks.size());
    }
}
