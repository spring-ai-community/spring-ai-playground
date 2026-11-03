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
package org.springaicommunity.playground.webui.vectorstore;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VectorStoreNewDocumentReuploadTest extends SpringBrowserlessTest {

    private static final String FIRST_FILE = "reupload-first.md";
    private static final String SECOND_FILE = "reupload-second.md";
    private static final String KOREAN_FILE_NFC = Normalizer.normalize("재업로드-핸드북.md", Normalizer.Form.NFC);
    private static final String KOREAN_FILE_NFD = Normalizer.normalize("재업로드-핸드북.md", Normalizer.Form.NFD);

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private OfflineEtlPipelineService documentService;

    @AfterEach
    void removeStagedFiles() throws IOException {
        for (String fileName : List.of(FIRST_FILE, SECOND_FILE, KOREAN_FILE_NFC, KOREAN_FILE_NFD)) {
            this.documentService.removeUploadedDocumentFile(fileName);
        }
    }

    @Test
    void cancellingThenUploadingTheSameFileAgainChunksWithoutAnError() throws IOException {
        VectorStoreView view = navigate(VectorStoreView.class);
        long stagedBefore = stagedFileCount();
        Dialog dialog = openNewDocumentDialog(view);
        VectorStoreDocumentUpload upload = $(VectorStoreDocumentUpload.class, dialog).single();
        upload.acceptUpload(FIRST_FILE, "# first\n\nhello".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getUploadedFileNames()).containsExactly(FIRST_FILE);
        assertThat(stagedFileCount()).isEqualTo(stagedBefore + 1);

        test(footerButton(dialog, "Cancel")).click();
        roundTrip();
        assertThat(dialog.isOpened()).isFalse();
        assertThat(upload.getUploadedFileNames()).isEmpty();
        assertThat(stagedFileCount()).isEqualTo(stagedBefore);

        dialog = openNewDocumentDialog(view);
        upload.acceptUpload(FIRST_FILE, "# first again\n\nhello".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getUploadedFileNames()).containsExactly(FIRST_FILE);

        test(footerButton(dialog, "Chunk Document")).click();
        roundTrip();

        assertThat($(Notification.class).all())
                .extracting(notification -> notification.getElement().getProperty("text"))
                .noneMatch(text -> text != null && text.startsWith("Chunk extraction failed"));
        assertThat(dialog.isOpened()).isFalse();
    }

    @Test
    void removingWithTheXThenUploadingTheSameAsciiFileAgainChunksWithoutAnError() throws IOException {
        removeThenReuploadChunksWithoutAnError(FIRST_FILE, FIRST_FILE);
    }

    @Test
    void removingWithTheXThenUploadingTheSameKoreanFileAgainChunksWithoutAnError() throws IOException {
        removeThenReuploadChunksWithoutAnError(KOREAN_FILE_NFD, KOREAN_FILE_NFC);
    }

    private void removeThenReuploadChunksWithoutAnError(String uploadedName, String removedName)
            throws IOException {
        VectorStoreView view = navigate(VectorStoreView.class);
        long stagedBefore = stagedFileCount();
        Dialog dialog = openNewDocumentDialog(view);
        VectorStoreDocumentUpload upload = $(VectorStoreDocumentUpload.class, dialog).single();
        upload.acceptUpload(uploadedName, "# first\n\nhello".getBytes(StandardCharsets.UTF_8));
        assertThat(stagedFileCount()).isEqualTo(stagedBefore + 1);

        upload.discardUploadedFile(removedName);
        assertThat(upload.getUploadedFileNames()).isEmpty();
        assertThat(stagedFileCount()).isEqualTo(stagedBefore);

        upload.acceptUpload(uploadedName, "# again\n\nhello".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getUploadedFileNames()).hasSize(1);
        assertThat(stagedFileCount()).isEqualTo(stagedBefore + 1);

        test(footerButton(dialog, "Chunk Document")).click();
        roundTrip();

        assertThat($(Notification.class).all())
                .extracting(notification -> notification.getElement().getProperty("text"))
                .noneMatch(text -> text != null && text.startsWith("Chunk extraction failed"));
        assertThat(dialog.isOpened()).isFalse();
    }

    @Test
    void cancellingThenUploadingADifferentFileForgetsTheCancelledOne() throws IOException {
        VectorStoreView view = navigate(VectorStoreView.class);
        long stagedBefore = stagedFileCount();
        Dialog dialog = openNewDocumentDialog(view);
        VectorStoreDocumentUpload upload = $(VectorStoreDocumentUpload.class, dialog).single();
        upload.acceptUpload(FIRST_FILE, "# first".getBytes(StandardCharsets.UTF_8));

        test(footerButton(dialog, "Cancel")).click();
        roundTrip();

        openNewDocumentDialog(view);
        upload.acceptUpload(SECOND_FILE, "# second".getBytes(StandardCharsets.UTF_8));

        assertThat(upload.getUploadedFileNames()).containsExactly(SECOND_FILE);
        assertThat(stagedFileCount()).isEqualTo(stagedBefore + 1);
    }

    private Dialog openNewDocumentDialog(VectorStoreView view) {
        Button open = $(Button.class, view).withCondition(button -> button.getTooltip() != null
                && "New Document & ETL Pipeline".equals(button.getTooltip().getText())).single();
        test(open).click();
        roundTrip();
        return $(Dialog.class).withCondition(Dialog::isOpened).single();
    }

    private Button footerButton(Dialog dialog, String text) {
        return $(Button.class, dialog).withCondition(button -> text.equals(button.getText())).single();
    }

    private long stagedFileCount() throws IOException {
        try (Stream<Path> staged = Files.list(this.documentService.getUploadDir())) {
            return staged.count();
        }
    }
}
