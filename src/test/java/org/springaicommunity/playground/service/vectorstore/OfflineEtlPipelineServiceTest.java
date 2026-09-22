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

import org.springaicommunity.playground.webui.vectorstore.VectorStoreView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class OfflineEtlPipelineServiceTest {
    @Autowired
    private OfflineEtlPipelineService service;

    @Test
    void testAddAndRemoveFile() throws Exception {
        File tempFile = File.createTempFile("test", ".txt");
        Files.write(tempFile.toPath(), "Test content".getBytes());
        String fileName = "upload-test.txt";
        Path uploadedPath = service.getUploadDir().resolve(fileName);
        Files.deleteIfExists(uploadedPath);

        try {
            service.stageUpload(fileName, tempFile);
        } catch (Exception e) {
            assertEquals("Already Exists - upload-test.txt", e.getMessage());
        }
        assertTrue(Files.exists(service.getUploadDir().resolve(service.encodeFileName(fileName))));

        service.removeUploadedDocumentFile(fileName);
        assertFalse(Files.exists(service.getUploadDir().resolve(service.encodeFileName(fileName))));
    }

    @Test
    void stageUploadReplacesAStagedFileThatWasNeverIndexed() throws Exception {
        File original = File.createTempFile("original", ".txt");
        Files.write(original.toPath(), "original content".getBytes());
        File replacement = File.createTempFile("replacement", ".txt");
        Files.write(replacement.toPath(), "replacement content".getBytes());
        String fileName = "duplicate-test.txt";
        Path stagedPath = service.getUploadDir().resolve(service.encodeFileName(fileName));
        Files.deleteIfExists(stagedPath);
        service.stageUpload(fileName, original);

        service.stageUpload(fileName, replacement);

        assertEquals("replacement content", Files.readString(stagedPath));
        service.removeUploadedDocumentFile(fileName);
    }

    @Test
    void stageUploadRejectsAFileNameThatIsAlreadyIndexed() throws Exception {
        File original = File.createTempFile("original", ".txt");
        Files.write(original.toPath(), "original content".getBytes());
        File replacement = File.createTempFile("replacement", ".txt");
        Files.write(replacement.toPath(), "replacement content".getBytes());
        String fileName = "indexed-duplicate.txt";
        Path stagedPath = service.getUploadDir().resolve(service.encodeFileName(fileName));
        Files.deleteIfExists(stagedPath);
        service.stageUpload(fileName, original);
        VectorStoreDocumentInfo indexed = service.loadDocument(fileName,
                List.of(new Document("indexed-1", "original content", Map.of())));
        try {
            assertThrows(FileAlreadyExistsException.class, () -> service.stageUpload(fileName, replacement));
            assertEquals("original content", Files.readString(stagedPath));
        } finally {
            service.deleteDocumentInfo(indexed);
            service.removeUploadedDocumentFile(fileName);
        }
    }

    @Test
    void testDocumentLifecycle() {
        String docName = "test-doc.txt";
        List<Document> documents = List.of(
                new Document("doc1", "Sample text", Map.of("key", "value"))
        );

        VectorStoreDocumentInfo addedInfo = service.loadDocument(docName, documents);

        assertNotNull(addedInfo.docInfoId());
        assertTrue(service.getDocumentList().stream()
                .anyMatch(info -> info.docInfoId().equals(addedInfo.docInfoId())));

        VectorStoreDocumentInfo updatedInfo = service.updateDocumentInfo(addedInfo, "New Title");

        assertEquals("New Title", updatedInfo.title());
        assertEquals(addedInfo.docInfoId(), updatedInfo.docInfoId());

        service.deleteDocumentInfo(addedInfo);
        assertFalse(service.getDocumentList().contains(updatedInfo));
    }

    @Test
    void testTextSplitting() {
        Path testFilePath = service.getUploadDir().resolve(service.encodeFileName("test-split.txt"));
        File testFile = testFilePath.toFile();
        if (testFile.exists()) {
            testFile.delete();
        }
        try {
            Files.writeString(testFilePath, """
                    This is sample data for testing text splitting functionality.
                    We're creating text with multiple lines.
                    The text should be long enough to verify that splitting works properly.
                    This text needs to be sufficiently long to be split into multiple chunks.
                    It is intended to test token-based splitting algorithms.
                    It includes various sentences to ensure split boundaries are handled correctly.""");
        } catch (IOException e) {
            fail("Failed to create sample data file: " + e.getMessage());
        }

        Resource testResource = new FileSystemResource(testFile);
        List<Document> defaultSplit = service.extractAndTransform(List.of("test-split.txt"),
                service.getDefaultTokenTextSplitter()).get("test-split.txt");
        assertEquals(1, defaultSplit.size());
        assertEquals("test-split.txt", defaultSplit.getFirst().getMetadata().get("source"));

        OfflineEtlPipelineService.TokenTextSplitInfo customConfig =
                new OfflineEtlPipelineService.TokenTextSplitInfo(50, 10, 2, 500, false);
        List<Document> customSplit = service.split(testResource, customConfig);
        assertEquals(2, customSplit.size());

        try {
            Files.deleteIfExists(testFilePath);
        } catch (IOException e) {
            System.err.println("Failed to delete test file: " + e.getMessage());
        }
    }

    @Test
    void testDocumentEvents() {
        PropertyChangeListener listener = mock(PropertyChangeListener.class);
        PropertyChangeSupport documentInfoChangeSupport = new PropertyChangeSupport(this);
        documentInfoChangeSupport.addPropertyChangeListener(listener);

        VectorStoreDocumentInfo docInfo =
                service.loadDocument("event-add.txt", List.of(new Document("id", "text", Map.of())));
        documentInfoChangeSupport.firePropertyChange(VectorStoreView.DOCUMENT_ADDING_EVENT, null, docInfo);

        List<VectorStoreDocumentInfo> docList = service.getDocumentList();
        documentInfoChangeSupport.firePropertyChange(VectorStoreView.DOCUMENT_SELECTING_EVENT, null, docList);

        service.deleteDocumentInfo(docInfo);
        documentInfoChangeSupport.firePropertyChange(VectorStoreView.DOCUMENTS_DELETE_EVENT, docInfo, null);

        ArgumentCaptor<PropertyChangeEvent> eventCaptor = ArgumentCaptor.forClass(PropertyChangeEvent.class);
        verify(listener, times(3)).propertyChange(eventCaptor.capture());
        List<PropertyChangeEvent> events = eventCaptor.getAllValues();

        assertTrue(events.stream()
                .anyMatch(e -> e.getPropertyName().equals(VectorStoreView.DOCUMENT_ADDING_EVENT)));
        assertTrue(events.stream()
                .anyMatch(e -> e.getPropertyName().equals(VectorStoreView.DOCUMENT_SELECTING_EVENT)));
        assertTrue(events.stream()
                .anyMatch(e -> e.getPropertyName().equals(VectorStoreView.DOCUMENTS_DELETE_EVENT)));
    }


    @Test
    void testFileSizeLimit() {
        DataSize maxSize = service.getMaxUploadSize();
        assertEquals(DataSize.ofMegabytes(20), maxSize);
    }

    @Test
    void readerRecommendationMapsExtensions() {
        assertEquals(OfflineEtlPipelineService.ReaderType.MARKDOWN,
                OfflineEtlPipelineService.ReaderType.recommendFor("guide.md"));
        assertEquals(OfflineEtlPipelineService.ReaderType.HTML,
                OfflineEtlPipelineService.ReaderType.recommendFor("page.HTML"));
        assertEquals(OfflineEtlPipelineService.ReaderType.JSON,
                OfflineEtlPipelineService.ReaderType.recommendFor("data.json"));
        assertEquals(OfflineEtlPipelineService.ReaderType.TEXT,
                OfflineEtlPipelineService.ReaderType.recommendFor("notes.txt"));
        assertEquals(OfflineEtlPipelineService.ReaderType.PDF_PAGE,
                OfflineEtlPipelineService.ReaderType.recommendFor("paper.pdf"));
        assertEquals(OfflineEtlPipelineService.ReaderType.TIKA,
                OfflineEtlPipelineService.ReaderType.recommendFor("slides.pptx"));
        assertEquals(OfflineEtlPipelineService.ReaderType.TIKA,
                OfflineEtlPipelineService.ReaderType.recommendFor("no-extension"));
    }

    @Test
    void readerFactoryReadsEachFormat() throws IOException {
        Path dir = Files.createTempDirectory("etl-readers");

        Path md = dir.resolve("sample.md");
        Files.writeString(md, "# Title\n\nSome markdown body text.\n\n```java\nint x = 1;\n```\n");
        List<Document> mdDocs = service.newDocumentReader(new FileSystemResource(md.toFile()),
                OfflineEtlPipelineService.ExtractOptions.defaults(
                        OfflineEtlPipelineService.ReaderType.MARKDOWN)).read();
        assertFalse(mdDocs.isEmpty());
        assertTrue(mdDocs.stream().anyMatch(document -> document.getText().contains("markdown body")));

        Path html = dir.resolve("sample.html");
        Files.writeString(html, "<html><head><title>T</title></head><body><p>Hello html body</p></body></html>");
        List<Document> htmlDocs = service.newDocumentReader(new FileSystemResource(html.toFile()),
                OfflineEtlPipelineService.ExtractOptions.defaults(
                        OfflineEtlPipelineService.ReaderType.HTML)).read();
        assertEquals(1, htmlDocs.size());
        assertTrue(htmlDocs.getFirst().getText().contains("Hello html body"));

        Path json = dir.resolve("sample.json");
        Files.writeString(json, "[{\"title\": \"first\", \"body\": \"json content one\"}]");
        List<Document> jsonDocs = service.newDocumentReader(new FileSystemResource(json.toFile()),
                new OfflineEtlPipelineService.ExtractOptions(OfflineEtlPipelineService.ReaderType.JSON,
                        "UTF-8", "title, body", "body", true, true, false, 1)).read();
        assertEquals(1, jsonDocs.size());
        assertTrue(jsonDocs.getFirst().getText().contains("json content one"));

        Path txt = dir.resolve("sample.txt");
        Files.writeString(txt, "plain text content line");
        List<Document> txtDocs = service.newDocumentReader(new FileSystemResource(txt.toFile()),
                OfflineEtlPipelineService.ExtractOptions.defaults(
                        OfflineEtlPipelineService.ReaderType.TEXT)).read();
        assertEquals(1, txtDocs.size());
        assertTrue(txtDocs.getFirst().getText().contains("plain text content line"));
    }

    @Test
    void splitterHonorsEncodingTypeAndFallsBackOnUnknown() {
        OfflineEtlPipelineService.TokenTextSplitInfo o200k =
                new OfflineEtlPipelineService.TokenTextSplitInfo(800, 350, 5, 10000, true, "O200K_BASE");
        assertNotNull(service.newTokenTextSplitter(o200k));

        OfflineEtlPipelineService.TokenTextSplitInfo unknown =
                new OfflineEtlPipelineService.TokenTextSplitInfo(800, 350, 5, 10000, true, "NOT_A_REAL_ENCODING");
        assertNotNull(service.newTokenTextSplitter(unknown));

        assertEquals("CL100K_BASE", OfflineEtlPipelineService.DEFAULT_TOKEN_TEXT_SPLIT_INFO.encodingType());
    }

    @Test
    void enrichIsNoOpWhenDisabledOrNoChatModel() {
        List<Document> chunks = List.of(new Document("c1", "chunk text", Map.of()));

        List<Document> untouched = service.enrich(chunks, OfflineEtlPipelineService.EnrichOptions.NONE, null, null);
        assertEquals(chunks, untouched);

        OfflineEtlPipelineService.EnrichOptions both =
                new OfflineEtlPipelineService.EnrichOptions(true, 5, true, true, false);
        assertEquals(2, both.llmCallsPerChunk());
        OfflineEtlPipelineService.EnrichOptions keywordOnly =
                new OfflineEtlPipelineService.EnrichOptions(true, 5, false, false, false);
        assertEquals(1, keywordOnly.llmCallsPerChunk());
    }

    @Test
    void enrichedMetadataDropsReasoningTags() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KeywordMetadataEnricher.EXCERPT_KEYWORDS_METADATA_KEY, "escalation, on-call, paging </think>");
        metadata.put("section_summary", "<think>Let me weigh the options.</think>The section covers escalation.");
        metadata.put("next_section_summary", "Battery limits.");
        Document document = new Document("c1", "chunk text", metadata);

        OfflineEtlPipelineService.stripReasoning(document);

        assertEquals("escalation, on-call, paging",
                document.getMetadata().get(KeywordMetadataEnricher.EXCERPT_KEYWORDS_METADATA_KEY));
        assertEquals("The section covers escalation.", document.getMetadata().get("section_summary"));
        assertEquals("Battery limits.", document.getMetadata().get("next_section_summary"));
    }
}
