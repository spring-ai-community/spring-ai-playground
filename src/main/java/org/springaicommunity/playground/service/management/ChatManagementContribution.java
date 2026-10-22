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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springaicommunity.playground.service.chat.HeadlessChatService;
import org.springaicommunity.playground.service.chat.HeadlessChatService.ConversationSummary;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatRequest;
import org.springaicommunity.playground.service.chat.HeadlessChatService.HeadlessChatResult;
import org.springaicommunity.playground.service.chat.HeadlessChatService.Stage;
import org.springaicommunity.playground.service.chat.HeadlessChatService.ToolCallSummary;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class ChatManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "chat";

    private static final int CONVERSATION_LIMIT_DEFAULT = 20;
    private static final int CONVERSATION_LIMIT_MAX = 100;
    private static final int PROMPT_PREVIEW_LIMIT = 80;

    private final HeadlessChatService headlessChatService;

    public ChatManagementContribution(HeadlessChatService headlessChatService) {
        this.headlessChatService = headlessChatService;
    }

    public record AskInput(
            @JsonPropertyDescription("What to ask the app's Agentic Chat.") String prompt,
            @Nullable @JsonPropertyDescription("Continue this conversation (id from saip_chat_list_conversations). Left out, a new conversation starts.") String conversationId,
            @Nullable @JsonPropertyDescription("Chat model for a new conversation; must be downloaded. Left out, the app's default.") String model,
            @Nullable @JsonPropertyDescription("Names of published or exposed tools the turn may call (saip_tools_list). Left out, a new conversation gets no tools and an existing one keeps its own.") List<String> tools,
            @Nullable @JsonPropertyDescription("RAG source for the turn: a pipeline id or doc:<documentId>.") String ragSourceId,
            @Nullable @JsonPropertyDescription("Seconds to wait for the answer, default 300, at most 1800.") Integer timeoutSeconds) {
    }

    public record AskData(String conversationId, boolean newConversation, String answer, List<ToolCallSummary> toolCalls,
                          String model, Long promptTokens, Long completionTokens, long durationMs) {
    }

    public record ListInput(
            @Nullable @JsonPropertyDescription("Maximum conversations to return, newest first, default 20, at most 100.") Integer limit) {
    }

    public record ConversationsData(List<ConversationSummary> conversations, boolean truncated, int total) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(ask(), listConversations());
    }

    private ManagementTool ask() {
        return ManagementTool.named("saip_chat_ask", CATEGORY, AskInput.class).data(AskData.class)
                .description("Run one turn of this app's Agentic Chat and return the answer: the app's chat model, "
                        + "optionally its tools and a RAG source, recorded in the chat history like a turn typed "
                        + "in the app. A tool that needs human approval asks the person at this client; if the "
                        + "client cannot ask, the tool is declined. The management tools themselves are never "
                        + "available to the turn.")
                .openWorld()
                .write(input -> "run a chat turn" + (input.conversationId() == null ? " in a new conversation"
                        : " in conversation " + input.conversationId())
                        + (input.tools() == null || input.tools().isEmpty() ? " without tools"
                                : " with tools " + input.tools())
                        + ": " + preview(input.prompt()))
                .handle((input, caller) -> {
                    HeadlessChatRequest request = new HeadlessChatRequest(input.prompt(), input.conversationId(),
                            input.model(), input.tools(), input.ragSourceId(),
                            input.timeoutSeconds() == null ? null : Duration.ofSeconds(Math.max(1,
                                    input.timeoutSeconds())));
                    HeadlessChatResult result = this.headlessChatService.ask(request,
                            new ElicitationQuestionHandler(caller),
                            stage -> caller.progress().report(0, null, describe(stage)));
                    AskData data = new AskData(result.conversationId(), result.newConversation(), result.answer(),
                            result.toolCalls(), result.model(), result.promptTokens(), result.completionTokens(),
                            result.durationMs());
                    return ManagementResult.persisted("Answered in " + result.durationMs() + " ms with "
                            + result.toolCalls().size() + " tool call(s); conversation " + result.conversationId()
                            + ".", data);
                });
    }

    private ManagementTool listConversations() {
        return ManagementTool.named("saip_chat_list_conversations", CATEGORY, ListInput.class)
                .data(ConversationsData.class)
                .description("List the chat conversations of this app, newest first, with model and message count, "
                        + "so a turn can continue one of them with saip_chat_ask.")
                .idempotent()
                .handle((input, caller) -> {
                    List<ConversationSummary> all = this.headlessChatService.conversations().stream()
                            .sorted((first, second) -> Long.compare(second.updatedAt(), first.updatedAt()))
                            .toList();
                    int limit = input.limit() == null ? CONVERSATION_LIMIT_DEFAULT
                            : Math.max(1, Math.min(input.limit(), CONVERSATION_LIMIT_MAX));
                    List<ConversationSummary> page = all.stream().limit(limit).toList();
                    return ManagementResult.ok(all.size() + " conversation(s).",
                            new ConversationsData(page, all.size() > page.size(), all.size()));
                });
    }

    static String describe(Stage stage) {
        return switch (stage.kind()) {
            case PROMPT_SENT -> "Sending the prompt to " + stage.detail();
            case DOCUMENTS_RETRIEVED -> "Retrieved " + stage.detail() + " document(s)";
            case TOOL_CALL -> "Calling tool " + stage.detail();
            case ANSWERING -> "Answering";
        };
    }

    private static String preview(String prompt) {
        String oneLine = prompt == null ? "" : prompt.replace('\n', ' ').strip();
        return oneLine.length() <= PROMPT_PREVIEW_LIMIT ? oneLine : oneLine.substring(0, PROMPT_PREVIEW_LIMIT) + "...";
    }

}
