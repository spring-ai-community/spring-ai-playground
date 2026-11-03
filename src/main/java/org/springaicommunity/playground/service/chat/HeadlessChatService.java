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

import org.springaicommunity.playground.service.SpringAiPlaygroundRagAdvisor;
import org.springaicommunity.playground.service.agent.AgentLoopHarness;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurn;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnListeners;
import org.springaicommunity.playground.service.chat.ChatTurnService.ChatTurnRequest;
import org.springaicommunity.playground.service.tool.FileUploadHandler;
import org.springaicommunity.playground.service.tool.HumanQuestionHandler;
import org.springaicommunity.playground.service.tool.ImageReferenceHandler;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Service
public class HeadlessChatService {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(1800);
    private static final int OUTCOME_LIMIT = 400;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record HeadlessChatRequest(String prompt, String conversationId, String model, List<String> tools,
                                      String ragSourceId, Duration timeout) {

        public HeadlessChatRequest {
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
            timeout = timeout == null ? DEFAULT_TIMEOUT
                    : timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        }
    }

    public record ToolCallSummary(String name, String id, Object arguments, String outcome) {

        ToolCallSummary withOutcome(String value) {
            return new ToolCallSummary(this.name, this.id, this.arguments, value);
        }
    }

    public record HeadlessChatResult(String conversationId, boolean newConversation, String answer,
                                     List<ToolCallSummary> toolCalls, String model, Long promptTokens,
                                     Long completionTokens, long durationMs) {
    }

    public record ConversationSummary(String conversationId, String title, long updatedAt, String model,
                                      int messages, boolean readOnly) {
    }

    public enum StageKind { PROMPT_SENT, DOCUMENTS_RETRIEVED, TOOL_CALL, ANSWERING }

    public record Stage(StageKind kind, String detail) {
    }

    private final ChatTurnService chatTurnService;
    private final ChatHistoryService chatHistoryService;
    private final ChatSessionService chatSessionService;
    private final ChatService chatService;
    private final ToolSpecService toolSpecService;

    public HeadlessChatService(ChatTurnService chatTurnService, ChatHistoryService chatHistoryService,
            ChatSessionService chatSessionService, ChatService chatService, ToolSpecService toolSpecService) {
        this.chatTurnService = chatTurnService;
        this.chatHistoryService = chatHistoryService;
        this.chatSessionService = chatSessionService;
        this.chatService = chatService;
        this.toolSpecService = toolSpecService;
    }

    public List<ConversationSummary> conversations() {
        return this.chatHistoryService.getChatHistoryList().stream()
                .map(history -> new ConversationSummary(history.conversationId(), history.title(),
                        history.updateTimestamp(),
                        history.chatOptions() == null ? null : history.chatOptions().getModel(),
                        history.messagesSupplier().get().size(),
                        this.chatTurnService.providerLock(history).isPresent()))
                .toList();
    }

    public HeadlessChatResult ask(HeadlessChatRequest request, HumanQuestionHandler approvals,
            Consumer<Stage> stage) {
        Objects.requireNonNull(approvals, "approvals");
        long startedAt = System.currentTimeMillis();
        boolean newConversation = request.conversationId() == null || request.conversationId().isBlank();
        ChatHistory history = newConversation ? newConversation(request) : existingConversation(request);
        ChatTurnRecorder recorder = new ChatTurnRecorder(history, startedAt);
        Map<String, ToolCallSummary> calls = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicBoolean answering = new AtomicBoolean();
        ChatTurnListeners listeners = new ChatTurnListeners(
                content -> onToolEvent(content, recorder, calls, stage),
                content -> onRagEvent(content, recorder, stage),
                content -> recorder.thinkProcess(System.currentTimeMillis(), String.valueOf(content)),
                recorder::applyRoundUsage,
                signal -> commit(signal, recorder, history),
                approvals,
                upload -> FileUploadHandler.Result.none("File upload is not available in a turn run over MCP."),
                (reference, gate) -> ImageReferenceHandler.Resolved.none(
                        "Image references are not available in a turn run over MCP."),
                this.chatHistoryService::updateChatHistory);
        stage.accept(new Stage(StageKind.PROMPT_SENT, modelOf(history)));
        ChatTurn turn = this.chatTurnService.send(new ChatTurnRequest(history, request.prompt(), List.of()),
                listeners);
        List<String> chunks = turn.content()
                .doOnNext(chunk -> {
                    if (answering.compareAndSet(false, true)) {
                        recorder.assistantStarted(System.currentTimeMillis());
                        stage.accept(new Stage(StageKind.ANSWERING, null));
                    }
                    recorder.assistantStreaming();
                })
                .collectList()
                .block(request.timeout());
        String answer = chunks == null ? "" : String.join("", chunks);
        Optional<ChatService.ChatMeta> meta = lastUserMessage(history).map(Message::getMetadata)
                .map(metadata -> metadata.get(ChatService.CHAT_META))
                .filter(ChatService.ChatMeta.class::isInstance)
                .map(ChatService.ChatMeta.class::cast);
        return new HeadlessChatResult(history.conversationId(), newConversation, answer,
                List.copyOf(calls.values()),
                meta.map(ChatService.ChatMeta::model).orElse(modelOf(history)),
                meta.map(ChatService.ChatMeta::usage).map(usage -> usage.getPromptTokens()).map(Integer::longValue)
                        .orElse(null),
                meta.map(ChatService.ChatMeta::usage).map(usage -> usage.getCompletionTokens())
                        .map(Integer::longValue).orElse(null),
                System.currentTimeMillis() - startedAt);
    }

    private ChatHistory newConversation(HeadlessChatRequest request) {
        ChatHistory base = this.chatSessionService.newDefaultChat();
        ChatOptions options = request.model() == null || request.model().isBlank()
                ? base.chatOptions() : withModel(base.chatOptions(), request.model());
        ChatToolPreferences preferences = preferencesOf(request, base.toolPreferences());
        return this.chatSessionService.startChat(new ChatSessionService.NewChatRequest(base.systemPrompt(),
                options, base.extraOptions(), preferences));
    }

    private ChatHistory existingConversation(HeadlessChatRequest request) {
        ChatHistory history = this.chatHistoryService.getChatHistory(request.conversationId());
        if (history == null) {
            throw new IllegalArgumentException("Conversation '" + request.conversationId()
                    + "' does not exist. Leave conversationId out to start a new one.");
        }
        this.chatTurnService.providerLock(history).ifPresent(lock -> {
            throw new IllegalStateException(lock);
        });
        if (request.model() != null && !request.model().isBlank()
                && !request.model().equals(modelOf(history))) {
            throw new IllegalArgumentException("Conversation '" + request.conversationId() + "' uses model '"
                    + modelOf(history) + "', not '" + request.model() + "'. Leave model out, or start a new "
                    + "conversation with it.");
        }
        if (request.tools() == null && (request.ragSourceId() == null || request.ragSourceId().isBlank())) {
            return history;
        }
        return history.withToolPreferences(preferencesOf(request, history.toolPreferences()));
    }

    private ChatToolPreferences preferencesOf(HeadlessChatRequest request, ChatToolPreferences current) {
        ChatToolPreferences base = current == null ? ChatToolPreferences.defaults() : current;
        Set<String> toolIds = request.tools() == null ? base.exposedToolIds() : toolIdsOf(request.tools());
        boolean useBuiltin = request.tools() == null ? base.useBuiltinMcp() : !toolIds.isEmpty();
        String ragSourceId = request.ragSourceId() == null || request.ragSourceId().isBlank()
                ? base.ragSourceId() : ragSourceOf(request.ragSourceId());
        return new ChatToolPreferences(useBuiltin, toolIds, ragSourceId, base.mcpServerNames(),
                base.reasoningEffort(), base.dynamicTools());
    }

    private Set<String> toolIdsOf(List<String> names) {
        Map<String, String> idsByName = new LinkedHashMap<>();
        Stream.concat(this.toolSpecService.getToolSpecList().stream(),
                        this.toolSpecService.getExternalToolSpecs().stream())
                .filter(spec -> !spec.draft())
                .forEach(spec -> idsByName.putIfAbsent(spec.name(), spec.toolId()));
        Set<String> ids = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            String id = idsByName.get(name);
            if (id == null) unknown.add(name);
            else ids.add(id);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown tool(s) " + unknown + ". Use the names of published or "
                    + "exposed tools; saip_tools_list shows them.");
        }
        return ids;
    }

    private String ragSourceOf(String sourceId) {
        boolean known = this.chatService.getRagSources().stream()
                .anyMatch(source -> source.sourceId().equals(sourceId));
        if (!known) {
            throw new IllegalArgumentException("Unknown RAG source '" + sourceId + "'. Use a pipeline id from "
                    + "saip_knowledge_list_pipelines or 'doc:<documentId>' from saip_knowledge_list_documents.");
        }
        return sourceId;
    }

    private ChatOptions withModel(ChatOptions base, String model) {
        List<String> models = this.chatService.getModels();
        if (!models.isEmpty() && !models.contains(model)) {
            throw new IllegalArgumentException("Unknown model '" + model + "'. Available: "
                    + String.join(", ", models));
        }
        if (!this.chatSessionService.isModelDownloaded(model)) {
            throw new IllegalArgumentException("Model '" + model + "' is not downloaded yet.");
        }
        return ChatOptions.builder().model(model).temperature(base.getTemperature()).maxTokens(base.getMaxTokens())
                .topP(base.getTopP()).topK(base.getTopK()).frequencyPenalty(base.getFrequencyPenalty())
                .presencePenalty(base.getPresencePenalty()).stopSequences(base.getStopSequences()).build();
    }

    private static String modelOf(ChatHistory history) {
        return history.chatOptions() == null ? null : history.chatOptions().getModel();
    }

    private static void onToolEvent(Object content, ChatTurnRecorder recorder, Map<String, ToolCallSummary> calls,
            Consumer<Stage> stage) {
        long now = System.currentTimeMillis();
        String text = String.valueOf(content);
        boolean completed = AgentLoopHarness.MCP_TOOL_EXECUTION_COMPLETED_MESSAGE.equals(text);
        recorder.mcpProcess(now, snippet(now, text), content, completed);
        if (content instanceof AgentLoopHarness.McpAssistantToolCall assistant) {
            for (AgentLoopHarness.McpToolCall call : assistant.toolCalls()) {
                calls.put(call.id(), new ToolCallSummary(call.name(), call.id(), call.arguments(), null));
                stage.accept(new Stage(StageKind.TOOL_CALL, call.name()));
            }
        } else if (content instanceof AgentLoopHarness.McpToolResult result) {
            ToolCallSummary summary = calls.get(result.id());
            String outcome = abbreviate(String.valueOf(result.responseData()));
            calls.put(result.id(), summary == null
                    ? new ToolCallSummary(result.name(), result.id(), null, outcome) : summary.withOutcome(outcome));
        }
    }

    private static void onRagEvent(Object content, ChatTurnRecorder recorder, Consumer<Stage> stage) {
        if (content instanceof SpringAiPlaygroundRagAdvisor.RagRetrievedDocumentsInfo info) {
            recorder.ragDocumentsRetrieved(info.count(), info.titles());
            stage.accept(new Stage(StageKind.DOCUMENTS_RETRIEVED, Integer.toString(info.count())));
            return;
        }
        long now = System.currentTimeMillis();
        String text = String.valueOf(content);
        recorder.ragProcess(now, snippet(now, text),
                SpringAiPlaygroundRagAdvisor.RAG_SEARCH_COMPLETED_MESSAGE.equals(text));
    }

    private static void commit(SignalType signal, ChatTurnRecorder recorder, ChatHistory history) {
        if (SignalType.CANCEL.equals(signal)) recorder.markStopped();
        List<Message> messages = history.messagesSupplier().get();
        if (messages.isEmpty()) return;
        lastUserMessage(history).map(Message::getMetadata).ifPresent(recorder::stampUserMessage);
        Map<String, Object> metadata = messages.getLast().getMetadata();
        if (!recorder.responded() && !recorder.hasProcessActivity()) {
            recorder.writeStreamStatus(metadata);
            return;
        }
        ChatService.ChatMeta chatMeta = lastUserMessage(history).map(Message::getMetadata)
                .map(map -> map.get(ChatService.CHAT_META))
                .filter(ChatService.ChatMeta.class::isInstance)
                .map(ChatService.ChatMeta.class::cast).orElse(null);
        recorder.finish(metadata, List.of(), chatMeta, System.currentTimeMillis());
    }

    private static Optional<Message> lastUserMessage(ChatHistory history) {
        return history.messagesSupplier().get().reversed().stream()
                .filter(message -> MessageType.USER.equals(message.getMessageType()))
                .findFirst();
    }

    private static String snippet(long timestamp, String text) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())) + " : " + text
                + "\n\n";
    }

    private static String abbreviate(String text) {
        return text.length() <= OUTCOME_LIMIT ? text : text.substring(0, OUTCOME_LIMIT) + "...";
    }

}
