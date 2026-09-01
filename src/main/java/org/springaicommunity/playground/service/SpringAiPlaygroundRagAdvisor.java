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
package org.springaicommunity.playground.service;

import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.TraceEvent;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springaicommunity.playground.service.chat.ChatService.RAG_DOCUMENT_SOURCE_PREFIX;
import static org.springaicommunity.playground.service.chat.ChatService.RAG_SOURCE_ID;
import static org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT;

@Service
public class SpringAiPlaygroundRagAdvisor implements BaseAdvisor {
    public static final String RAG_PROCESS_MESSAGE_CONSUMER = "ragProcessMessageConsumer";
    public static final String RAG_SEARCH_COMPLETED_MESSAGE = "VectorDB document search completed.";

    static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 2;

    public record RagRetrievedDocumentsInfo(List<String> titles, int count) {}

    private static final Logger logger = LoggerFactory.getLogger(SpringAiPlaygroundRagAdvisor.class);

    private final RagPipelineService ragPipelineService;
    private final RagPipelineExecutor ragPipelineExecutor;
    private final SharedDataReader<List<VectorStoreDocumentInfo>> vectorStoreDocumentsReader;

    public SpringAiPlaygroundRagAdvisor(RagPipelineService ragPipelineService,
            RagPipelineExecutor ragPipelineExecutor,
            SharedDataReader<List<VectorStoreDocumentInfo>> vectorStoreDocumentsReader) {
        this.ragPipelineService = ragPipelineService;
        this.ragPipelineExecutor = ragPipelineExecutor;
        this.vectorStoreDocumentsReader = vectorStoreDocumentsReader;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Object sourceId = chatClientRequest.context().get(RAG_SOURCE_ID);
        if (Objects.isNull(sourceId)) {
            logger.debug("RAG pipeline execution was skipped.");
            return chatClientRequest;
        }
        String query = extractUserQuery(chatClientRequest);
        if (!StringUtils.hasText(query))
            return chatClientRequest;
        Optional<Consumer<Object>> ragProcessMessageConsumer = getRagProcessMessageConsumer(chatClientRequest);
        Optional<RagPipeline> pipeline = resolveSource(sourceId.toString());
        if (pipeline.isEmpty()) {
            logger.warn("RAG source {} not found - retrieval skipped.", sourceId);
            ragProcessMessageConsumer.ifPresent(consumer -> {
                consumer.accept("RAG source `" + sourceId + "` was not found - retrieval skipped.");
                consumer.accept(RAG_SEARCH_COMPLETED_MESSAGE);
            });
            return chatClientRequest;
        }
        ragProcessMessageConsumer.ifPresent(consumer -> consumer.accept(formatPipelineStart(pipeline.get(), query)));
        Consumer<TraceEvent> trace = event ->
                ragProcessMessageConsumer.ifPresent(consumer -> consumer.accept(formatTraceEvent(event)));
        RagPipelineExecutor.RunResult result = this.ragPipelineExecutor.executeForChat(pipeline.get(), query,
                extractHistory(chatClientRequest), trace);
        List<Document> retrievedDocuments = result.finalDocs();
        printSearchResults(retrievedDocuments);
        ragProcessMessageConsumer.ifPresent(consumer -> {
            consumer.accept(new RagRetrievedDocumentsInfo(retrievedTitles(retrievedDocuments),
                    retrievedDocuments.size()));
            consumer.accept(formatRetrievedDocuments(retrievedDocuments));
            consumer.accept(RAG_SEARCH_COMPLETED_MESSAGE);
        });
        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(result.finalPrompt()))
                .context(DOCUMENT_CONTEXT, retrievedDocuments).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private Optional<RagPipeline> resolveSource(String sourceId) {
        if (sourceId.startsWith(RAG_DOCUMENT_SOURCE_PREFIX)) {
            String docInfoId = sourceId.substring(RAG_DOCUMENT_SOURCE_PREFIX.length());
            return this.vectorStoreDocumentsReader.read().stream()
                    .filter(documentInfo -> docInfoId.equals(documentInfo.docInfoId())).findFirst()
                    .map(documentInfo -> new RagPipeline(sourceId, documentInfo.title(), null,
                            List.of(docInfoId), null, null, null,
                            new RagPipeline.GenerationConfig(true, null, null, false), 0L, 0L));
        }
        return this.ragPipelineService.get(sourceId);
    }

    private String extractUserQuery(ChatClientRequest chatClientRequest) {
        return chatClientRequest.prompt().getInstructions().stream()
                .filter(m -> m instanceof UserMessage).reduce((first, second) -> second)
                .map(m -> ((UserMessage) m).getText()).filter(StringUtils::hasText).orElse("");
    }

    private List<Message> extractHistory(ChatClientRequest chatClientRequest) {
        List<Message> instructions = chatClientRequest.prompt().getInstructions();
        int lastUserIndex = -1;
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex < 0) return List.of();
        return instructions.subList(0, lastUserIndex).stream()
                .filter(message -> MessageType.USER.equals(message.getMessageType())
                        || MessageType.ASSISTANT.equals(message.getMessageType()))
                .toList();
    }

    private Optional<Consumer<Object>> getRagProcessMessageConsumer(ChatClientRequest chatClientRequest) {
        return Optional.ofNullable(chatClientRequest.context().get(RAG_PROCESS_MESSAGE_CONSUMER))
                .map(consumer -> (Consumer<Object>) consumer);
    }

    private static String formatPipelineStart(RagPipeline pipeline, String query) {
        return "Running RAG pipeline `" + pipeline.name() + "`...\n" +
                "- Query: `" + query + "`\n" +
                "- Scope: " + (pipeline.docInfoIds().isEmpty() ? "all documents"
                : pipeline.docInfoIds().size() + " selected documents") + "\n" +
                "- Stages: " + String.join(" → ", pipeline.stageLabels());
    }

    private static String formatTraceEvent(TraceEvent event) {
        String prefix = switch (event.level()) {
            case WARN -> "[warn] ";
            case ERROR -> "[error] ";
            default -> "";
        };
        return prefix + "`" + event.stage() + "` " + event.message();
    }

    private List<String> retrievedTitles(List<Document> retrievedDocuments) {
        Map<String, String> docInfoTitles = docInfoTitles();
        return retrievedDocuments.stream()
                .map(doc -> resolveDocumentTitle(doc, docInfoTitles)).distinct().toList();
    }

    private Map<String, String> docInfoTitles() {
        return this.vectorStoreDocumentsReader.read().stream()
                .collect(Collectors.toMap(VectorStoreDocumentInfo::docInfoId, VectorStoreDocumentInfo::title,
                        (current, ignored) -> current));
    }

    private String formatRetrievedDocuments(List<Document> results) {
        if (results.isEmpty())
            return "No matching VectorDB documents were found.";
        Map<String, String> docInfoTitles = docInfoTitles();
        return "Retrieved " + results.size() + " document chunks from VectorDB.\n" +
                IntStream.range(0, results.size()).mapToObj(i ->
                                formatRetrievedDocument(results.get(i), i, docInfoTitles))
                        .collect(Collectors.joining("\n"));
    }

    private String formatRetrievedDocument(Document document, int index, Map<String, String> docInfoTitles) {
        String title = resolveDocumentTitle(document, docInfoTitles);
        String score = Optional.ofNullable(document.getScore()).map(value -> String.format(Locale.ROOT, "%.3f", value))
                .orElse("n/a");
        String excerpt = Optional.ofNullable(document.getText()).map(text -> text.replaceAll("\\s+", " ").trim())
                .filter(StringUtils::hasText)
                .map(text -> text.length() <= 140 ? text : text.substring(0, 137) + "...")
                .orElse("No excerpt available.");
        return String.format("- %d. `%s` (score: %s): %s", index + 1, title, score, excerpt);
    }

    private String resolveDocumentTitle(Document document, Map<String, String> docInfoTitles) {
        Object docInfoId = document.getMetadata().get(VectorStoreService.DOC_INFO_ID);
        if (docInfoId != null) {
            String title = docInfoTitles.get(docInfoId.toString());
            if (StringUtils.hasText(title))
                return title;
        }
        return Optional.ofNullable(document.getMetadata().get("source")).map(Object::toString)
                .filter(StringUtils::hasText)
                .orElseGet(() -> Optional.ofNullable(document.getId()).filter(StringUtils::hasText)
                        .orElse("document"));
    }

    private static void printSearchResults(List<Document> results) {
        logger.debug("Retrieved Documents Count - {}", results.size());
        for (int i = 0; i < results.size(); i++) {
            Document document = results.get(i);
            logger.debug("Retrieved Document {}, Score: {}\n{}", i + 1, document.getScore(), document.getText());
        }
    }

}
