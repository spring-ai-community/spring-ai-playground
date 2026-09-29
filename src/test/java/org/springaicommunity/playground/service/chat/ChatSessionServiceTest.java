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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.chat.ChatSessionService.NewChatRequest;
import org.springaicommunity.playground.service.chat.ChatSessionService.PresetToolIssue;
import org.springaicommunity.playground.service.chat.ChatSessionService.PresetToolMatch;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.Preset;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.PresetKind;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolReadiness;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ChatSystemPromptPresetService presetService = mock(ChatSystemPromptPresetService.class);
    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final ToolSpecPersistenceService persistenceService = mock(ToolSpecPersistenceService.class);
    private final OllamaModelDownloadService downloadService = mock(OllamaModelDownloadService.class);
    private final ChatOptions defaultOptions = ChatOptions.builder().model("qwen3").build();
    private final ChatHistory created = mock(ChatHistory.class);

    @BeforeEach
    void stubDefaults() {
        when(chatService.getSystemPrompt()).thenReturn("system default");
        when(chatService.getDefaultOptions()).thenReturn(defaultOptions);
        when(downloadService.isDownloaded(anyString())).thenReturn(true);
        when(chatHistoryService.createChatHistory(anyString(), any(), any())).thenReturn(created);
        when(chatHistoryService.createChatHistory(anyString(), any(), any(), any())).thenReturn(created);
    }

    private ChatSessionService service(String defaultPresetId) {
        SpringAiPlaygroundOptions options = new SpringAiPlaygroundOptions(null, false, null,
                new SpringAiPlaygroundOptions.Chat(null, null, null, null, null, null, null, null, defaultPresetId,
                        null), null, null);
        return new ChatSessionService(chatService, chatHistoryService, presetService, toolSpecService,
                persistenceService, downloadService, options);
    }

    private static ToolSpec spec(String name) {
        return new ToolSpec("id-" + name, name, "", List.of(), List.of(), "return 1;", ToolSpec.CodeType.Javascript,
                null);
    }

    @Test
    void newDefaultChatUsesTheServiceDefaultsWithoutAPreset() {
        assertThat(service(null).newDefaultChat()).isSameAs(created);

        verify(chatHistoryService).createChatHistory("system default", defaultOptions, ChatExtraOptions.defaults());
    }

    @Test
    void newDefaultChatTakesPromptAndDynamicFlagFromTheConfiguredPreset() {
        Preset preset = new Preset("agent", "Agent", "", "You are an agent.", PresetKind.EXAMPLE, List.of(), true);
        when(presetService.presets()).thenReturn(List.of(preset));

        service("agent").newDefaultChat();

        ArgumentCaptor<ChatToolPreferences> preferences = ArgumentCaptor.forClass(ChatToolPreferences.class);
        verify(chatHistoryService).createChatHistory(eq("You are an agent."), eq(defaultOptions),
                eq(ChatExtraOptions.defaults()), preferences.capture());
        assertThat(preferences.getValue().dynamicTools()).isTrue();
        assertThat(service("missing").defaultPreset()).isNull();
    }

    @Test
    void startChatRefusesAModelThatIsNotDownloaded() {
        when(downloadService.isDownloaded("gone")).thenReturn(false);
        NewChatRequest request = new NewChatRequest("prompt", ChatOptions.builder().model("gone").build(),
                ChatExtraOptions.defaults(), ChatToolPreferences.defaults());

        assertThatThrownBy(() -> service(null).startChat(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gone");
        verify(chatHistoryService, never()).createChatHistory(anyString(), any(), any(), any());
    }

    @Test
    void resolvePresetToolsSplitsMatchesFromReasonedUnmatched() {
        when(toolSpecService.exposableBuiltins()).thenReturn(List.of(spec("getWeather")));
        when(toolSpecService.readiness("sendMail")).thenReturn(ToolReadiness.NEEDS_SETUP);
        when(toolSpecService.readiness("ghost")).thenReturn(ToolReadiness.NOT_ENABLED);

        PresetToolMatch match = service(null).resolvePresetTools(List.of("getWeather", "sendMail", "ghost"));

        assertThat(match.matched()).extracting(ToolSpec::name).containsExactly("getWeather");
        assertThat(match.unmatched()).containsExactly(Map.entry("sendMail", PresetToolIssue.NEEDS_SETUP),
                Map.entry("ghost", PresetToolIssue.NOT_ENABLED));
    }

    @Test
    void startChatWithPresetToolsExposesThemAndPinsTheSelection() {
        NewChatRequest request = new NewChatRequest("prompt", defaultOptions, ChatExtraOptions.defaults(),
                ChatToolPreferences.defaults().withDynamicTools(true));

        service(null).startChatWithPresetTools(request, List.of(spec("getWeather"), spec("getCurrentTime")));

        verify(persistenceService).exposeBuiltinToolsAsPreference(List.of("getWeather", "getCurrentTime"));
        ArgumentCaptor<ChatToolPreferences> preferences = ArgumentCaptor.forClass(ChatToolPreferences.class);
        verify(chatHistoryService).createChatHistory(eq("prompt"), eq(defaultOptions),
                eq(ChatExtraOptions.defaults()), preferences.capture());
        assertThat(preferences.getValue().useBuiltinMcp()).isTrue();
        assertThat(preferences.getValue().dynamicTools()).isFalse();
        assertThat(preferences.getValue().exposedToolIds())
                .containsExactlyInAnyOrder("id-getWeather", "id-getCurrentTime");
    }
}
