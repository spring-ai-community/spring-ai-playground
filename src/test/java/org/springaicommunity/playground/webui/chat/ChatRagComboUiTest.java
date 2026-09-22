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
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ChatToolPreferences;
import org.springaicommunity.playground.service.chat.ReasoningEffort;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@SpringBootTest(properties = "spring.ai.playground.chat.tool-search.min-tools=0")
class ChatRagComboUiTest extends SpringBrowserlessTest {

    private static final String SERVER_NAME = "rag-combo-sse";

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private RagPipelineService ragPipelineService;

    @Autowired
    private OfflineEtlPipelineService offlineEtlPipelineService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    private final List<String> pipelineIds = new ArrayList<>();
    private final List<String> conversationIds = new ArrayList<>();
    private final List<VectorStoreDocumentInfo> documents = new ArrayList<>();

    @BeforeEach
    void stubModel() {
        lenient().when(this.chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        lenient().when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("mock reply"))))));
    }

    @AfterEach
    void cleanUp() {
        this.pipelineIds.forEach(this.ragPipelineService::deleteById);
        this.documents.forEach(this.offlineEtlPipelineService::deleteDocumentInfo);
        this.conversationIds.stream().map(this.chatHistoryService::getChatHistory).filter(Objects::nonNull)
                .forEach(history -> this.chatHistoryService.deleteChatHistory(history, true));
        this.pipelineIds.clear();
        this.conversationIds.clear();
        this.documents.clear();
        this.mcpServerInfoService.deleteMcpServerInfo(McpTransportType.SSE, SERVER_NAME);
    }

    @Test
    void ragComboListsPipelinesBeforeDocumentsWithDistinctHints() {
        RagPipeline pipeline = createPipeline("ui01-pipeline");
        VectorStoreDocumentInfo document = registerDocument("ui01-document");

        UI.getCurrent().navigate(ChatView.class);
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        List<ChatService.RagSource> sources = ragCombo(view).getGenericDataView().getItems().toList();
        int pipelineIndex = indexOfName(sources, pipeline.name());
        int documentIndex = indexOfName(sources, document.title());
        assertThat(pipelineIndex).as("UI-01 pipeline present").isGreaterThanOrEqualTo(0);
        assertThat(documentIndex).as("UI-01 document present").isGreaterThanOrEqualTo(0);
        assertThat(pipelineIndex).as("UI-01 pipelines are listed before documents").isLessThan(documentIndex);
        assertThat(sources.get(pipelineIndex).hint()).as("UI-01 pipeline description")
                .contains("pipeline").contains("stages");
        assertThat(sources.get(documentIndex).hint()).as("UI-01 document description")
                .contains("retrieval only");
    }

    @Test
    void ragSourceAndMcpServerSelectionsAreIndependentAndBothPersist() {
        long now = System.currentTimeMillis();
        RagPipeline pipeline = createPipeline("ui04-pipeline");
        this.mcpServerInfoService.updateMcpServerInfo(McpTransportType.SSE, SERVER_NAME,
                new McpServerInfo(McpTransportType.SSE, SERVER_NAME, "connected", now, now, null));
        String conversationId = "ui04-conv";
        this.conversationIds.add(conversationId);
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory(conversationId, "Ui04", now, now, "sys",
                (DefaultChatOptions) ChatOptions.builder().build(), earlierTurn())
                .withToolPreferences(ChatToolPreferences.defaults()));

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", conversationId));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        ComboBox<ChatService.RagSource> rag = ragCombo(view);
        ChatService.RagSource selected = rag.getGenericDataView().getItems()
                .filter(source -> pipeline.name().equals(source.name())).findFirst().orElseThrow();
        rag.setValue(selected);
        ComponentUtil.fireEvent(rag, new AbstractField.ComponentValueChangeEvent<>(rag, rag, null, true));
        MultiSelectComboBox<McpServerInfo> mcp = mcpServerCombo(view);
        test(mcp).selectItem(SERVER_NAME + "(SSE)");
        ComponentUtil.fireEvent(mcp, new AbstractField.ComponentValueChangeEvent<>(mcp, mcp, Set.of(), true));
        roundTrip();

        assertThat(rag.getValue()).as("UI-04 RAG selection is not cleared by picking an MCP server")
                .isNotNull();
        assertThat(mcp.getValue().stream().map(McpServerInfo::serverName).toList())
                .as("UI-04 MCP selection is not cleared by picking a RAG source").contains(SERVER_NAME);
        ChatToolPreferences persisted = this.chatHistoryService.getChatHistory(conversationId).toolPreferences();
        assertThat(persisted.ragSourceId()).as("UI-04 the RAG source is persisted").isEqualTo(pipeline.id());
        assertThat(persisted.mcpServerNames().get(McpTransportType.SSE))
                .as("UI-04 the MCP selection is persisted").contains(SERVER_NAME);
    }

    @Test
    void ragSourceMcpSelectionAndDynamicDiscoveryAllRestoreOnAFreshView() {
        long now = System.currentTimeMillis();
        RagPipeline pipeline = createPipeline("ps02-pipeline");
        this.mcpServerInfoService.updateMcpServerInfo(McpTransportType.SSE, SERVER_NAME,
                new McpServerInfo(McpTransportType.SSE, SERVER_NAME, "connected", now, now, null));
        String conversationId = "ps02-conv";
        this.conversationIds.add(conversationId);
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory(conversationId, "Ps02", now, now, "sys",
                (DefaultChatOptions) ChatOptions.builder().build(), earlierTurn())
                .withToolPreferences(new ChatToolPreferences(false, Set.of(), pipeline.id(),
                        Map.of(McpTransportType.SSE, List.of(SERVER_NAME)), ReasoningEffort.DEFAULT, true)));

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", conversationId));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        Checkbox dynamic = $(Checkbox.class, view)
                .withCondition(box -> "Dynamic tool discovery".equals(box.getLabel())).single();
        assertThat(dynamic.getValue()).as("PS-02 dynamic discovery is restored").isTrue();
        assertThat(ragCombo(view).getValue()).as("PS-02 the RAG source is restored alongside it").isNotNull();
        assertThat(ragCombo(view).getValue().name()).isEqualTo(pipeline.name());
        assertThat(mcpServerCombo(view).getValue().stream().map(McpServerInfo::serverName).toList())
                .as("PS-01 the MCP selection is restored on a fresh view").contains(SERVER_NAME);
    }

    @Test
    void deletedRagSourceLeavesTheComboEmptyOnReloadInsteadOfFailing() {
        long now = System.currentTimeMillis();
        RagPipeline pipeline = this.ragPipelineService.create("ps05-pipeline", null, List.of(),
                null, null, null, null);
        String conversationId = "ps05-conv";
        this.conversationIds.add(conversationId);
        this.chatHistoryService.putIfAbsentChatHistory(new ChatHistory(conversationId, "Ps05", now, now, "sys",
                (DefaultChatOptions) ChatOptions.builder().build(), earlierTurn())
                .withToolPreferences(new ChatToolPreferences(false, Set.of(), pipeline.id(), Map.of(),
                        ReasoningEffort.DEFAULT, false)));
        this.ragPipelineService.deleteById(pipeline.id());

        UI.getCurrent().navigate(ChatView.class, QueryParameters.of("conv", conversationId));
        roundTrip();
        ChatView view = (ChatView) getCurrentView();

        assertThat(ragCombo(view).getValue())
                .as("PS-05 a conversation pointing at a deleted RAG source restores with no selection")
                .isNull();
        assertThat(ragCombo(view).getGenericDataView().getItems().map(ChatService.RagSource::sourceId).toList())
                .as("PS-05 the deleted pipeline is gone from the item list")
                .doesNotContain(pipeline.id());
    }

    private static Supplier<List<Message>> earlierTurn() {
        return () -> List.of(new UserMessage("earlier question"), new AssistantMessage("earlier answer"));
    }

    private RagPipeline createPipeline(String name) {
        RagPipeline pipeline = this.ragPipelineService.create(name, null, List.of(), null, null, null, null);
        this.pipelineIds.add(pipeline.id());
        return pipeline;
    }

    private VectorStoreDocumentInfo registerDocument(String title) {
        VectorStoreDocumentInfo document = this.offlineEtlPipelineService.loadDocument(title + ".txt", title, null,
                false, List.of(new Document("body of " + title)));
        this.documents.add(document);
        return document;
    }

    private static int indexOfName(List<ChatService.RagSource> sources, String name) {
        for (int i = 0; i < sources.size(); i++)
            if (name.equals(sources.get(i).name())) return i;
        return -1;
    }

    @SuppressWarnings("unchecked")
    private ComboBox<ChatService.RagSource> ragCombo(ChatView view) {
        return $(ComboBox.class, view)
                .withCondition(combo -> combo.getTooltip() != null && combo.getTooltip().getText() != null
                        && combo.getTooltip().getText().startsWith("RAG with a pipeline"))
                .single();
    }

    @SuppressWarnings("unchecked")
    private MultiSelectComboBox<McpServerInfo> mcpServerCombo(ChatView view) {
        return $(MultiSelectComboBox.class, view)
                .withCondition(combo -> "Access Tools via external MCP connections"
                        .equals(combo.getTooltip().getText()))
                .single();
    }

}
