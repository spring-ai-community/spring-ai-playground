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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springaicommunity.playground.service.tool.runtime.JsHelperException;
import org.springaicommunity.playground.service.tool.runtime.SafeFs;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.EnrichOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ExtractOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.IngestRequest;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ReaderType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

@Service
public class KnowledgeIngestService {

    public static final String INGEST_ROOTS_PROPERTY = "spring.ai.playground.rag.ingest-roots";
    public static final String DEFAULT_INBOX = "inbox";

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIngestService.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PERSISTENCE_FLUSH_TIMEOUT = Duration.ofSeconds(10);

    public record IngestProgress(BiConsumer<Integer, Integer> progress, BooleanSupplier cancelled) {

        public static final IngestProgress NONE = new IngestProgress(null, () -> false);

        public IngestProgress {
            cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    public record Prepared(DocumentSpec spec, String fileName, Map<String, List<Document>> chunksByFile,
                           String fingerprint) {

        public List<Document> chunks() {
            return this.chunksByFile.values().stream().flatMap(List::stream).toList();
        }

        public String title() {
            return this.spec.resolvedTitle(this.fileName);
        }
    }

    record ResolvedSource(String fileName, byte[] bytes, ReaderType recommendedReader, Path stagedPath) {}

    private final OfflineEtlPipelineService etl;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PersistenceExecutor persistenceExecutor;
    private final List<Path> ingestRoots;
    private final HttpClient httpClient;

    @Autowired
    public KnowledgeIngestService(Path springAiPlaygroundHomeDir, OfflineEtlPipelineService etl,
            ObjectProvider<ChatModel> chatModelProvider, PersistenceExecutor persistenceExecutor,
            SpringAiPlaygroundOptions playgroundOptions) throws IOException {
        this(springAiPlaygroundHomeDir, etl, chatModelProvider, persistenceExecutor,
                playgroundOptions.rag().ingestRoots());
    }

    public KnowledgeIngestService(Path springAiPlaygroundHomeDir, OfflineEtlPipelineService etl,
            ObjectProvider<ChatModel> chatModelProvider, PersistenceExecutor persistenceExecutor,
            List<String> configuredRoots) throws IOException {
        this.etl = etl;
        this.chatModelProvider = chatModelProvider;
        this.persistenceExecutor = persistenceExecutor;
        this.ingestRoots = resolveRoots(springAiPlaygroundHomeDir, configuredRoots);
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    private static List<Path> resolveRoots(Path homeDir, List<String> configured) throws IOException {
        if (configured == null || configured.isEmpty()) {
            Path inbox = homeDir.resolve("vectorstore").resolve(DEFAULT_INBOX);
            Files.createDirectories(inbox);
            return List.of(inbox.toAbsolutePath().normalize());
        }
        List<Path> roots = new ArrayList<>();
        for (String root : configured) {
            if (root == null || root.isBlank()) continue;
            roots.add(expandHome(root.trim()).toAbsolutePath().normalize());
        }
        if (roots.isEmpty()) return resolveRoots(homeDir, null);
        return List.copyOf(roots);
    }

    static Path expandHome(String rawPath) {
        if (rawPath.equals("~") || rawPath.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(rawPath.length() == 1 ? "" : rawPath.substring(2));
        }
        return Path.of(rawPath);
    }

    public List<Path> ingestRoots() {
        return this.ingestRoots;
    }

    public VectorStoreDocumentInfo ingest(DocumentSpec spec, IngestProgress progress) {
        return commit(prepare(spec, progress));
    }

    public Prepared prepare(DocumentSpec spec, IngestProgress progress) {
        requireChatModelForEnrichment(spec);
        return prepare(spec, progress, resolve(spec.source()));
    }

    Prepared prepare(DocumentSpec spec, IngestProgress progress, ResolvedSource source) {
        EnrichOptions enrich = spec.enrich().toOptions();
        requireChatModelForEnrichment(spec);
        stage(source);
        ExtractOptions extract = spec.extract().toOptions(source.recommendedReader());
        Map<String, List<Document>> chunks = this.etl.extractAndTransform(List.of(source.fileName()), extract,
                this.etl.newTokenTextSplitter(spec.split().toInfo()));
        IngestProgress effectiveProgress = progress == null ? IngestProgress.NONE : progress;
        if (enrich.llmCallsPerChunk() > 0) {
            chunks = this.etl.enrichByFile(chunks, enrich, effectiveProgress.progress(),
                    effectiveProgress.cancelled());
        }
        return new Prepared(spec, source.fileName(), chunks, fingerprint(source.bytes(), spec));
    }

    private void requireChatModelForEnrichment(DocumentSpec spec) {
        if (spec.enrich().toOptions().llmCallsPerChunk() > 0 && this.chatModelProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "enrichment needs a chat model; configure one or turn keyword and summary off");
        }
    }

    public String declaredTitle(DocumentSpec spec) {
        DocumentSpec.Source source = spec.source();
        if (source == null) throw new IllegalArgumentException("source is required");
        return switch (source.kind()) {
            case PATH -> spec.resolvedTitle(expandHome(source.path().trim()).getFileName().toString());
            case URL -> spec.resolvedTitle(urlFileName(URI.create(source.url().trim())));
            case TEXT -> {
                if (spec.title() == null || spec.title().isBlank()) {
                    throw new IllegalArgumentException("title is required for an inline text source");
                }
                yield spec.title().trim();
            }
        };
    }

    public VectorStoreDocumentInfo commit(Prepared prepared) {
        return commit(prepared, prepared.chunksByFile());
    }

    public VectorStoreDocumentInfo commit(Prepared prepared, Map<String, List<Document>> selectedChunks) {
        List<VectorStoreDocumentInfo> documents = this.etl.ingest(new IngestRequest(List.of(prepared.fileName()),
                selectedChunks, prepared.spec().resolvedTitle(prepared.fileName()),
                prepared.spec().resolvedDescription(), prepared.fingerprint()));
        if (documents.isEmpty()) {
            throw new IllegalStateException("no chunks to embed for '" + prepared.title() + "'");
        }
        logger.info("Ingested knowledge document '{}' from {} ({} chunks)", prepared.title(),
                prepared.spec().source().describe(), documents.getFirst().documentListSupplier().get().size());
        return documents.getFirst();
    }

    public String fingerprint(DocumentSpec spec) {
        return fingerprint(resolve(spec.source()).bytes(), spec);
    }

    public static String fingerprint(byte[] sourceBytes, DocumentSpec spec) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceBytes);
            digest.update((byte) '\n');
            ReaderType reader = spec.extract().reader();
            String options = String.join("|",
                    String.valueOf(reader == null ? "" : reader.name()),
                    String.valueOf(spec.extract().toOptions(reader == null ? ReaderType.TIKA : reader)),
                    String.valueOf(spec.split().toInfo()), String.valueOf(spec.enrich().toOptions()));
            digest.update(options.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<VectorStoreDocumentInfo> find(String titleOrId) {
        if (titleOrId == null || titleOrId.isBlank()) return Optional.empty();
        String wanted = titleOrId.trim();
        List<VectorStoreDocumentInfo> documents = this.etl.getVisibleDocumentList();
        return documents.stream().filter(info -> wanted.equals(info.docInfoId())).findFirst()
                .or(() -> documents.stream().filter(info -> wanted.equals(info.title())).findFirst());
    }

    public VectorStoreDocumentInfo delete(String titleOrId) {
        VectorStoreDocumentInfo info = find(titleOrId)
                .orElseThrow(() -> new IllegalArgumentException("unknown document '" + titleOrId + "'"));
        VectorStoreDocumentInfo deleted = this.etl.deleteDocument(info.docInfoId());
        awaitPersistence();
        return deleted;
    }

    private void awaitPersistence() {
        try {
            this.persistenceExecutor.awaitCompletion(PERSISTENCE_FLUSH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            logger.warn("Persistence did not settle within {} after deleting a document", PERSISTENCE_FLUSH_TIMEOUT);
        }
    }

    ResolvedSource resolve(DocumentSpec.Source source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        return switch (source.kind()) {
            case PATH -> resolvePath(source.path().trim());
            case URL -> resolveUrl(source.url().trim());
            case TEXT -> new ResolvedSource(textFileName(), source.text().getBytes(StandardCharsets.UTF_8),
                    ReaderType.TEXT, null);
        };
    }

    private ResolvedSource resolvePath(String rawPath) {
        Path path = expandHome(rawPath).toAbsolutePath().normalize();
        Path uploadDir = this.etl.getUploadDir().toAbsolutePath().normalize();
        if (path.startsWith(uploadDir) && Files.isRegularFile(path)) {
            return new ResolvedSource(this.etl.decodeFileName(path.getFileName().toString()), readAll(path),
                    ReaderType.recommendFor(path.getFileName().toString()), path);
        }
        SafeFs.FsScope scope = new SafeFs.FsScope(this.ingestRoots.getFirst(), this.ingestRoots);
        try {
            byte[] bytes = SafeFs.readBytes(scope, path.toString());
            String fileName = path.getFileName().toString();
            return new ResolvedSource(fileName, bytes, ReaderType.recommendFor(fileName), null);
        } catch (JsHelperException e) {
            throw new IllegalArgumentException("'" + rawPath + "' is outside the ingest roots " + this.ingestRoots
                    + "; put the file there or extend " + INGEST_ROOTS_PROPERTY, e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read '" + rawPath + "': " + e.getMessage(), e);
        }
    }

    private ResolvedSource resolveUrl(String rawUrl) {
        URI uri = URI.create(rawUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("url must use http or https: " + rawUrl);
        }
        HttpResponse<byte[]> response;
        try {
            response = this.httpClient.send(HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("download failed for " + rawUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrupted for " + rawUrl, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("download failed for " + rawUrl + ": HTTP " + response.statusCode());
        }
        long limit = this.etl.getMaxUploadSize().toBytes();
        if (response.body().length > limit) {
            throw new IllegalArgumentException("download of " + rawUrl + " exceeds the upload limit of "
                    + this.etl.getMaxUploadSize());
        }
        String fileName = urlFileName(uri);
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        return new ResolvedSource(fileName, response.body(), readerFor(fileName, contentType), null);
    }

    private static ReaderType readerFor(String fileName, String contentType) {
        ReaderType byName = ReaderType.recommendFor(fileName);
        if (byName != ReaderType.TIKA || fileName.contains(".")) return byName;
        if (contentType.contains("html")) return ReaderType.HTML;
        if (contentType.contains("json")) return ReaderType.JSON;
        if (contentType.contains("markdown")) return ReaderType.MARKDOWN;
        if (contentType.contains("text/plain")) return ReaderType.TEXT;
        if (contentType.contains("pdf")) return ReaderType.PDF_PAGE;
        return ReaderType.TIKA;
    }

    private static String urlFileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        if (last.isBlank()) last = uri.getHost() == null ? "download" : uri.getHost();
        return last;
    }

    private static String textFileName() {
        return "inline-" + Long.toHexString(System.currentTimeMillis()) + ".txt";
    }

    private void stage(ResolvedSource source) {
        Path target = this.etl.buildUploadFilePath(source.fileName());
        if (source.stagedPath() != null && source.stagedPath().equals(target.toAbsolutePath().normalize())) return;
        Optional<VectorStoreDocumentInfo> indexed = this.etl.getDocumentList().stream()
                .filter(info -> source.fileName().equals(info.getDocumentFileName())).findFirst();
        if (indexed.isPresent()) {
            throw new IllegalStateException("'" + source.fileName() + "' is already indexed as '"
                    + indexed.get().title() + "'; delete that document before ingesting it again");
        }
        try {
            Files.write(target, source.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage '" + source.fileName() + "': " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read '" + path + "': " + e.getMessage(), e);
        }
    }

}
