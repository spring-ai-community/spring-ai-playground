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
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ChatToolPreferences;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.StringNode;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = "spring.ai.playground.chat.tool-search.min-tools=0")
class ChatToolCoexistenceSendTest extends SpringBrowserlessTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoSpyBean
    private ChatService chatService;

    @MockitoSpyBean
    private McpClientService mcpClientService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void stubModel() {
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("mock reply from browserless"))))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicOnPlusSelectedMcpServerSendsTheServerToolsToo() {
        long now = System.currentTimeMillis();
        this.mcpServerInfoService.updateMcpServerInfo(McpTransportType.SSE, "coexist-sse",
                new McpServerInfo(McpTransportType.SSE, "coexist-sse", "connected before the chat", now, now, null));
        try {
            ToolDefinition def = mock(ToolDefinition.class);
            lenient().when(def.name()).thenReturn("coexistMcpTool");
            ToolCallback mcpTool = mock(ToolCallback.class);
            lenient().when(mcpTool.getToolDefinition()).thenReturn(def);
            ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
            lenient().when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] {mcpTool});
            doReturn(List.of(provider)).when(this.mcpClientService)
                    .buildToolCallbackProviders(any(McpServerInfo.class));

            ArgumentCaptor<List<ToolCallback>> toolCaptor = ArgumentCaptor.forClass(List.class);
            doReturn(Flux.empty()).when(this.chatService).stream(any(), any(), any(), any(), toolCaptor.capture(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory("coexist-conv", "Coexist", now, now, "sys",
                    (DefaultChatOptions) ChatOptions.builder().build(), List::of)
                    .withToolPreferences(ChatToolPreferences.defaults().withDynamicTools(true)));
            UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", "coexist-conv"));
            roundTrip();
            ChatView view = (ChatView) getCurrentView();

            assertThat(dynamicCheckbox(view).getValue()).isTrue();
            MultiSelectComboBox<McpServerInfo> combo = mcpServerCombo(view);
            test(combo).selectItem("coexist-sse(SSE)");
            ComponentUtil.fireEvent(combo,
                    new AbstractField.ComponentValueChangeEvent<>(combo, combo, Set.of(), true));
            roundTrip();

            TextArea prompt = promptArea(view);
            test(prompt).setValue("use the mcp tool");
            Button submit = $(Button.class, view)
                    .withCondition(button -> "Submit".equals(button.getTooltip().getText())).single();
            test(submit).click();
            completePendingPromptValueJs("use the mcp tool");
            roundTrip();

            List<String> sentToolNames = toolCaptor.getValue().stream()
                    .map(callback -> callback.getToolDefinition().name()).toList();
            assertThat(sentToolNames).contains("coexistMcpTool");
        } finally {
            this.mcpServerInfoService.deleteMcpServerInfo(McpTransportType.SSE, "coexist-sse");
        }
    }

    private Checkbox dynamicCheckbox(ChatView view) {
        return $(Checkbox.class, view)
                .withCondition(box -> "Dynamic tool discovery".equals(box.getLabel()))
                .single();
    }

    @SuppressWarnings("unchecked")
    private MultiSelectComboBox<McpServerInfo> mcpServerCombo(ChatView view) {
        return $(MultiSelectComboBox.class, view)
                .withCondition(combo -> "Access Tools via external MCP connections"
                        .equals(combo.getTooltip().getText()))
                .single();
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
