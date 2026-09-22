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
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatToolPreferences;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@SpringBootTest(properties = "spring.ai.playground.chat.tool-search.min-tools=100000")
class ChatDynamicToolGateUiTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void stubModel() {
        lenient().when(this.chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("mock reply"))))));
    }

    @Test
    void dynamicToolsIsForcedOffWhenTheSearchablePoolIsBelowMinTools() {
        long now = System.currentTimeMillis();
        String conversationId = "rt07-conv";
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory(conversationId, "Rt07", now, now, "sys",
                (DefaultChatOptions) ChatOptions.builder().build(), List::of)
                .withToolPreferences(ChatToolPreferences.defaults().withDynamicTools(true)));

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", conversationId));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        Checkbox dynamic = $(Checkbox.class, view)
                .withCondition(box -> "Dynamic tool discovery".equals(box.getLabel())).single();
        assertThat(dynamic.isEnabled()).as("RT-07 the gate disables the control").isFalse();
        assertThat(dynamic.getValue()).as("RT-07 a persisted true value is cleared rather than silently honoured")
                .isFalse();
    }

}
