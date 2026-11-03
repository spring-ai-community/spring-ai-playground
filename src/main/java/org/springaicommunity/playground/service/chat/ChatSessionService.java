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

import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.Preset;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatSessionService {

    public record NewChatRequest(String systemPrompt, ChatOptions chatOptions, ChatExtraOptions extraOptions,
            ChatToolPreferences toolPreferences) {

        public NewChatRequest withToolPreferences(ChatToolPreferences preferences) {
            return new NewChatRequest(this.systemPrompt, this.chatOptions, this.extraOptions, preferences);
        }
    }

    public enum PresetToolIssue { NOT_A_DEFAULT_TOOL, NOT_EXPOSED, NEEDS_SETUP, NOT_ENABLED }

    public record PresetToolMatch(List<ToolSpec> matched, Map<String, PresetToolIssue> unmatched) {}

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ChatSystemPromptPresetService presetService;
    private final ToolSpecService toolSpecService;
    private final ToolSpecPersistenceService toolSpecPersistenceService;
    private final OllamaModelDownloadService modelDownloadService;
    private final SpringAiPlaygroundOptions options;

    public ChatSessionService(ChatService chatService, ChatHistoryService chatHistoryService,
            ChatSystemPromptPresetService presetService, ToolSpecService toolSpecService,
            ToolSpecPersistenceService toolSpecPersistenceService, OllamaModelDownloadService modelDownloadService,
            SpringAiPlaygroundOptions options) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.presetService = presetService;
        this.toolSpecService = toolSpecService;
        this.toolSpecPersistenceService = toolSpecPersistenceService;
        this.modelDownloadService = modelDownloadService;
        this.options = options;
    }

    public Preset defaultPreset() {
        String id = this.options.chat().defaultPreset();
        if (id == null || id.isBlank()) return null;
        return this.presetService.presets().stream().filter(preset -> id.equals(preset.id())).findFirst()
                .orElse(null);
    }

    public ChatHistory newDefaultChat() {
        Preset preset = defaultPreset();
        if (preset == null) {
            return this.chatHistoryService.createChatHistory(this.chatService.getSystemPrompt(),
                    this.chatService.getDefaultOptions(), ChatExtraOptions.defaults());
        }
        String prompt = preset.prompt() == null || preset.prompt().isBlank()
                ? this.chatService.getSystemPrompt() : preset.prompt();
        ChatToolPreferences preferences = preset.dynamicTools()
                ? ChatToolPreferences.defaults().withDynamicTools(true) : ChatToolPreferences.defaults();
        return this.chatHistoryService.createChatHistory(prompt, this.chatService.getDefaultOptions(),
                ChatExtraOptions.defaults(), preferences);
    }

    public boolean isModelDownloaded(String model) {
        return this.modelDownloadService.isDownloaded(model);
    }

    public ChatHistory startChat(NewChatRequest request) {
        String model = request.chatOptions() == null ? null : request.chatOptions().getModel();
        if (!isModelDownloaded(model)) {
            throw new IllegalStateException("Model '" + model + "' is not downloaded yet");
        }
        return this.chatHistoryService.createChatHistory(request.systemPrompt(), request.chatOptions(),
                request.extraOptions(), request.toolPreferences());
    }

    public ChatHistory startChatWithPresetTools(NewChatRequest request, List<ToolSpec> presetTools) {
        this.toolSpecPersistenceService.exposeBuiltinToolsAsPreference(
                presetTools.stream().map(ToolSpec::name).toList());
        return startChat(request.withToolPreferences(presetPreferences(presetTools)));
    }

    public static ChatToolPreferences presetPreferences(List<ToolSpec> presetTools) {
        Set<String> toolIds = presetTools.stream().map(ToolSpec::toolId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ChatToolPreferences(true, toolIds, null, Map.of(), ReasoningEffort.DEFAULT, false);
    }

    public PresetToolMatch resolvePresetTools(List<String> presetToolNames) {
        Map<String, ToolSpec> exposableBuiltinsByName = this.toolSpecService.exposableBuiltins().stream()
                .collect(Collectors.toMap(ToolSpec::name, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new));
        List<ToolSpec> matched = new ArrayList<>();
        Map<String, PresetToolIssue> unmatched = new LinkedHashMap<>();
        for (String name : presetToolNames) {
            ToolSpec spec = exposableBuiltinsByName.get(name);
            if (spec != null) matched.add(spec);
            else unmatched.put(name, issueOf(name));
        }
        return new PresetToolMatch(matched, unmatched);
    }

    private PresetToolIssue issueOf(String toolName) {
        return switch (this.toolSpecService.readiness(toolName)) {
            case READY -> PresetToolIssue.NOT_A_DEFAULT_TOOL;
            case NOT_EXPOSED -> PresetToolIssue.NOT_EXPOSED;
            case NEEDS_SETUP -> PresetToolIssue.NEEDS_SETUP;
            case NOT_ENABLED -> PresetToolIssue.NOT_ENABLED;
        };
    }
}
