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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.StringNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

@SpringBootTest(properties = "spring.ai.playground.chat.tool-search.min-tools=0")
class ChatPromptSubmitTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoSpyBean
    private ChatService chatService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void stubModel() {
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    }

    @Test
    void enterSubmittedPromptIsStrippedOfTheTrailingNewline() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        doReturn(Flux.empty()).when(this.chatService).stream(any(), promptCaptor.capture(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        long now = System.currentTimeMillis();
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory("strip-conv", "Strip", now, now, "sys",
                (DefaultChatOptions) ChatOptions.builder().build(), List::of));
        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", "strip-conv"));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        TextArea prompt = promptArea(view);
        test(prompt).setValue("hello there");
        Button submit = $(Button.class, view)
                .withCondition(button -> "Submit".equals(button.getTooltip().getText())).single();
        test(submit).click();
        completePendingPromptValueJs("hello there\n");
        roundTrip();

        assertThat(promptCaptor.getValue()).isEqualTo("hello there");
    }

    private TextArea promptArea(ChatView view) {
        return $(TextArea.class, view)
                .withCondition(area -> "Ask Spring AI Playground".equals(area.getPlaceholder()))
                .single();
    }

    private void completePendingPromptValueJs(String typedValue) {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(invocation -> invocation.getInvocation().getExpression().contains("return this.value"))
                .forEach(invocation -> invocation.complete(StringNode.valueOf(typedValue)));
    }

}
