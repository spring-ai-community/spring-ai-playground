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

import org.springaicommunity.playground.service.agent.AgentLoopHarness;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ChatTurnRecorder {

    public record TokenUsage(long promptTokens, long completionTokens) {}

    public static final String RAG_PROCESS_TIMESTAMP = "ragProcessTimestamp";
    public static final String RAG_PROCESS_END_TIMESTAMP = "ragProcessEndTimestamp";
    public static final String RAG_PROCESS_MESSAGES = "ragProcessMessages";
    public static final String RAG_PROCESS_DOC_COUNT = "ragProcessDocCount";
    public static final String RAG_PROCESS_DOC_TITLES = "ragProcessDocTitles";
    public static final String THINK_PROCESS_TIMESTAMP = "thinkProcessTimestamp";
    public static final String THINK_PROCESS_END_TIMESTAMP = "thinkProcessEndTimestamp";
    public static final String THINK_PROCESS_MESSAGES = "thinkProcessMessages";
    public static final String THINK_PROCESS_PROMPT_TOKENS = "thinkProcessPromptTokens";
    public static final String THINK_PROCESS_COMPLETION_TOKENS = "thinkProcessCompletionTokens";
    public static final String MCP_TOOL_PROCESS_TIMESTAMP = "mcpToolProcessTimestamp";
    public static final String MCP_TOOL_PROCESS_END_TIMESTAMP = "mcpToolProcessEndTimestamp";
    public static final String MCP_TOOL_PROCESS_MESSAGES = "mcpToolProcessMessages";
    public static final String MCP_TOOL_PROCESS_CALL_COUNT = "mcpToolProcessCallCount";
    public static final String MCP_TOOL_PROCESS_TOOL_NAMES = "mcpToolProcessToolNames";
    public static final String MCP_TOOL_PROCESS_PROMPT_TOKENS = "mcpToolProcessPromptTokens";
    public static final String MCP_TOOL_PROCESS_COMPLETION_TOKENS = "mcpToolProcessCompletionTokens";
    public static final String ACTION_BLOCKS = "actionBlocks";
    public static final String STREAM_STATUS = "streamStatus";
    public static final String STREAM_STATUS_STAGE = "streamStatusStage";
    public static final String STREAM_STATUS_MESSAGE = "streamStatusMessage";
    public static final String RESPONSE_DURATION_MS = "responseDurationMs";
    public static final String RESPONSE_PROMPT_TOKENS = "responsePromptTokens";
    public static final String RESPONSE_COMPLETION_TOKENS = "responseCompletionTokens";
    public static final String RESPONSE_TOTAL_TOKENS = "responseTotalTokens";
    public static final String RESPONSE_MODEL = "responseModel";
    public static final String STAGE_STARTING = "STARTING";
    public static final String STAGE_RAG = "RAG DOCUMENTS";
    public static final String STAGE_THINK = "THINK";
    public static final String STAGE_MCP = "MCP TOOLS";
    public static final String STAGE_ASSISTANT = "ASSISTANT";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_ERROR = "ERROR";

    private final String conversationId;
    private final long startTimestamp;
    private final List<String> pendingActionBlocks = new ArrayList<>();
    private final LinkedHashSet<String> ragDocTitles = new LinkedHashSet<>();
    private final LinkedHashSet<String> mcpToolNames = new LinkedHashSet<>();
    private long ragStart;
    private long ragEnd;
    private int ragDocCount;
    private StringBuilder ragMessages;
    private long thinkStart;
    private long thinkEnd;
    private StringBuilder thinkMessages;
    private long mcpStart;
    private long mcpEnd;
    private int mcpCallCount;
    private StringBuilder mcpMessages;
    private long turnPromptTokens;
    private long turnCompletionTokens;
    private long turnTotalTokens;
    private boolean turnUsageSeen;
    private long thinkPromptTokens;
    private long thinkCompletionTokens;
    private boolean thinkUsageSeen;
    private long mcpPromptTokens;
    private long mcpCompletionTokens;
    private boolean mcpUsageSeen;
    private long responseTimestamp;
    private String stage = STAGE_STARTING;
    private String status;
    private String statusMessage;

    public ChatTurnRecorder(ChatHistory chatHistory, long startTimestamp) {
        this.conversationId = chatHistory.conversationId();
        this.startTimestamp = startTimestamp;
        chatHistory.updateLastMessageTimestamp(startTimestamp);
    }

    public long startTimestamp() {
        return this.startTimestamp;
    }

    public String stage() {
        return this.stage;
    }

    public void ragDocumentsRetrieved(int count, Collection<String> titles) {
        this.stage = STAGE_RAG;
        this.ragDocCount = count;
        if (titles != null) this.ragDocTitles.addAll(titles);
    }

    public void ragProcess(long timestamp, String markdownSnippet, boolean completed) {
        this.stage = STAGE_RAG;
        if (this.ragStart == 0) this.ragStart = timestamp;
        if (this.ragMessages == null) this.ragMessages = new StringBuilder();
        this.ragMessages.append(markdownSnippet);
        if (completed) this.ragEnd = timestamp;
    }

    public boolean hasRagProcess() {
        return this.ragMessages != null;
    }

    public long ragStart() {
        return this.ragStart;
    }

    public long ragEnd() {
        return this.ragEnd;
    }

    public int ragDocCount() {
        return this.ragDocCount;
    }

    public Set<String> ragDocTitles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.ragDocTitles));
    }

    public void thinkProcess(long timestamp, String markdownSnippet) {
        this.stage = STAGE_THINK;
        if (this.thinkStart == 0) this.thinkStart = timestamp;
        if (this.thinkMessages == null) this.thinkMessages = new StringBuilder();
        this.thinkMessages.append(markdownSnippet);
    }

    public void endThink(long timestamp) {
        if (this.thinkEnd == 0) this.thinkEnd = timestamp;
    }

    public boolean hasThinkProcess() {
        return this.thinkMessages != null;
    }

    public long thinkStart() {
        return this.thinkStart;
    }

    public long thinkEnd() {
        return this.thinkEnd;
    }

    public void mcpProcess(long timestamp, String markdownSnippet, Object content, boolean completed) {
        this.stage = STAGE_MCP;
        if (this.mcpStart == 0) this.mcpStart = timestamp;
        if (this.mcpMessages == null) this.mcpMessages = new StringBuilder();
        this.mcpMessages.append(markdownSnippet);
        if (content instanceof AgentLoopHarness.McpAssistantToolCall toolCall) {
            toolCall.toolCalls().forEach(call -> {
                this.mcpCallCount++;
                this.mcpToolNames.add(call.name());
            });
        }
        if (content instanceof AgentLoopHarness.McpToolResult toolResult) {
            this.pendingActionBlocks.addAll(ChatActionBlocks.extract(toolResult.responseData()));
        }
        if (completed) this.mcpEnd = timestamp;
    }

    public boolean hasMcpProcess() {
        return this.mcpMessages != null;
    }

    public long mcpStart() {
        return this.mcpStart;
    }

    public long mcpEnd() {
        return this.mcpEnd;
    }

    public int mcpCallCount() {
        return this.mcpCallCount;
    }

    public Set<String> mcpToolNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.mcpToolNames));
    }

    public void applyRoundUsage(ChatService.RoundUsage round) {
        this.turnPromptTokens += round.promptTokens();
        this.turnCompletionTokens += round.completionTokens();
        this.turnTotalTokens += round.totalTokens();
        this.turnUsageSeen = true;
        if (!round.toolCallRound()) return;
        if (round.thinkRound()) {
            this.thinkPromptTokens += round.promptTokens();
            this.thinkCompletionTokens += round.completionTokens();
            this.thinkUsageSeen = true;
        } else {
            this.mcpPromptTokens += round.promptTokens();
            this.mcpCompletionTokens += round.completionTokens();
            this.mcpUsageSeen = true;
        }
    }

    public Optional<TokenUsage> thinkUsage() {
        return this.thinkUsageSeen
                ? Optional.of(new TokenUsage(this.thinkPromptTokens, this.thinkCompletionTokens)) : Optional.empty();
    }

    public Optional<TokenUsage> mcpUsage() {
        return this.mcpUsageSeen
                ? Optional.of(new TokenUsage(this.mcpPromptTokens, this.mcpCompletionTokens)) : Optional.empty();
    }

    public void assistantStreaming() {
        this.stage = STAGE_ASSISTANT;
    }

    public void assistantStarted(long timestamp) {
        if (this.responseTimestamp == 0) this.responseTimestamp = timestamp;
    }

    public boolean responded() {
        return this.responseTimestamp > 0;
    }

    public long responseTimestamp() {
        return this.responseTimestamp;
    }

    public boolean hasPendingActionBlocks() {
        return !this.pendingActionBlocks.isEmpty();
    }

    public List<String> takeActionBlocks(String existingMarkdown) {
        List<String> appended = ChatActionBlocks.missingFrom(existingMarkdown, this.pendingActionBlocks);
        this.pendingActionBlocks.clear();
        return appended;
    }

    public void markStopped() {
        this.status = STATUS_STOPPED;
        this.statusMessage = null;
    }

    public void markError(String message) {
        this.status = STATUS_ERROR;
        this.statusMessage = message;
    }

    public String status() {
        return this.status;
    }

    public String statusMessage() {
        return this.statusMessage;
    }

    public boolean hasProcessActivity() {
        return hasRagProcess() || hasThinkProcess() || hasMcpProcess();
    }

    public void stampUserMessage(Map<String, Object> lastUserMetadata) {
        if (lastUserMetadata != null) stamp(lastUserMetadata, this.startTimestamp);
    }

    public void finish(Map<String, Object> metadata, List<String> appendedBlocks, ChatService.ChatMeta chatMeta,
            long now) {
        if (hasThinkProcess()) endThink(now);
        if (metadata == null) return;
        if (hasRagProcess()) {
            metadata.put(RAG_PROCESS_TIMESTAMP, this.ragStart);
            if (this.ragEnd > 0) metadata.put(RAG_PROCESS_END_TIMESTAMP, this.ragEnd);
            metadata.put(RAG_PROCESS_DOC_COUNT, this.ragDocCount);
            if (!this.ragDocTitles.isEmpty()) metadata.put(RAG_PROCESS_DOC_TITLES, new ArrayList<>(this.ragDocTitles));
            metadata.put(RAG_PROCESS_MESSAGES, this.ragMessages.toString());
        }
        if (hasThinkProcess()) {
            metadata.put(THINK_PROCESS_TIMESTAMP, this.thinkStart);
            metadata.put(THINK_PROCESS_END_TIMESTAMP, this.thinkEnd);
            metadata.put(THINK_PROCESS_MESSAGES, this.thinkMessages.toString());
            if (this.thinkUsageSeen) {
                metadata.put(THINK_PROCESS_PROMPT_TOKENS, this.thinkPromptTokens);
                metadata.put(THINK_PROCESS_COMPLETION_TOKENS, this.thinkCompletionTokens);
            }
        }
        if (hasMcpProcess()) {
            metadata.put(MCP_TOOL_PROCESS_TIMESTAMP, this.mcpStart);
            if (this.mcpEnd > 0) metadata.put(MCP_TOOL_PROCESS_END_TIMESTAMP, this.mcpEnd);
            if (this.mcpCallCount > 0) metadata.put(MCP_TOOL_PROCESS_CALL_COUNT, this.mcpCallCount);
            if (!this.mcpToolNames.isEmpty()) metadata.put(MCP_TOOL_PROCESS_TOOL_NAMES, new ArrayList<>(this.mcpToolNames));
            metadata.put(MCP_TOOL_PROCESS_MESSAGES, this.mcpMessages.toString());
            if (this.mcpUsageSeen) {
                metadata.put(MCP_TOOL_PROCESS_PROMPT_TOKENS, this.mcpPromptTokens);
                metadata.put(MCP_TOOL_PROCESS_COMPLETION_TOKENS, this.mcpCompletionTokens);
            }
        }
        stamp(metadata, this.responseTimestamp > 0 ? this.responseTimestamp : now);
        if (appendedBlocks != null && !appendedBlocks.isEmpty())
            metadata.put(ACTION_BLOCKS, new ArrayList<>(appendedBlocks));
        if (responded()) writeResponseMeta(metadata, Math.max(0, now - this.startTimestamp), chatMeta);
        writeStreamStatus(metadata);
    }

    public void writeStreamStatus(Map<String, Object> metadata) {
        if (this.status == null || metadata == null) return;
        metadata.put(STREAM_STATUS, this.status);
        metadata.put(STREAM_STATUS_STAGE, this.stage);
        if (this.statusMessage != null) metadata.put(STREAM_STATUS_MESSAGE, this.statusMessage);
    }

    private void writeResponseMeta(Map<String, Object> metadata, long durationMs, ChatService.ChatMeta chatMeta) {
        metadata.put(RESPONSE_DURATION_MS, durationMs);
        if (chatMeta != null && chatMeta.model() != null && !chatMeta.model().isBlank())
            metadata.put(RESPONSE_MODEL, chatMeta.model());
        if (this.turnUsageSeen) {
            metadata.put(RESPONSE_PROMPT_TOKENS, this.turnPromptTokens);
            metadata.put(RESPONSE_COMPLETION_TOKENS, this.turnCompletionTokens);
            metadata.put(RESPONSE_TOTAL_TOKENS, this.turnTotalTokens);
            return;
        }
        Usage usage = chatMeta == null ? null : chatMeta.usage();
        if (usage == null) return;
        putIfPresent(metadata, RESPONSE_PROMPT_TOKENS, usage.getPromptTokens());
        putIfPresent(metadata, RESPONSE_COMPLETION_TOKENS, usage.getCompletionTokens());
        putIfPresent(metadata, RESPONSE_TOTAL_TOKENS, usage.getTotalTokens());
    }

    private void stamp(Map<String, Object> metadata, long timestamp) {
        metadata.put(ChatHistoryPersistenceService.CONVERSATION_ID, this.conversationId);
        metadata.put(ChatHistory.TIMESTAMP, timestamp);
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Integer value) {
        if (value != null) metadata.put(key, value);
    }
}
