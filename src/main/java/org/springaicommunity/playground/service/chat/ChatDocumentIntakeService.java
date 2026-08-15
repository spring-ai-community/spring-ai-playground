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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.playground.service.vectorstore.HierarchicalSummaryTransformer;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Service
public class ChatDocumentIntakeService {

    public static final int SMALL_MAX_TOKENS = 4000;
    public static final int LARGE_MIN_TOKENS = 40000;
    public static final int MAX_ATTACHMENTS = 5;

    public enum Grade {SMALL, MEDIUM, LARGE}

    public enum Status {EXTRACTING, INDEXING, SUMMARIZING, READY, FAILED}

    public record ChatDocumentAttachment(String attachId, String fileName, String mimeType, long bytes, Grade grade,
            Status status, String statusDetail, String docInfoId, String storedFileName, String summary,
            String inlineText, int tokenCount, int chunkCount, String error, boolean promoted, long createTimestamp,
            long updateTimestamp) {

        public ChatDocumentAttachment statusOf(Status newStatus, String newStatusDetail) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, newStatus, newStatusDetail,
                    docInfoId, storedFileName, summary, inlineText, tokenCount, chunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment graded(Grade newGrade, int newTokenCount, Status newStatus) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, newGrade, newStatus, null,
                    docInfoId, storedFileName, summary, inlineText, newTokenCount, chunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment readyInline(String newInlineText, int newTokenCount) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, Grade.SMALL, Status.READY, null,
                    docInfoId, storedFileName, summary, newInlineText, newTokenCount, chunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment readyIndexed(String newDocInfoId, String newSummary, int newChunkCount) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, Status.READY, null,
                    newDocInfoId, storedFileName, newSummary, inlineText, tokenCount, newChunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment indexed(String newDocInfoId, int newChunkCount, Status newStatus,
                String newStatusDetail) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, newStatus, newStatusDetail,
                    newDocInfoId, storedFileName, summary, inlineText, tokenCount, newChunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment stored(String newStoredFileName) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, status, statusDetail,
                    docInfoId, newStoredFileName, summary, inlineText, tokenCount, chunkCount, error, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment failed(String newError) {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, Status.FAILED, null,
                    docInfoId, storedFileName, summary, inlineText, tokenCount, chunkCount, newError, promoted,
                    createTimestamp, System.currentTimeMillis());
        }

        public ChatDocumentAttachment promotedCopy() {
            return new ChatDocumentAttachment(attachId, fileName, mimeType, bytes, grade, status, statusDetail,
                    docInfoId, storedFileName, summary, inlineText, tokenCount, chunkCount, error, true,
                    createTimestamp, System.currentTimeMillis());
        }

        public boolean processing() {
            return status == Status.EXTRACTING || status == Status.INDEXING || status == Status.SUMMARIZING;
        }

        public boolean promotable() {
            return status == Status.READY && docInfoId != null && !promoted;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ChatDocumentIntakeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChatDocumentAttachment>> LIST_TYPE = new TypeReference<>() {};

    private final Path attachmentsDir;
    private final VectorStoreDocumentService vectorStoreDocumentService;
    private final VectorStoreService vectorStoreService;
    private final HierarchicalSummaryTransformer summaryTransformer;
    private final TokenCountEstimator tokenCountEstimator;
    private final Map<String, List<ChatDocumentAttachment>> attachmentsByConversation = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<ChatDocumentAttachment>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ExecutorService intakeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-doc-intake");
        thread.setDaemon(false);
        return thread;
    });

    public ChatDocumentIntakeService(Path springAiPlaygroundHomeDir,
            VectorStoreDocumentService vectorStoreDocumentService, VectorStoreService vectorStoreService,
            ChatModel chatModel) throws IOException {
        this.attachmentsDir = springAiPlaygroundHomeDir.resolve("chat").resolve("attachments");
        Files.createDirectories(this.attachmentsDir);
        this.vectorStoreDocumentService = vectorStoreDocumentService;
        this.vectorStoreService = vectorStoreService;
        this.summaryTransformer = new HierarchicalSummaryTransformer(chatModel);
        this.tokenCountEstimator = new JTokkitTokenCountEstimator();
    }

    public List<ChatDocumentAttachment> list(String conversationId) {
        return this.attachmentsByConversation.computeIfAbsent(conversationId, this::loadFromDisk);
    }

    public ChatDocumentAttachment attach(String conversationId, String fileName, byte[] bytes, String mimeType) {
        long maxBytes = this.vectorStoreDocumentService.getMaxUploadSize().toBytes();
        long now = System.currentTimeMillis();
        String attachId = UUID.randomUUID().toString().substring(0, 8);
        ChatDocumentAttachment attachment = new ChatDocumentAttachment(attachId, fileName, mimeType,
                bytes == null ? 0 : bytes.length, null, Status.EXTRACTING, null, null, null, null, null, 0, 0, null,
                false, now, now);
        if (bytes == null || bytes.length == 0)
            attachment = attachment.failed("The file is empty.");
        else if (bytes.length > maxBytes)
            attachment = attachment.failed("The file exceeds the upload limit of "
                    + this.vectorStoreDocumentService.getMaxUploadSize() + ".");
        put(conversationId, attachment);
        if (attachment.status() == Status.EXTRACTING) {
            AtomicBoolean cancelled = new AtomicBoolean();
            this.cancelFlags.put(attachId, cancelled);
            byte[] content = bytes;
            this.intakeExecutor.submit(() -> runPipeline(conversationId, attachId, content, cancelled));
        }
        return attachment;
    }

    public void remove(String conversationId, String attachId) {
        AtomicBoolean cancelled = this.cancelFlags.get(attachId);
        if (cancelled != null) cancelled.set(true);
        ChatDocumentAttachment attachment = find(conversationId, attachId);
        if (attachment == null) return;
        if (attachment.docInfoId() != null && !attachment.promoted()) deleteIndexed(attachment.docInfoId());
        else if (attachment.docInfoId() == null && attachment.storedFileName() != null)
            deleteUploadQuietly(attachment.storedFileName());
        synchronized (this) {
            List<ChatDocumentAttachment> remaining = list(conversationId).stream()
                    .filter(item -> !item.attachId().equals(attachId)).toList();
            this.attachmentsByConversation.put(conversationId, remaining);
            persist(conversationId, remaining);
        }
    }

    public ChatDocumentAttachment promote(String conversationId, String attachId) {
        ChatDocumentAttachment attachment = find(conversationId, attachId);
        if (attachment == null || !attachment.promotable()) return attachment;
        this.vectorStoreDocumentService.getDocumentList().stream()
                .filter(info -> info.docInfoId().equals(attachment.docInfoId())).findFirst()
                .ifPresent(this.vectorStoreDocumentService::promoteToKnowledgeBase);
        return update(conversationId, attachId, ChatDocumentAttachment::promotedCopy);
    }

    public void removeConversation(String conversationId) {
        List<ChatDocumentAttachment> attachments = list(conversationId);
        for (ChatDocumentAttachment attachment : attachments) {
            AtomicBoolean cancelled = this.cancelFlags.get(attachment.attachId());
            if (cancelled != null) cancelled.set(true);
            if (attachment.docInfoId() != null && !attachment.promoted()) deleteIndexed(attachment.docInfoId());
            else if (attachment.docInfoId() == null && attachment.storedFileName() != null)
                deleteUploadQuietly(attachment.storedFileName());
        }
        synchronized (this) {
            this.attachmentsByConversation.remove(conversationId);
            this.listeners.remove(conversationId);
            persist(conversationId, List.of());
        }
    }

    public void addListener(String conversationId, Consumer<ChatDocumentAttachment> listener) {
        this.listeners.computeIfAbsent(conversationId, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void removeListener(String conversationId, Consumer<ChatDocumentAttachment> listener) {
        List<Consumer<ChatDocumentAttachment>> registered = this.listeners.get(conversationId);
        if (registered != null) registered.remove(listener);
    }

    @PreDestroy
    public void shutdown() {
        this.intakeExecutor.shutdownNow();
    }

    private void runPipeline(String conversationId, String attachId, byte[] bytes, AtomicBoolean cancelled) {
        ChatDocumentAttachment attachment = find(conversationId, attachId);
        if (attachment == null || cancelled.get()) return;
        String fileName = attachment.fileName();
        String storedFileName = attachId + "-" + fileName;
        Path uploadPath = this.vectorStoreDocumentService.buildUploadFilePath(storedFileName);
        try {
            Files.write(uploadPath, bytes);
            update(conversationId, attachId, current -> current.stored(storedFileName));
            List<Document> rawDocuments = new TikaDocumentReader(new FileSystemResource(uploadPath.toFile())).read();
            String text = rawDocuments.stream().map(Document::getText).filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n\n"));
            if (!StringUtils.hasText(text)) {
                Files.deleteIfExists(uploadPath);
                update(conversationId, attachId, current -> current.failed(
                        "No extractable text. The file may be a scanned or image-only document."));
                return;
            }
            int tokenCount = this.tokenCountEstimator.estimate(text);
            if (tokenCount <= SMALL_MAX_TOKENS) {
                Files.deleteIfExists(uploadPath);
                update(conversationId, attachId, current -> current.readyInline(text, tokenCount));
                return;
            }
            Grade grade = tokenCount > LARGE_MIN_TOKENS ? Grade.LARGE : Grade.MEDIUM;
            update(conversationId, attachId, current -> current.graded(grade, tokenCount, Status.INDEXING));
            if (cancelled.get()) {
                Files.deleteIfExists(uploadPath);
                return;
            }
            List<Document> chunks = this.vectorStoreDocumentService.getDefaultTokenTextSplitter().apply(rawDocuments);
            chunks.forEach(chunk -> {
                chunk.getMetadata().put("source", fileName);
                chunk.getMetadata().put(HierarchicalSummaryTransformer.LEVEL, 1);
            });
            VectorStoreDocumentInfo documentInfo =
                    this.vectorStoreDocumentService.putNewDocument(storedFileName, chunks, true);
            documentInfo = this.vectorStoreDocumentService.updateDocumentInfo(documentInfo, fileName);
            this.vectorStoreService.add(documentInfo);
            if (cancelled.get()) {
                deleteIndexed(documentInfo.docInfoId());
                return;
            }
            String docInfoId = documentInfo.docInfoId();
            int plannedCalls = this.summaryTransformer.plannedCalls(chunks.size());
            update(conversationId, attachId, current -> current.indexed(docInfoId, chunks.size(), Status.SUMMARIZING,
                    "0/" + plannedCalls));
            String summary = this.summaryTransformer.summarize(fileName, chunks, cancelled::get,
                    done -> update(conversationId, attachId,
                            current -> current.statusOf(Status.SUMMARIZING, done + "/" + plannedCalls)));
            if (cancelled.get()) {
                deleteIndexed(docInfoId);
                return;
            }
            update(conversationId, attachId, current -> current.readyIndexed(docInfoId, summary, chunks.size()));
        } catch (Exception e) {
            logger.error("Document intake failed for {} [conversationId={}]", fileName, conversationId, e);
            deleteUploadQuietly(storedFileName);
            update(conversationId, attachId, current -> current.failed(Objects.toString(e.getMessage(),
                    e.getClass().getSimpleName())));
        } finally {
            this.cancelFlags.remove(attachId);
        }
    }

    private void deleteIndexed(String docInfoId) {
        this.vectorStoreDocumentService.getDocumentList().stream()
                .filter(info -> info.docInfoId().equals(docInfoId)).findFirst()
                .ifPresent(info -> {
                    List<String> chunkIds = info.documentListSupplier().get().stream().map(Document::getId).toList();
                    if (!chunkIds.isEmpty()) this.vectorStoreService.delete(chunkIds);
                    this.vectorStoreDocumentService.deleteDocumentInfo(info);
                });
    }

    private void deleteUploadQuietly(String storedFileName) {
        if (storedFileName == null) return;
        try {
            Files.deleteIfExists(this.vectorStoreDocumentService.buildUploadFilePath(storedFileName));
        } catch (IOException e) {
            logger.warn("Failed to delete uploaded attachment file {}", storedFileName, e);
        }
    }

    private ChatDocumentAttachment find(String conversationId, String attachId) {
        return list(conversationId).stream().filter(item -> item.attachId().equals(attachId)).findFirst()
                .orElse(null);
    }

    private void put(String conversationId, ChatDocumentAttachment attachment) {
        synchronized (this) {
            List<ChatDocumentAttachment> updated = new ArrayList<>(list(conversationId));
            updated.add(attachment);
            this.attachmentsByConversation.put(conversationId, List.copyOf(updated));
            persist(conversationId, updated);
        }
        notifyListeners(conversationId, attachment);
    }

    private ChatDocumentAttachment update(String conversationId, String attachId,
            UnaryOperator<ChatDocumentAttachment> change) {
        ChatDocumentAttachment changed;
        synchronized (this) {
            List<ChatDocumentAttachment> current = list(conversationId);
            Map<String, ChatDocumentAttachment> byId = current.stream()
                    .collect(Collectors.toMap(ChatDocumentAttachment::attachId, item -> item, (a, b) -> a,
                            HashMap::new));
            ChatDocumentAttachment existing = byId.get(attachId);
            if (existing == null) return null;
            changed = change.apply(existing);
            List<ChatDocumentAttachment> updated = current.stream()
                    .map(item -> item.attachId().equals(attachId) ? changed : item).toList();
            this.attachmentsByConversation.put(conversationId, updated);
            persist(conversationId, updated);
        }
        notifyListeners(conversationId, changed);
        return changed;
    }

    private void notifyListeners(String conversationId, ChatDocumentAttachment attachment) {
        for (Consumer<ChatDocumentAttachment> listener :
                this.listeners.getOrDefault(conversationId, List.of())) {
            try {
                listener.accept(attachment);
            } catch (Exception e) {
                logger.warn("Attachment listener failed [conversationId={}]", conversationId, e);
            }
        }
    }

    private List<ChatDocumentAttachment> loadFromDisk(String conversationId) {
        Path file = indexFile(conversationId);
        if (!Files.exists(file)) return List.of();
        try {
            List<ChatDocumentAttachment> loaded = MAPPER.readValue(file.toFile(), LIST_TYPE);
            List<ChatDocumentAttachment> repaired = loaded.stream()
                    .map(item -> item.processing() ? item.failed("Interrupted by application restart.") : item)
                    .toList();
            if (!repaired.equals(loaded)) persist(conversationId, repaired);
            return repaired;
        } catch (IOException e) {
            logger.error("Failed to load attachment index [conversationId={}]", conversationId, e);
            return List.of();
        }
    }

    private void persist(String conversationId, List<ChatDocumentAttachment> attachments) {
        try {
            if (attachments.isEmpty()) Files.deleteIfExists(indexFile(conversationId));
            else MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexFile(conversationId).toFile(), attachments);
        } catch (IOException e) {
            logger.error("Failed to persist attachment index [conversationId={}]", conversationId, e);
        }
    }

    private Path indexFile(String conversationId) {
        return this.attachmentsDir.resolve(conversationId + ".json");
    }
}
