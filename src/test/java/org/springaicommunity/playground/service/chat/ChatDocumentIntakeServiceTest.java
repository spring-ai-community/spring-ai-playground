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
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.ChatDocumentAttachment;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Grade;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService.Status;
import org.springaicommunity.playground.service.vectorstore.HierarchicalSummaryTransformer;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentPersistenceService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springaicommunity.playground.service.vectorstore.VectorStoreService.SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION;

class ChatDocumentIntakeServiceTest {

    private static final String CONVERSATION_ID = "conv-1";

    @TempDir
    Path homeDir;

    private OfflineEtlPipelineService documentService;
    private VectorStoreService vectorStoreService;
    private ChatDocumentIntakeService intakeService;

    private static final EmbeddingModel EMBEDDING_MODEL = new EmbeddingModel() {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(vectorOf(request.getInstructions().get(i)), i)).toList());
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document.getText());
        }

        private static float[] vectorOf(String text) {
            int hash = text == null ? 0 : text.hashCode();
            return new float[]{(hash & 0xFF) / 255f, ((hash >> 8) & 0xFF) / 255f, 1f};
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        VectorStoreDocumentPersistenceService persistence = mock(VectorStoreDocumentPersistenceService.class);
        ObjectProvider<VectorStoreDocumentPersistenceService> persistenceProvider = mock(ObjectProvider.class);
        when(persistenceProvider.getObject()).thenReturn(persistence);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("stub summary")))));
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        this.documentService = new OfflineEtlPipelineService(homeDir, DataSize.ofMegabytes(20),
                new DefaultResourceLoader(), persistenceProvider, chatModelProvider);
        this.vectorStoreService = new VectorStoreService(EMBEDDING_MODEL,
                SimpleVectorStore.builder(EMBEDDING_MODEL).build(), mock(ApplicationContext.class),
                persistenceProvider, mock(RagPipelineService.class), 0.35, 4);
        this.intakeService = new ChatDocumentIntakeService(homeDir, documentService, vectorStoreService, chatModel);
    }

    @AfterEach
    void tearDown() {
        this.intakeService.shutdown();
    }

    private ChatDocumentAttachment await(String attachId) {
        return await(this.intakeService, attachId);
    }

    private static ChatDocumentAttachment await(ChatDocumentIntakeService service, String attachId) {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            ChatDocumentAttachment attachment = service.list(CONVERSATION_ID).stream()
                    .filter(item -> item.attachId().equals(attachId)).findFirst().orElseThrow();
            if (!attachment.processing()) return attachment;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Attachment did not settle in time");
    }

    private static byte[] wordsOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> "word" + i)
                .collect(Collectors.joining(" ")).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void smallTextStaysInlineWithoutIndexing() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "notes.txt", wordsOf(100), "text/plain");

        ChatDocumentAttachment settled = await(attachment.attachId());

        assertThat(settled.status()).isEqualTo(Status.READY);
        assertThat(settled.grade()).isEqualTo(Grade.SMALL);
        assertThat(settled.inlineText()).contains("word42");
        assertThat(settled.docInfoId()).isNull();
        assertThat(this.documentService.getDocumentList()).isEmpty();
    }

    @Test
    void mediumTextIndexesChunksAndSummarizes() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain");

        ChatDocumentAttachment settled = await(attachment.attachId());

        assertThat(settled.status()).isEqualTo(Status.READY);
        assertThat(settled.grade()).isEqualTo(Grade.MEDIUM);
        assertThat(settled.summary()).isEqualTo("stub summary");
        assertThat(settled.docInfoId()).isNotNull();
        assertThat(settled.chunkCount()).isGreaterThan(1);
        assertThat(this.documentService.getDocumentList()).singleElement()
                .satisfies(info -> {
                    assertThat(info.title()).isEqualTo("report.txt");
                    assertThat(info.chatOrigin()).isTrue();
                });
        assertThat(this.documentService.getVisibleDocumentList()).isEmpty();
        List<Document> stored = this.vectorStoreService.search(
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(settled.docInfoId())));
        assertThat(stored).hasSize(settled.chunkCount());
        assertThat(stored).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry(HierarchicalSummaryTransformer.LEVEL, 1).containsEntry("source", "report.txt"));
    }

    @Test
    void blankExtractionFailsHonestly() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "blank.txt", "   \n   ".getBytes(StandardCharsets.UTF_8),
                        "text/plain");

        ChatDocumentAttachment settled = await(attachment.attachId());

        assertThat(settled.status()).isEqualTo(Status.FAILED);
        assertThat(settled.error()).contains("No extractable text");
    }

    @Test
    void emptyFileFailsBeforePipeline() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "empty.txt", new byte[0], "text/plain");

        assertThat(attachment.status()).isEqualTo(Status.FAILED);
        assertThat(attachment.error()).contains("empty");
    }

    @Test
    void summaryFailureRollsBackTheChunksAndTheRegistryEntry() throws Exception {
        ChatModel failing = mock(ChatModel.class);
        when(failing.call(any(Prompt.class))).thenThrow(new IllegalStateException("no chat model is configured"));
        ChatDocumentIntakeService failingIntake =
                new ChatDocumentIntakeService(homeDir, this.documentService, this.vectorStoreService, failing);
        try {
            ChatDocumentAttachment settled = await(failingIntake,
                    failingIntake.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain").attachId());

            assertThat(settled.status()).isEqualTo(Status.FAILED);
            assertThat(settled.docInfoId()).as("the record still names what it had indexed").isNotNull();
            assertThat(this.documentService.getDocumentList())
                    .as("a failed summary leaves no registry entry behind").isEmpty();
            assertThat(this.vectorStoreService.search(
                    SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(settled.docInfoId()))))
                    .as("a failed summary leaves no orphan chunks in the store").isEmpty();
        } finally {
            failingIntake.shutdown();
        }
    }

    @Test
    void embeddingFailureRollsBackTheRegistryEntry() throws Exception {
        VectorStoreService failingStore = mock(VectorStoreService.class);
        doThrow(new IllegalStateException("embedding model is unreachable")).when(failingStore)
                .add(any(VectorStoreDocumentInfo.class));
        ChatDocumentIntakeService failingIntake =
                new ChatDocumentIntakeService(homeDir, this.documentService, failingStore, mock(ChatModel.class));
        try {
            ChatDocumentAttachment settled = await(failingIntake,
                    failingIntake.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain").attachId());

            assertThat(settled.status()).isEqualTo(Status.FAILED);
            assertThat(settled.error()).contains("embedding model is unreachable");
            assertThat(settled.docInfoId()).isNull();
            assertThat(this.documentService.getDocumentList())
                    .as("a failed embedding leaves no registry entry behind").isEmpty();
        } finally {
            failingIntake.shutdown();
        }
    }

    @Test
    void unreadableIndexMovesAsideInsteadOfBeingOverwritten() throws Exception {
        Path indexFile = homeDir.resolve("chat").resolve("attachments").resolve(CONVERSATION_ID + ".json");
        Files.writeString(indexFile, "{ this is not the index json");

        assertThat(this.intakeService.list(CONVERSATION_ID)).isEmpty();

        this.intakeService.attach(CONVERSATION_ID, "notes.txt", wordsOf(100), "text/plain");
        assertThat(indexFile).as("the conversation gets a fresh index").exists();
        try (Stream<Path> files = Files.list(indexFile.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .as("the unreadable content is kept for recovery")
                    .anyMatch(name -> name.startsWith(CONVERSATION_ID + ".json.corrupt-"));
        }
        assertThat(Files.readString(indexFile)).doesNotContain("not the index json");
    }

    @Test
    void removeDeletesVectorsRegistryAndIndex() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain");
        ChatDocumentAttachment settled = await(attachment.attachId());

        this.intakeService.remove(CONVERSATION_ID, settled.attachId());

        assertThat(this.intakeService.list(CONVERSATION_ID)).isEmpty();
        assertThat(this.documentService.getDocumentList()).isEmpty();
        assertThat(this.vectorStoreService.search(
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(settled.docInfoId())))).isEmpty();
    }

    @Test
    void promoteMakesDocumentVisibleAndDetachesLifecycle() {
        ChatDocumentAttachment attachment =
                this.intakeService.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain");
        ChatDocumentAttachment settled = await(attachment.attachId());
        assertThat(this.documentService.getVisibleDocumentList()).isEmpty();

        ChatDocumentAttachment promoted = this.intakeService.promote(CONVERSATION_ID, settled.attachId());

        assertThat(promoted.promoted()).isTrue();
        assertThat(this.documentService.getVisibleDocumentList()).singleElement()
                .satisfies(info -> assertThat(info.chatOrigin()).isFalse());

        this.intakeService.remove(CONVERSATION_ID, promoted.attachId());

        assertThat(this.intakeService.list(CONVERSATION_ID)).isEmpty();
        assertThat(this.documentService.getVisibleDocumentList()).hasSize(1);
        assertThat(this.vectorStoreService.search(
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(settled.docInfoId()))))
                .hasSize(settled.chunkCount());
    }

    @Test
    void removeConversationCleansUnpromotedButKeepsPromoted() {
        ChatDocumentAttachment first =
                await(this.intakeService.attach(CONVERSATION_ID, "report.txt", wordsOf(4000), "text/plain")
                        .attachId());
        ChatDocumentAttachment second =
                await(this.intakeService.attach(CONVERSATION_ID, "keeper.txt", wordsOf(4000), "text/plain")
                        .attachId());
        this.intakeService.promote(CONVERSATION_ID, second.attachId());

        this.intakeService.removeConversation(CONVERSATION_ID);

        assertThat(this.intakeService.list(CONVERSATION_ID)).isEmpty();
        assertThat(this.documentService.getDocumentList()).singleElement()
                .satisfies(info -> assertThat(info.docInfoId()).isEqualTo(second.docInfoId()));
        assertThat(this.vectorStoreService.search(
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(first.docInfoId())))).isEmpty();
        assertThat(this.vectorStoreService.search(
                SEARCH_ALL_REQUEST_WITH_DOC_INFO_IDS_FUNCTION.apply(List.of(second.docInfoId()))))
                .hasSize(second.chunkCount());
    }
}
