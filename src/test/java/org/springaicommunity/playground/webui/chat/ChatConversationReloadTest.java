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
package org.springaicommunity.playground.webui.chat;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@SpringBootTest
class ChatConversationReloadTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void stubModelOptions() {
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadingConversationRendersPersistedMessagesAndHistorySwitchRerenders() {
        long now = System.currentTimeMillis();
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory("reload-conv-a", "Reload A", now, now,
                "sys", (DefaultChatOptions) ChatOptions.builder().build(),
                () -> List.of(new UserMessage("alpha question"), new AssistantMessage("alpha answer"))));
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory("reload-conv-b", "Reload B", now + 1,
                now + 1, "sys", (DefaultChatOptions) ChatOptions.builder().build(),
                () -> List.of(new UserMessage("beta question"), new AssistantMessage("beta answer"))));

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", "reload-conv-a"));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();
        assertThat(markdownContents(view)).anyMatch(text -> text.contains("alpha answer"));

        ListBox<ChatHistory> historyList = $(ListBox.class, view)
                .withCondition(box -> ((ListBox<Object>) box).getListDataView().getItems()
                        .anyMatch(ChatHistory.class::isInstance))
                .first();
        ChatHistory target = historyList.getListDataView().getItems()
                .filter(history -> "reload-conv-b".equals(history.conversationId()))
                .findFirst().orElseThrow();
        historyList.setValue(target);
        roundTrip();
        assertThat(markdownContents(view)).anyMatch(text -> text.contains("beta answer"));
    }

    private List<String> markdownContents(ChatView view) {
        return $(Markdown.class, view).all().stream()
                .map(Markdown::getContent)
                .filter(Objects::nonNull)
                .toList();
    }

}
