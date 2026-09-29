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
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VectorStoreAddChunkFlowTest extends SpringBrowserlessTest {

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private OfflineEtlPipelineService documentService;

    @Test
    void addingAChunkWithoutTextShowsTheServiceErrorAndKeepsTheDialogOpen() {
        Dialog dialog = openAddChunkDialog();

        test(addButton(dialog)).click();
        roundTrip();

        assertThat($(Notification.class).all())
                .extracting(notification -> notification.getElement().getProperty("text"))
                .contains("Document name and text are required");
        assertThat(dialog.isOpened()).isTrue();
    }

    @Test
    void addingAChunkCreatesTheCustomDocumentThroughTheService() {
        Dialog dialog = openAddChunkDialog();
        test($(TextArea.class, dialog).withCondition(area -> "Text".equals(area.getLabel())).single())
                .setValue("chunk added from the browserless test");

        test(addButton(dialog)).click();
        roundTrip();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(this.documentService.read()).extracting(VectorStoreDocumentInfo::title)
                .contains(VectorStoreContentView.DEFAULT_CUSTOM_DOCUMENT_NAME);
    }

    private Dialog openAddChunkDialog() {
        VectorStoreView view = navigate(VectorStoreView.class);
        VectorStoreContentView contentView = $(VectorStoreContentView.class, view).single();
        Button add = $(Button.class, contentView).withCondition(button -> button.getTooltip() != null
                && "Add a custom chunk".equals(button.getTooltip().getText())).single();
        test(add).click();
        roundTrip();
        return $(Dialog.class).withCondition(Dialog::isOpened).single();
    }

    private Button addButton(Dialog dialog) {
        return $(Button.class, dialog).withCondition(button -> "Add".equals(button.getText())).single();
    }

}
