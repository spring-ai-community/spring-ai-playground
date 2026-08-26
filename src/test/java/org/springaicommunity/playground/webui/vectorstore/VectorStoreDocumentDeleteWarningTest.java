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
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.listbox.MultiSelectListBox;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarTester;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@SpringBootTest
class VectorStoreDocumentDeleteWarningTest extends SpringBrowserlessTest {

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private OfflineEtlPipelineService documentService;

    @Autowired
    private RagPipelineService ragPipelineService;

    @Test
    @SuppressWarnings("unchecked")
    void deleteDialogWarnsAboutPipelinesScopedToTheDocument() {
        Document chunk = new Document("scoped chunk body", Map.of("source", "browserless"));
        VectorStoreDocumentInfo info = this.documentService.loadDocument("scoped-doc.txt", List.of(chunk));
        this.ragPipelineService.create("Scoped pipeline", null, List.of(info.docInfoId()), null, null, null, null);
        lenient().when(this.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(info.documentListSupplier().get());

        VectorStoreView view = navigate(VectorStoreView.class);
        VectorStoreDocumentView documentView = $(VectorStoreDocumentView.class, view).first();
        MultiSelectListBox<VectorStoreDocumentInfo> listBox = $(MultiSelectListBox.class, documentView).first();
        listBox.select(info);
        roundTrip();

        new MenuBarTester<>($(MenuBar.class, documentView).first()).clickItem(0);
        roundTrip();

        Dialog dialog = $(Dialog.class).withCondition(Dialog::isOpened).first();
        List<String> spanTexts = $(Span.class, dialog).all().stream().map(Span::getText).toList();
        assertThat(spanTexts).anyMatch(text -> text.startsWith("Scoped by pipeline: Scoped pipeline"));
    }

}
