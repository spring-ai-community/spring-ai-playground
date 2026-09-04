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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springaicommunity.playground.service.AttachedDocumentRagAdvisor;
import org.springaicommunity.playground.service.SpringAiPlaygroundRagAdvisor;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.ChatDocumentAttachment;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Grade;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Status;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springaicommunity.playground.service.AttachedDocumentRagAdvisor.EXCERPT_TOP_K;
import static org.springaicommunity.playground.service.SpringAiPlaygroundRagAdvisor.RAG_SEARCH_COMPLETED_MESSAGE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChatRagToolComboScenarioTest {

    private static final int PIPELINE_TOP_K = 3;
    private static final double PIPELINE_THRESHOLD = 0.5d;
    private static final String PIPELINE_CHUNK_MARK = "PIPELINE-CHUNK-MARK";
    private static final String EXCERPT_MARK = "ATTACH-EXCERPT-MARK";
    private static final String PIPELINE_START_PREFIX = "Running RAG pipeline";
    private static final String CONTENT_QUERY = "penalty clause escalation path";
    private static final String NO_CONTENT_QUERY = "요약해줘";

    @Autowired
    ChatService chatService;

    @Autowired
    RagPipelineService ragPipelineService;

    @Autowired
    OfflineEtlPipelineService offlineEtlPipelineService;

    @Autowired
    ChatDocumentIntakeService intakeService;

    @Autowired
    SpringAiPlaygroundRagAdvisor pipelineRagAdvisor;

    @Autowired
    AttachedDocumentRagAdvisor attachedDocumentRagAdvisor;

    @Autowired
    Path homeDir;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean
    VectorStore vectorStore;

    @MockitoBean
    EmbeddingModel embeddingModel;

    @MockitoSpyBean
    RagPipelineExecutor ragPipelineExecutor;

    private static final Map<String, String> CHUNK_MARKS_BY_DOC_INFO_ID = new LinkedHashMap<>();

    private final List<String> pipelineIds = new ArrayList<>();
    private final List<String> conversationIds = new ArrayList<>();
    private final List<VectorStoreDocumentInfo> documents = new ArrayList<>();

    @BeforeEach
    void stubModelAndStore() {
        CHUNK_MARKS_BY_DOC_INFO_ID.clear();
        lenient().when(this.chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        lenient().when(this.chatModel.stream(any(Prompt.class))).thenReturn(textReply("assistant reply"));
        lenient().when(this.chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("STUB-SUMMARY")))));
        lenient().when(this.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenAnswer(ChatRagToolComboScenarioTest::answerSearch);
    }

    @AfterEach
    void cleanUp() {
        this.conversationIds.forEach(this.intakeService::removeConversation);
        this.pipelineIds.forEach(this.ragPipelineService::deleteById);
        this.documents.forEach(this.offlineEtlPipelineService::deleteDocumentInfo);
        this.conversationIds.clear();
        this.pipelineIds.clear();
        this.documents.clear();
    }

    @Test
    void bothRagAdvisorsRunAfterMemoryAndAheadOfTheToolLoop() {
        assertThat(this.pipelineRagAdvisor.getOrder())
                .as("retrieval must run after the memory advisor has merged history and stored the raw question")
                .isGreaterThan(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
        assertThat(this.attachedDocumentRagAdvisor.getOrder())
                .as("both advisors must run before the tool-calling loop re-invokes the rest of the chain")
                .isLessThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    @Test
    void bothRagAdvisorsStayAheadOfTheTerminalModelAdvisor() {
        assertThat(this.pipelineRagAdvisor.getOrder())
                .as("pipeline retrieval must run before attachment context")
                .isLessThan(this.attachedDocumentRagAdvisor.getOrder());
        assertThat(this.attachedDocumentRagAdvisor.getOrder())
                .as("attachment context must run before the model is called")
                .isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void noRagSourceLeavesUserPromptUntouched() {
        List<Object> ragMessages = new ArrayList<>();
        send(newHistory(), CONTENT_QUERY, null, null, ragMessages);

        assertThat(userTextSentToModel()).as("RC-01 user text").isEqualTo(CONTENT_QUERY);
        assertThat(ragMessages).as("RC-01 no rag panel output").isEmpty();
    }

    @Test
    void retrievalOnlyPipelineReachesTheModelPrompt() {
        VectorStoreDocumentInfo document = registerDocument("curated-rc02", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rc02", List.of(document.docInfoId()));
        List<Object> ragMessages = new ArrayList<>();

        send(newHistory(), CONTENT_QUERY, pipeline.id(), null, ragMessages);

        assertThat(userTextSentToModel()).as("RC-02 retrieved chunk reaches the model")
                .contains(PIPELINE_CHUNK_MARK).contains(CONTENT_QUERY);
        assertThat(traceOf(ragMessages)).as("RC-02 panel trace").anyMatch(line -> line.startsWith(PIPELINE_START_PREFIX))
                .contains(RAG_SEARCH_COMPLETED_MESSAGE);
    }

    @Test
    void emptyRetrievalOnADocumentSourceKeepsTheQuestion() {
        VectorStoreDocumentInfo document = unindexedDocument("curated-empty");

        String reply = send(newHistory(), CONTENT_QUERY, "doc:" + document.docInfoId(), null, null);

        assertThat(reply).as("the turn still answers").isEqualTo("assistant reply");
        assertThat(userTextSentToModel())
                .as("a document picked in the chat combo keeps the question when nothing is retrieved")
                .contains(CONTENT_QUERY).doesNotContain("outside your knowledge base");
    }

    @Test
    void singleDocumentSourceScopesToThatDocument() {
        VectorStoreDocumentInfo document = registerDocument("curated-rc03", false);
        List<Object> ragMessages = new ArrayList<>();

        send(newHistory(), CONTENT_QUERY, "doc:" + document.docInfoId(), null, ragMessages);

        assertThat(traceOf(ragMessages)).as("RC-03 scope line")
                .anyMatch(line -> line.contains("Scope: 1 selected documents"));
        assertThat(userTextSentToModel()).as("RC-03 the picked document is what gets retrieved")
                .contains(chunkMarkOf("curated-rc03"));
    }

    @Test
    void scopedPipelineRetrievesOnlyTheDocumentsInItsScope() {
        VectorStoreDocumentInfo inScope = registerDocument("curated-rc06-in", false);
        registerDocument("curated-rc06-out", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rc06", List.of(inScope.docInfoId()));

        send(newHistory(), CONTENT_QUERY, pipeline.id(), null, null);

        assertThat(capturedSearchRequests()).as("RC-06 the scope reaches the store as an IN filter")
                .anyMatch(request -> request.getFilterExpression() != null
                        && request.getFilterExpression().type() == Filter.ExpressionType.IN);
        assertThat(userTextSentToModel()).as("RC-06 only the scoped document is retrieved")
                .contains(chunkMarkOf("curated-rc06-in")).doesNotContain(chunkMarkOf("curated-rc06-out"));
    }

    @Test
    void unscopedPipelineExcludesConversationAttachments() throws Exception {
        String conversationId = newConversationId();
        ChatDocumentAttachment attachment = attachMedium(conversationId, "rc05-medium.txt");
        CHUNK_MARKS_BY_DOC_INFO_ID.put(attachment.docInfoId(), chunkMarkOf("rc05-attachment"));
        VectorStoreDocumentInfo curated = registerDocument("curated-rc05", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rc05", List.of());
        List<Object> ragMessages = new ArrayList<>();

        send(newHistory(conversationId), CONTENT_QUERY, pipeline.id(), null, ragMessages);

        assertThat(traceOf(ragMessages)).as("RC-05 NIN exclusion trace")
                .anyMatch(line -> line.contains("excluding") && line.contains("conversation attachment"));
        assertThat(capturedSearchRequests()).as("RC-05 the exclusion reaches the store as a NIN filter")
                .anyMatch(request -> request.getTopK() == PIPELINE_TOP_K
                        && request.getFilterExpression() != null
                        && request.getFilterExpression().type() == Filter.ExpressionType.NIN);
        String userText = userTextSentToModel();
        assertThat(userText).as("RC-05 knowledge-base documents are still retrieved")
                .contains(chunkMarkOf("curated-rc05"));
        assertThat(userText).as("RC-05 the conversation attachment never enters pipeline retrieval")
                .doesNotContain(chunkMarkOf("rc05-attachment"));
        assertThat(curated.chatOrigin()).as("RC-05 precondition curated document is not chat-origin").isFalse();
    }

    @Test
    void attachmentExcerptSearchIsScopedToTheAttachedDocuments() throws Exception {
        String conversationId = newConversationId();
        ChatDocumentAttachment attachment = attachMedium(conversationId, "at05-medium.txt");

        send(newHistory(conversationId), CONTENT_QUERY, null, null, null);

        assertThat(capturedSearchRequests())
                .as("AT-05 excerpt search filters on the attached docInfoId")
                .anyMatch(request -> request.getTopK() == EXCERPT_TOP_K
                        && request.getFilterExpression() != null
                        && request.getFilterExpression().type() == Filter.ExpressionType.IN
                        && filteredIds(request.getFilterExpression()).contains(attachment.docInfoId()));
    }

    @Test
    void documentSourceAndSmallAttachmentBothReachTheModel() throws Exception {
        String conversationId = newConversationId();
        VectorStoreDocumentInfo document = registerDocument("curated-ra07", false);
        awaitSettled(conversationId, attachSmall(conversationId, "ra07-small.txt").attachId());

        send(newHistory(conversationId), CONTENT_QUERY, "doc:" + document.docInfoId(), null, null);

        String userText = userTextSentToModel();
        assertThat(userText).as("RA-07 the doc: source still retrieves")
                .contains(chunkMarkOf("curated-ra07"));
        assertThat(userText).as("RA-07 the small attachment is inlined in the same turn")
                .contains("SMALL-ATTACHMENT-BODY");
    }

    @Test
    void smallAttachmentCoexistsWithManualBuiltinTools() throws Exception {
        String conversationId = newConversationId();
        awaitSettled(conversationId, attachSmall(conversationId, "at01-small.txt").attachId());
        ToolCallback callback = probeCallback("at01Probe");

        send(newHistory(conversationId), CONTENT_QUERY, null, List.of(callback), null);

        assertThat(userTextSentToModel()).as("AT-01 inline attachment context reaches the model")
                .contains("SMALL-ATTACHMENT-BODY");
        assertThat(toolNamesSentToModel()).as("AT-01 the selected tool still reaches the model")
                .containsExactly("at01Probe");
    }

    @Test
    void deletedPipelineSkipsRetrievalWithoutFailingTheTurn() {
        List<Object> ragMessages = new ArrayList<>();

        String reply = send(newHistory(), CONTENT_QUERY, "pipeline-that-no-longer-exists", null, ragMessages);

        assertThat(reply).as("RC-06 turn still answers").isEqualTo("assistant reply");
        assertThat(traceOf(ragMessages)).as("RC-06 honest skip notice")
                .anyMatch(line -> line.contains("was not found - retrieval skipped"))
                .contains(RAG_SEARCH_COMPLETED_MESSAGE);
        assertThat(userTextSentToModel()).as("RC-06 prompt untouched").isEqualTo(CONTENT_QUERY);
    }

    @Test
    void pipelineOwnedTopKAndThresholdReachTheSearchRequest() {
        VectorStoreDocumentInfo document = registerDocument("curated-rc08", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rc08", List.of(document.docInfoId()));

        send(newHistory(), CONTENT_QUERY, pipeline.id(), null, null);

        assertThat(capturedSearchRequests())
                .as("RC-08 pipeline values override the global search option")
                .anyMatch(request -> request.getTopK() == PIPELINE_TOP_K
                        && Double.compare(request.getSimilarityThreshold(), PIPELINE_THRESHOLD) == 0);
    }

    @Test
    void pipelineContextAndAttachmentContextBothReachTheModel() throws Exception {
        String conversationId = newConversationId();
        ChatDocumentAttachment attachment = attachMedium(conversationId, "ra01-medium.txt");
        assertThat(attachment.grade()).as("RA-01 precondition grade").isEqualTo(Grade.MEDIUM);
        VectorStoreDocumentInfo document = registerDocument("curated-ra01", false);
        RagPipeline pipeline = retrievalOnlyPipeline("ra01", List.of(document.docInfoId()));

        send(newHistory(conversationId), CONTENT_QUERY, pipeline.id(), null, null);

        String userText = userTextSentToModel();
        assertThat(userText).as("RA-01 attachment context reaches the model")
                .contains("ra01-medium.txt");
        assertThat(userText).as("RA-01 pipeline retrieved chunk also reaches the model")
                .contains(PIPELINE_CHUNK_MARK);
    }

    @Test
    void smallAttachmentIsInlinedWithoutVectorSearch() throws Exception {
        String conversationId = newConversationId();
        ChatDocumentAttachment attachment = awaitSettled(conversationId,
                attachSmall(conversationId, "ra02-small.txt").attachId());
        assertThat(attachment.grade()).as("RA-02 precondition grade").isEqualTo(Grade.SMALL);

        send(newHistory(conversationId), CONTENT_QUERY, null, null, null);

        assertThat(userTextSentToModel()).as("RA-02 inline full text")
                .contains("Full text:").contains("SMALL-ATTACHMENT-BODY");
        assertThat(capturedSearchRequests()).as("RA-02 no excerpt search for a small attachment")
                .noneMatch(request -> request.getTopK() == EXCERPT_TOP_K);
    }

    @Test
    void mediumAttachmentAddsExcerptsForAContentQuery() throws Exception {
        String conversationId = newConversationId();
        attachMedium(conversationId, "ra03-medium.txt");

        send(newHistory(conversationId), CONTENT_QUERY, null, null, null);

        assertThat(userTextSentToModel()).as("RA-03 overview plus excerpts")
                .contains("Overview:").contains(EXCERPT_MARK);
        assertThat(capturedSearchRequests()).as("RA-03 excerpt search bounds")
                .anyMatch(request -> request.getTopK() == EXCERPT_TOP_K
                        && Double.compare(request.getSimilarityThreshold(), 0.0d) == 0);
    }

    @Test
    void mediumAttachmentSearchesExcerptsForDirectiveOnlyQuestions() throws Exception {
        String conversationId = newConversationId();
        attachMedium(conversationId, "ra04-medium.txt");
        List<Object> ragMessages = new ArrayList<>();

        send(newHistory(conversationId), NO_CONTENT_QUERY, null, null, ragMessages);

        assertThat(traceOf(ragMessages)).as("RA-04 excerpt search announced")
                .anyMatch(line -> line.contains("Searching attached documents"));
        assertThat(capturedSearchRequests()).as("RA-04 scoped excerpt search")
                .anyMatch(request -> request.getTopK() == EXCERPT_TOP_K);
        assertThat(userTextSentToModel()).as("RA-04 overview still injected").contains("Overview:");
    }

    @Test
    void processingAttachmentIsDisclosedRatherThanSilentlyDropped() {
        String conversationId = newConversationId();
        this.intakeService.attach(conversationId, "ra05-medium.txt",
                mediumBody().getBytes(StandardCharsets.UTF_8), "text/plain");

        send(newHistory(conversationId), CONTENT_QUERY, null, null, null);

        assertThat(userTextSentToModel()).as("RA-05 processing disclosure")
                .contains("ra05-medium.txt").contains("Still being processed");
    }

    @Test
    void failedAttachmentInjectsNoContextAndTheTurnStillAnswers() {
        String conversationId = newConversationId();
        ChatDocumentAttachment attachment = this.intakeService.attach(conversationId, "ra06-empty.txt",
                new byte[0], "text/plain");
        assertThat(attachment.status()).as("RA-06 precondition").isEqualTo(Status.FAILED);

        String reply = send(newHistory(conversationId), CONTENT_QUERY, null, null, null);

        assertThat(reply).as("RA-06 turn still answers").isEqualTo("assistant reply");
        assertThat(userTextSentToModel()).as("RA-06 failed attachment contributes no context")
                .isEqualTo(CONTENT_QUERY);
    }

    @Test
    void attachmentCapIsAUiGuardRatherThanAServiceContract() throws Exception {
        String conversationId = newConversationId();
        for (int i = 0; i < ChatDocumentIntakeService.MAX_ATTACHMENTS + 1; i++)
            awaitSettled(conversationId, attachSmall(conversationId, "at03-" + i + ".txt").attachId());

        assertThat(this.intakeService.list(conversationId))
                .as("AT-03 the service itself does not enforce MAX_ATTACHMENTS")
                .hasSize(ChatDocumentIntakeService.MAX_ATTACHMENTS + 1);
    }

    @Test
    void processingAttachmentIsRepairedToFailedWhenLoadedAfterARestart() throws Exception {
        String conversationId = newConversationId();
        Path indexFile = this.homeDir.resolve("chat").resolve("attachments").resolve(conversationId + ".json");
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, """
                [{"attachId":"ps04","fileName":"ps04.txt","mimeType":"text/plain","bytes":10,"grade":"MEDIUM",\
                "status":"INDEXING","statusDetail":null,"docInfoId":null,"storedFileName":null,"summary":null,\
                "inlineText":null,"tokenCount":9000,"chunkCount":0,"error":null,"promoted":false,\
                "createTimestamp":1,"updateTimestamp":1}]""");

        List<ChatDocumentAttachment> loaded = this.intakeService.list(conversationId);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().status()).as("PS-04 interrupted work is not left processing")
                .isEqualTo(Status.FAILED);
        assertThat(loaded.getFirst().error()).as("PS-04 honest reason")
                .isEqualTo("Interrupted by application restart.");
    }

    @Test
    void mediumAttachmentExcerptsCoexistWithMcpStyleToolCallbacks() throws Exception {
        String conversationId = newConversationId();
        attachMedium(conversationId, "at02-medium.txt");
        ToolCallback callback = probeCallback("at02McpProbe");

        send(newHistory(conversationId), CONTENT_QUERY, null, List.of(callback), null);

        assertThat(toolNamesSentToModel()).as("AT-02 tool still reaches the model").containsExactly("at02McpProbe");
        assertThat(userTextSentToModel()).as("AT-02 attachment overview and excerpts survive tool wiring")
                .contains("Overview:").contains(EXCERPT_MARK);
    }

    @Test
    void manualBuiltinToolsCoexistWithPipelineRetrieval() {
        VectorStoreDocumentInfo document = registerDocument("curated-rt01", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rt01", List.of(document.docInfoId()));
        ToolCallback callback = probeCallback("rt01Probe");

        send(newHistory(), CONTENT_QUERY, pipeline.id(), List.of(callback), null);

        assertThat(toolNamesSentToModel()).as("RT-01 tool reaches the model").containsExactly("rt01Probe");
        assertThat(userTextSentToModel()).as("RT-01 retrieval survives tool wiring").contains(PIPELINE_CHUNK_MARK);
    }

    @Test
    void dynamicToolsCoexistWithPipelineRetrieval() {
        // The dedicated tool index owns a separate vector store; mock its model as well.
        when(this.embeddingModel.dimensions()).thenReturn(3);
        when(this.embeddingModel.embed(any(Document.class))).thenReturn(new float[] {1.0f, 0.0f, 0.0f});
        VectorStoreDocumentInfo document = registerDocument("curated-rt02", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rt02", List.of(document.docInfoId()));
        // A fresh name forces indexing even when a previous run persisted the tool index.
        ToolCallback callback = probeCallback("rt02Probe_" + UUID.randomUUID().toString().replace("-", ""));
        ChatHistory history = newHistory()
                .withToolPreferences(ChatToolPreferences.defaults().withDynamicTools(true));

        send(history, CONTENT_QUERY, pipeline.id(), List.of(callback), null);

        verify(this.embeddingModel, atLeastOnce()).embed(any(Document.class));
        assertThat(userTextSentToModel()).as("RT-02 retrieval survives dynamic mode").contains(PIPELINE_CHUNK_MARK);
        assertThat(toolNamesSentToModel())
                .as("RT-02 the turn really ran in dynamic mode: the pool is hidden behind the search tool")
                .containsExactly("toolSearchTool");
    }

    @Test
    void ragWorkRunsOncePerTurnNotPerToolRound() throws Exception {
        String conversationId = newConversationId();
        attachMedium(conversationId, "rt05-medium.txt");
        VectorStoreDocumentInfo document = registerDocument("curated-rt05", false);
        RagPipeline pipeline = retrievalOnlyPipeline("rt05", List.of(document.docInfoId()));
        ToolCallback callback = probeCallback("rt05Probe");
        when(this.chatModel.stream(any(Prompt.class))).thenReturn(toolCallReply("rt05Probe"))
                .thenReturn(textReply("assistant reply"));
        List<Object> ragMessages = new ArrayList<>();

        send(newHistory(conversationId), CONTENT_QUERY, pipeline.id(), List.of(callback), ragMessages);

        assertThat(countModelStreamCalls()).as("RT-05 precondition: the tool round actually happened")
                .isEqualTo(2L);
        verify(this.ragPipelineExecutor, times(1))
                .executeForChat(any(RagPipeline.class), anyString(), any(), any());
        assertThat(traceOf(ragMessages).stream().filter(line -> line.startsWith(PIPELINE_START_PREFIX)).count())
                .as("RT-05 the RAG panel reports the pipeline once for the turn").isEqualTo(1L);
        assertThat(capturedSearchRequests().stream().filter(request -> request.getTopK() == EXCERPT_TOP_K).count())
                .as("RT-05 the attachment excerpt search also runs once for the turn").isEqualTo(1L);
        assertThat(userTextSentToModel()).as("RT-05 the augmented prompt still reaches the model")
                .contains(PIPELINE_CHUNK_MARK);
    }

    private ChatHistory newHistory() {
        return newHistory(newConversationId());
    }

    private ChatHistory newHistory(String conversationId) {
        long now = System.currentTimeMillis();
        return new ChatHistory(conversationId, "Combo", now, now, "System prompt",
                (DefaultChatOptions) ChatOptions.builder().build(), List::of);
    }

    private String newConversationId() {
        String conversationId = "combo-" + UUID.randomUUID();
        this.conversationIds.add(conversationId);
        return conversationId;
    }

    private String send(ChatHistory chatHistory, String prompt, String ragSourceId,
            List<ToolCallback> toolCallbacks, List<Object> ragMessages) {
        return this.chatService.stream(chatHistory, prompt, ragSourceId, null, toolCallbacks,
                        message -> { }, ragMessages == null ? null : ragMessages::add, null)
                .toStream().collect(Collectors.joining());
    }

    private RagPipeline retrievalOnlyPipeline(String name, List<String> docInfoIds) {
        RagPipeline pipeline = this.ragPipelineService.create(name, null, docInfoIds, null,
                new RagPipeline.RetrievalConfig(null, PIPELINE_TOP_K, PIPELINE_THRESHOLD), null, null);
        this.pipelineIds.add(pipeline.id());
        return pipeline;
    }

    private VectorStoreDocumentInfo registerDocument(String title, boolean chatOrigin) {
        VectorStoreDocumentInfo document = this.offlineEtlPipelineService.loadDocument(title + ".txt", title, null,
                chatOrigin, List.of(new Document(PIPELINE_CHUNK_MARK + " body of " + title)));
        this.documents.add(document);
        CHUNK_MARKS_BY_DOC_INFO_ID.put(document.docInfoId(), chunkMarkOf(title));
        return document;
    }

    private VectorStoreDocumentInfo unindexedDocument(String title) {
        VectorStoreDocumentInfo document = registerDocument(title, false);
        CHUNK_MARKS_BY_DOC_INFO_ID.remove(document.docInfoId());
        return document;
    }

    private static String chunkMarkOf(String label) {
        return PIPELINE_CHUNK_MARK + "-" + label.toUpperCase(Locale.ROOT);
    }

    private ChatDocumentAttachment attachMedium(String conversationId, String fileName) throws Exception {
        ChatDocumentAttachment attachment = this.intakeService.attach(conversationId, fileName,
                mediumBody().getBytes(StandardCharsets.UTF_8), "text/plain");
        return awaitSettled(conversationId, attachment.attachId());
    }

    private ChatDocumentAttachment attachSmall(String conversationId, String fileName) {
        return this.intakeService.attach(conversationId, fileName,
                "SMALL-ATTACHMENT-BODY covering the penalty clause.".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private ChatDocumentAttachment awaitSettled(String conversationId, String attachId) throws Exception {
        long deadline = System.currentTimeMillis() + 60000L;
        while (System.currentTimeMillis() < deadline) {
            ChatDocumentAttachment attachment = this.intakeService.list(conversationId).stream()
                    .filter(item -> item.attachId().equals(attachId)).findFirst().orElseThrow();
            if (!attachment.processing()) return attachment;
            Thread.sleep(50L);
        }
        throw new IllegalStateException("attachment " + attachId + " never settled");
    }

    private static String mediumBody() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 900; i++)
            body.append("Section ").append(i)
                    .append(" describes the retention policy, the penalty clause and the escalation path.\n");
        return body.toString();
    }

    private String userTextSentToModel() {
        return lastUserText(capturedPrompts().getLast());
    }

    private List<String> toolNamesSentToModel() {
        ChatOptions options = capturedPrompts().getLast().getOptions();
        if (!(options instanceof ToolCallingChatOptions toolOptions)) return List.of();
        return toolOptions.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
    }

    private long countModelStreamCalls() {
        return capturedPrompts().size();
    }

    private List<Prompt> capturedPrompts() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(this.chatModel, atLeastOnce()).stream(captor.capture());
        return captor.getAllValues();
    }

    private List<SearchRequest> capturedSearchRequests() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(this.vectorStore, atLeast(0)).similaritySearch(captor.capture());
        return captor.getAllValues();
    }

    private static List<String> traceOf(List<Object> ragMessages) {
        return ragMessages.stream().map(String::valueOf).toList();
    }

    private static String lastUserText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(UserMessage.class::isInstance)
                .reduce((first, second) -> second).map(message -> ((UserMessage) message).getText()).orElse("");
    }

    private static List<Document> answerSearch(InvocationOnMock invocation) {
        SearchRequest request = invocation.getArgument(0);
        if (request.getTopK() == EXCERPT_TOP_K && Double.compare(request.getSimilarityThreshold(), 0.0d) == 0)
            return List.of(new Document(EXCERPT_MARK + " excerpt body"));
        if (request.getTopK() == EXCERPT_TOP_K) return List.of();
        return CHUNK_MARKS_BY_DOC_INFO_ID.entrySet().stream()
                .filter(entry -> admits(request.getFilterExpression(), entry.getKey()))
                .map(entry -> new Document(entry.getValue() + " retrieved body",
                        Map.of(VectorStoreService.DOC_INFO_ID, entry.getKey())))
                .map(Document.class::cast).toList();
    }

    private static boolean admits(Filter.Expression expression, String docInfoId) {
        if (expression == null) return true;
        return switch (expression.type()) {
            case AND -> admits((Filter.Expression) expression.left(), docInfoId)
                    && admits((Filter.Expression) expression.right(), docInfoId);
            case OR -> admits((Filter.Expression) expression.left(), docInfoId)
                    || admits((Filter.Expression) expression.right(), docInfoId);
            case NOT -> !admits((Filter.Expression) expression.left(), docInfoId);
            case IN -> !docInfoIdClause(expression) || filteredIds(expression).contains(docInfoId);
            case NIN -> !docInfoIdClause(expression) || !filteredIds(expression).contains(docInfoId);
            default -> true;
        };
    }

    private static boolean docInfoIdClause(Filter.Expression expression) {
        return expression.left() instanceof Filter.Key key
                && VectorStoreService.DOC_INFO_ID.equals(key.key());
    }

    private static List<String> filteredIds(Filter.Expression expression) {
        Object value = ((Filter.Value) expression.right()).value();
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        return values.stream().map(String::valueOf).toList();
    }

    private static ToolCallback probeCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        lenient().when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name)
                .description("combo probe").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        lenient().when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        lenient().when(callback.call(anyString(), any())).thenReturn("{\"ok\":true}");
        lenient().when(callback.call(anyString())).thenReturn("{\"ok\":true}");
        return callback;
    }

    private static Flux<ChatResponse> textReply(String text) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private static Flux<ChatResponse> toolCallReply(String toolName) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}"))).build();
        return Flux.just(new ChatResponse(List.of(new Generation(message))));
    }

}
