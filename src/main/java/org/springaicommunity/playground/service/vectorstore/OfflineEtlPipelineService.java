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
package org.springaicommunity.playground.service.vectorstore;

import com.knuddels.jtokkit.api.EncodingType;
import org.springaicommunity.playground.service.SharedDataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class OfflineEtlPipelineService implements SharedDataReader<List<VectorStoreDocumentInfo>> {

    private static final Logger logger = LoggerFactory.getLogger(OfflineEtlPipelineService.class);

    private static final Pattern REASONING_TAGS = Pattern.compile("(?is)<think>.*?</think>|</?think\\s*>");

    private static final List<String> ENRICHED_METADATA_KEYS = List.of(
            KeywordMetadataEnricher.EXCERPT_KEYWORDS_METADATA_KEY, "section_summary", "prev_section_summary",
            "next_section_summary");

    @Override
    public List<VectorStoreDocumentInfo> read() {
        return getDocumentList();
    }

    public record TokenTextSplitInfo(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
                                     int maxNumChunks, boolean keepSeparator, String encodingType) {

        public TokenTextSplitInfo(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
                int maxNumChunks, boolean keepSeparator) {
            this(chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator,
                    DEFAULT_ENCODING_TYPE);
        }
    }

    public static final String DEFAULT_ENCODING_TYPE = EncodingType.CL100K_BASE.name();

    public static List<String> encodingTypeNames() {
        return Arrays.stream(EncodingType.values()).map(Enum::name).toList();
    }

    public final static TokenTextSplitInfo DEFAULT_TOKEN_TEXT_SPLIT_INFO =
            new TokenTextSplitInfo(800, 350, 5, 10000, true);

    public enum ReaderType {
        TIKA, TEXT, JSON, MARKDOWN, HTML, PDF_PAGE, PDF_PARAGRAPH;

        public static ReaderType recommendFor(String fileName) {
            String ext = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            int dot = ext.lastIndexOf('.');
            ext = dot < 0 ? "" : ext.substring(dot + 1);
            return switch (ext) {
                case "md", "markdown" -> MARKDOWN;
                case "html", "htm" -> HTML;
                case "json" -> JSON;
                case "txt", "text" -> TEXT;
                case "pdf" -> PDF_PAGE;
                default -> TIKA;
            };
        }
    }

    public record ExtractOptions(ReaderType readerType, String charset, String jsonKeys, String htmlSelector,
                                 boolean markdownIncludeCodeBlock, boolean markdownIncludeBlockquote,
                                 boolean markdownHorizontalRuleCreateDocument, int pdfPagesPerDocument) {

        public static ExtractOptions defaults(ReaderType readerType) {
            return new ExtractOptions(readerType, "UTF-8", "", "body", true, true, false, 1);
        }
    }

    public record EnrichOptions(boolean keywordEnabled, int keywordCount, boolean summaryEnabled,
                                boolean summaryPrevious, boolean summaryNext) {

        public static final EnrichOptions NONE = new EnrichOptions(false, 5, false, false, false);

        public int llmCallsPerChunk() {
            return (keywordEnabled ? 1 : 0) + (summaryEnabled ? 1 : 0);
        }
    }

    private final ResourceLoader resourceLoader;

    private final Path uploadDir;

    private final DataSize maxUploadSize;

    private final Map<String, TokenTextSplitter> splitters;
    private final TokenTextSplitter defaultTokenTextSplitter;
    private final ObjectProvider<VectorStoreDocumentPersistenceService> vectorStoreDocumentPersistenceServiceProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Map<String, VectorStoreDocumentInfo> documentInfos;
    private final ThreadLocal<Boolean> skipPersist = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public OfflineEtlPipelineService(Path springAiPlaygroundHomeDir,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxUploadSize, ResourceLoader resourceLoader,
            ObjectProvider<VectorStoreDocumentPersistenceService> vectorStoreDocumentPersistenceServiceProvider,
            ObjectProvider<ChatModel> chatModelProvider)
            throws IOException {
        this.uploadDir = springAiPlaygroundHomeDir.resolve("vectorstore").resolve("docs");
        this.resourceLoader = resourceLoader;
        this.vectorStoreDocumentPersistenceServiceProvider = vectorStoreDocumentPersistenceServiceProvider;
        this.chatModelProvider = chatModelProvider;
        Files.createDirectories(uploadDir);
        this.maxUploadSize = maxUploadSize;
        this.splitters = new WeakHashMap<>();
        this.defaultTokenTextSplitter = newTokenTextSplitter(DEFAULT_TOKEN_TEXT_SPLIT_INFO);
        this.documentInfos = new ConcurrentHashMap<>();
    }

    public TokenTextSplitter getDefaultTokenTextSplitter() {
        return this.defaultTokenTextSplitter;
    }

    public void loadAll(Runnable loadAction) {
        this.skipPersist.set(Boolean.TRUE);
        try {
            loadAction.run();
        } finally {
            this.skipPersist.remove();
        }
    }

    public VectorStoreDocumentInfo loadDocument(String documentFileName, List<Document> uploadedDocumentItems) {
        return loadDocument(documentFileName, null, null, uploadedDocumentItems);
    }

    public VectorStoreDocumentInfo loadDocument(String documentFileName, String description,
            List<Document> uploadedDocumentItems) {
        return loadDocument(documentFileName, null, description, uploadedDocumentItems);
    }

    public VectorStoreDocumentInfo loadDocument(String documentFileName, List<Document> uploadedDocumentItems,
            boolean chatOrigin) {
        return loadDocument(documentFileName, null, null, chatOrigin, uploadedDocumentItems);
    }

    public VectorStoreDocumentInfo loadDocument(String documentFileName, String title, String description,
            List<Document> uploadedDocumentItems) {
        return loadDocument(documentFileName, title, description, false, uploadedDocumentItems);
    }

    public VectorStoreDocumentInfo loadDocument(String documentFileName, String title, String description,
            boolean chatOrigin, List<Document> uploadedDocumentItems) {
        long createTimestamp = System.currentTimeMillis();
        File uploadedDocumentFile = buildUploadFilePath(documentFileName).toFile();
        String docInfoId = VectorStoreService.DOC_INFO_ID + "-" + UUID.randomUUID();
        List<Document> documentList = IntStream.range(0, uploadedDocumentItems.size()).boxed()
                .map(index -> copyNewDocument(docInfoId, index, uploadedDocumentItems.get(index))).toList();
        String resolvedTitle = (title == null || title.isBlank()) ? documentFileName : title.trim();
        VectorStoreDocumentInfo vectorStoreDocumentInfo =
                new VectorStoreDocumentInfo(docInfoId, resolvedTitle, description, createTimestamp,
                        createTimestamp, documentFileName, uploadedDocumentFile.getPath(), chatOrigin,
                        () -> documentList);
        this.documentInfos.put(docInfoId, vectorStoreDocumentInfo);
        if (!Boolean.TRUE.equals(this.skipPersist.get()))
            this.vectorStoreDocumentPersistenceServiceProvider.getObject().saveAsync(vectorStoreDocumentInfo);
        return vectorStoreDocumentInfo;
    }

    public VectorStoreDocumentInfo promoteToKnowledgeBase(VectorStoreDocumentInfo vectorStoreDocumentInfo) {
        VectorStoreDocumentInfo promoted = vectorStoreDocumentInfo.promoted();
        this.documentInfos.put(promoted.docInfoId(), promoted);
        if (!Boolean.TRUE.equals(this.skipPersist.get()))
            this.vectorStoreDocumentPersistenceServiceProvider.getObject().saveAsync(promoted);
        return promoted;
    }

    public Path buildUploadFilePath(String fileName) {
        return this.uploadDir.resolve(encodeFileName(fileName));
    }

    private Document copyNewDocument(String docInfoId, Integer index, Document uploadedDocument) {
        Map<String, Object> metadata = new HashMap<>(uploadedDocument.getMetadata());
        metadata.put(VectorStoreService.DOC_INFO_ID, docInfoId);
        return new Document(index + "-" + docInfoId, uploadedDocument.getText(), metadata);
    }

    public Map<String, List<Document>> extractAndTransform(List<String> uploadedFileNames, TextSplitter textSplitter) {
        return extractAndTransform(uploadedFileNames, null, textSplitter);
    }

    public Map<String, List<Document>> extractAndTransform(List<String> uploadedFileNames,
            ExtractOptions extractOptions, TextSplitter textSplitter) {
        return uploadedFileNames.stream().map(fileName -> {
                    Resource resource = resolveResource(buildUploadFilePath(fileName).toFile().getPath());
                    DocumentReader reader = newDocumentReader(resource, extractOptions);
                    return Map.entry(fileName, split(textSplitter, reader));
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    DocumentReader newDocumentReader(Resource resource, ExtractOptions options) {
        if (options == null || options.readerType() == null || options.readerType() == ReaderType.TIKA)
            return new TikaDocumentReader(resource);
        return switch (options.readerType()) {
            case TEXT -> {
                TextReader textReader = new TextReader(resource);
                if (options.charset() != null && !options.charset().isBlank())
                    textReader.setCharset(Charset.forName(options.charset().trim()));
                yield textReader;
            }
            case JSON -> {
                String keys = options.jsonKeys() == null ? "" : options.jsonKeys().trim();
                yield keys.isEmpty() ? new JsonReader(resource)
                        : new JsonReader(resource, Arrays.stream(keys.split(","))
                                .map(String::trim).filter(key -> !key.isEmpty()).toArray(String[]::new));
            }
            case MARKDOWN -> new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.builder()
                    .withIncludeCodeBlock(options.markdownIncludeCodeBlock())
                    .withIncludeBlockquote(options.markdownIncludeBlockquote())
                    .withHorizontalRuleCreateDocument(options.markdownHorizontalRuleCreateDocument())
                    .build());
            case HTML -> {
                JsoupDocumentReaderConfig.Builder builder = JsoupDocumentReaderConfig.builder();
                if (options.charset() != null && !options.charset().isBlank())
                    builder.charset(options.charset().trim());
                if (options.htmlSelector() != null && !options.htmlSelector().isBlank())
                    builder.selector(options.htmlSelector().trim());
                yield new JsoupDocumentReader(resource, builder.build());
            }
            case PDF_PAGE -> new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(Math.max(1, options.pdfPagesPerDocument())).build());
            case PDF_PARAGRAPH -> new ParagraphPdfDocumentReader(resource, PdfDocumentReaderConfig.builder().build());
            case TIKA -> new TikaDocumentReader(resource);
        };
    }

    public List<Document> enrich(List<Document> chunks, EnrichOptions options,
            BiConsumer<Integer, Integer> progress, BooleanSupplier cancelled) {
        if (options == null || (!options.keywordEnabled() && !options.summaryEnabled())) return chunks;
        ChatModel chatModel = this.chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            logger.warn("Enrichment skipped: no ChatModel bean available");
            return chunks;
        }
        KeywordMetadataEnricher keywordEnricher = options.keywordEnabled()
                ? KeywordMetadataEnricher.builder(chatModel).keywordCount(Math.max(1, options.keywordCount())).build()
                : null;
        SummaryMetadataEnricher summaryEnricher = options.summaryEnabled()
                ? new SummaryMetadataEnricher(chatModel, summaryTypes(options)) : null;

        List<Document> result = new ArrayList<>(chunks);
        if (summaryEnricher != null) {
            if (cancelled != null && cancelled.getAsBoolean()) return result;
            result = new ArrayList<>(summaryEnricher.apply(result));
        }
        if (keywordEnricher != null) {
            for (int i = 0; i < result.size(); i++) {
                if (cancelled != null && cancelled.getAsBoolean()) break;
                List<Document> enriched = keywordEnricher.apply(List.of(result.get(i)));
                result.set(i, enriched.get(0));
                if (progress != null) progress.accept(i + 1, result.size());
            }
        }
        result.forEach(OfflineEtlPipelineService::stripReasoning);
        return result;
    }

    static void stripReasoning(Document document) {
        ENRICHED_METADATA_KEYS.forEach(key -> {
            if (document.getMetadata().get(key) instanceof String value) {
                String cleaned = REASONING_TAGS.matcher(value).replaceAll("").strip();
                if (!cleaned.equals(value)) document.getMetadata().put(key, cleaned);
            }
        });
    }

    private static List<SummaryMetadataEnricher.SummaryType> summaryTypes(EnrichOptions options) {
        List<SummaryMetadataEnricher.SummaryType> types = new ArrayList<>();
        if (options.summaryPrevious()) types.add(SummaryMetadataEnricher.SummaryType.PREVIOUS);
        types.add(SummaryMetadataEnricher.SummaryType.CURRENT);
        if (options.summaryNext()) types.add(SummaryMetadataEnricher.SummaryType.NEXT);
        return types;
    }

    private Resource resolveResource(String path) {
        if (path.startsWith("classpath:") || path.startsWith("file:")) {
            return resourceLoader.getResource(path);
        }
        return resourceLoader.getResource("file:" + path);
    }

    private List<Document> split(Resource resource, TextSplitter textSplitter) {
        return split(textSplitter, new TikaDocumentReader(resource));
    }

    private List<Document> split(TextSplitter textSplitter, DocumentReader documentReader) {
        List<Document> documentList = textSplitter.split(documentReader.read());
        documentList.forEach(document -> document.getMetadata().computeIfPresent("source",
                (key, value) -> decodeFileName(value.toString())));
        return documentList;
    }

    public List<Document> split(Resource resource, TokenTextSplitInfo tokenTextSplitInfo) {
        return split(this.splitters.computeIfAbsent(tokenTextSplitInfo.toString(),
                key -> newTokenTextSplitter(tokenTextSplitInfo)), new TikaDocumentReader(resource));
    }

    public TokenTextSplitter newTokenTextSplitter(TokenTextSplitInfo tokenTextSplitInfo) {
        return TokenTextSplitter.builder().withChunkSize(tokenTextSplitInfo.chunkSize())
                .withMinChunkSizeChars(tokenTextSplitInfo.minChunkSizeChars())
                .withMinChunkLengthToEmbed(tokenTextSplitInfo.minChunkLengthToEmbed()).withMaxNumChunks(
                        tokenTextSplitInfo.maxNumChunks()).withKeepSeparator(
                        tokenTextSplitInfo.keepSeparator())
                .withEncodingType(parseEncodingType(tokenTextSplitInfo.encodingType())).build();
    }

    private static EncodingType parseEncodingType(String name) {
        if (name == null || name.isBlank()) return EncodingType.CL100K_BASE;
        try {
            return EncodingType.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return EncodingType.CL100K_BASE;
        }
    }

    public void stageUpload(String fileName, File uploadedFile) throws IOException {
        File file = buildUploadFilePath(fileName).toFile();
        if (file.exists() && isIndexed(fileName))
            throw new FileAlreadyExistsException("Already Exists - " + fileName);
        Files.copy(uploadedFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean isIndexed(String fileName) {
        return this.documentInfos.values().stream()
                .anyMatch(documentInfo -> fileName.equals(documentInfo.getDocumentFileName()));
    }

    private String[] splitNameAndExt(String fileName) {
        int dotIdx = fileName.lastIndexOf(".");
        return dotIdx != -1 ? new String[]{fileName.substring(0, dotIdx), fileName.substring(dotIdx)} : new String[]{
                fileName, ""};
    }

    String encodeFileName(String fileName) {
        String[] parts = splitNameAndExt(fileName);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(parts[0].getBytes()) +
                parts[1];
    }

    String decodeFileName(String encodedFileName) {
        String[] parts = splitNameAndExt(encodedFileName);
        return new String(Base64.getUrlDecoder().decode(parts[0])) + parts[1];
    }


    public void removeUploadedDocumentFile(String fileName) throws IOException {
        Files.deleteIfExists(buildUploadFilePath(fileName));
    }

    public DataSize getMaxUploadSize() {
        return this.maxUploadSize;
    }

    public VectorStoreDocumentInfo updateDocumentInfo(VectorStoreDocumentInfo vectorStoreDocumentInfo, String title) {
        return updateDocumentInfo(vectorStoreDocumentInfo, title, vectorStoreDocumentInfo.description());
    }

    public VectorStoreDocumentInfo updateDocumentInfo(VectorStoreDocumentInfo vectorStoreDocumentInfo, String title,
            String description) {
        logger.info("Updating document info: {} ({})", title, description);
        VectorStoreDocumentInfo updateVectorStoreDocumentInfo =
                vectorStoreDocumentInfo.newTitleAndDescription(title, description);
        this.documentInfos.put(vectorStoreDocumentInfo.docInfoId(), updateVectorStoreDocumentInfo);
        if (!Boolean.TRUE.equals(this.skipPersist.get()))
            this.vectorStoreDocumentPersistenceServiceProvider.getObject().saveAsync(updateVectorStoreDocumentInfo);
        return updateVectorStoreDocumentInfo;
    }

    public void deleteDocumentInfo(VectorStoreDocumentInfo vectorStoreDocumentInfo) {
        this.documentInfos.remove(vectorStoreDocumentInfo.docInfoId());
        this.vectorStoreDocumentPersistenceServiceProvider.getObject().deleteAsync(vectorStoreDocumentInfo);
    }

    public List<VectorStoreDocumentInfo> getDocumentList() {
        return this.documentInfos.values().stream()
                .sorted(Comparator.comparingLong(VectorStoreDocumentInfo::updateTimestamp).reversed()).toList();
    }

    public List<VectorStoreDocumentInfo> getVisibleDocumentList() {
        return getDocumentList().stream().filter(info -> !info.chatOrigin()).toList();
    }

    public List<String> getChatOriginDocInfoIds() {
        return getDocumentList().stream().filter(VectorStoreDocumentInfo::chatOrigin)
                .map(VectorStoreDocumentInfo::docInfoId).toList();
    }

    public Path getUploadDir() {
        return this.uploadDir;
    }

}
