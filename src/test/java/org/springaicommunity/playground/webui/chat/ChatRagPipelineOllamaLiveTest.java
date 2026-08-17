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
package org.springaicommunity.playground.webui.chat;

import tools.jackson.databind.node.StringNode;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ReasoningEffort;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.service.vectorstore.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test: full and partial modular RAG pipelines driven through the browserless
 * chat UI against a REAL local Ollama (chat + embedding models, no mocks). Opt-in only - CI and
 * plain `mvn test` skip it, following the RUN_NETWORK_SMOKE convention:
 *
 * RUN_OLLAMA_IT=true ./mvnw test -Dtest=ChatRagPipelineOllamaLiveTest
 *
 * Requires Ollama on 127.0.0.1:11434 with the `ollama` profile's chat model and the default
 * embedding model pulled. Budget several minutes: every enabled pre-retrieval stage is a real
 * LLM call on a local model.
 */
@SpringBootTest(properties = {
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.ai.playground.user-home=${java.io.tmpdir}/rag-ollama-live-test-home"
})
@ActiveProfiles("ollama")
class ChatRagPipelineOllamaLiveTest extends SpringBrowserlessTest {

    private static final Duration SIMPLE_DEADLINE = Duration.ofMinutes(3);
    private static final Duration STAGED_DEADLINE = Duration.ofMinutes(8);

    @Autowired
    private RagPipelineService ragPipelineService;

    @Autowired
    private OfflineEtlPipelineService offlineEtlPipelineService;

    @Autowired
    private VectorStoreService vectorStoreService;

    private RagPipeline pipeline;
    private VectorStoreDocumentInfo documentInfo;

    @BeforeAll
    static void requiresOptInAndRunningOllama() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_OLLAMA_IT")),
                "RUN_OLLAMA_IT=true not set; skipping live Ollama RAG pipeline test");
        assumeTrue(ollamaIsUp(), "Ollama not reachable on 127.0.0.1:11434; skipping");
    }

    private static boolean ollamaIsUp() {
        try {
            HttpResponse<Void> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/tags"))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @AfterEach
    void cleanUpPipelineAndDocument() {
        if (this.pipeline != null) this.ragPipelineService.deleteById(this.pipeline.id());
        if (this.documentInfo != null) {
            this.vectorStoreService.delete(this.documentInfo.documentListSupplier().get().stream()
                    .map(Document::getId).toList());
            this.offlineEtlPipelineService.deleteDocumentInfo(this.documentInfo);
        }
    }

    private void embedFactDocument() {
        this.documentInfo = this.offlineEtlPipelineService.loadDocument("aurora-ledger-live.txt",
                List.of(new Document("The Aurora Ledger is an internal accounting system used by the Helix "
                        + "division. It reconciles invoices every Tuesday at 03:00 UTC. Its maintainer is "
                        + "Mira Kwon, and the escalation contact is Devin Park.", Map.of())));
        this.vectorStoreService.add(this.documentInfo);
    }

    @Test
    void retrievalOnlyPipelineGroundsTheAnswerInTheEmbeddedDocument() throws InterruptedException {
        embedFactDocument();
        this.pipeline = this.ragPipelineService.create("live-simple", null, List.of(), null, null, null, null);

        ChatView view = openChatWithPipeline(this.pipeline.id());
        sendPrompt(view, "Who maintains the Aurora Ledger? Answer with the name only.");

        assertThat(awaitRagCompleted(view, SIMPLE_DEADLINE)).isTrue();
        String panel = ragPanelContent(view);
        assertThat(panel).contains("Running RAG pipeline `live-simple`");
        assertThat(panel).contains("`retrieve`");
        assertThat(panel).contains("Retrieved");
        assertThat(awaitMarkdownContaining(view, "Kwon", SIMPLE_DEADLINE)).isTrue();
    }

    @Test
    void fullyStagedPipelineRunsEveryModularStageAgainstOllama() throws InterruptedException {
        embedFactDocument();
        this.pipeline = this.ragPipelineService.create("live-full", null, List.of(),
                new RagPipeline.PreRetrievalConfig(true, null, true, null, true, "english", null,
                        true, 2, true, null, null),
                null,
                new RagPipeline.PostRetrievalConfig(true, 3),
                new RagPipeline.GenerationConfig(false, null, null, false,
                        RagPipeline.DocumentFormat.TEXT_WITH_SOURCE));

        ChatView view = openChatWithPipeline(this.pipeline.id());
        sendPrompt(view, "Who maintains the Aurora Ledger? Answer with the name only.");

        assertThat(awaitRagCompleted(view, STAGED_DEADLINE)).isTrue();
        String panel = ragPanelContent(view);
        assertThat(panel).contains("Stages: Rewrite → Compress → Translate → Multi-Q×2 → Retrieve");
        assertThat(panel).contains("`rewrite` done");
        assertThat(panel).contains("`compression` done");
        assertThat(panel).contains("`translation` done");
        assertThat(panel).contains("`multiQuery` done");
        assertThat(panel).contains("`joiner` done");
        assertThat(panel).contains("format=text+source");
        assertThat(awaitMarkdownContaining(view, "Kwon", STAGED_DEADLINE)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ChatView openChatWithPipeline(String pipelineId) {
        ChatView view = navigate(ChatView.class);
        $(Select.class, view).withCondition(select -> select.getValue() instanceof ReasoningEffort).first()
                .setValue(ReasoningEffort.OFF);
        ComboBox<ChatService.RagSource> sources = $(ComboBox.class, view)
                .withCondition(combo -> ((ComboBox<Object>) combo).getListDataView().getItems()
                        .anyMatch(ChatService.RagSource.class::isInstance))
                .first();
        sources.setValue(sources.getListDataView().getItems()
                .filter(source -> pipelineId.equals(source.sourceId())).findFirst().orElseThrow());
        return view;
    }

    private void sendPrompt(ChatView view, String text) {
        TextArea prompt = $(TextArea.class, view)
                .withCondition(area -> "Ask Spring AI Playground".equals(area.getPlaceholder()))
                .first();
        test(prompt).setValue(text);
        test($(Button.class, view)
                .withCondition(button -> "Submit".equals(button.getTooltip().getText()))
                .first()).click();
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(invocation -> invocation.getInvocation().getExpression().contains("return this.value"))
                .forEach(invocation -> invocation.complete(StringNode.valueOf(text)));
    }

    private boolean awaitRagCompleted(ChatView view, Duration deadline) throws InterruptedException {
        return awaitMarkdownContaining(view, "VectorDB document search completed.", deadline);
    }

    private String ragPanelContent(ChatView view) {
        return $(Markdown.class, view).all().stream().map(Markdown::getContent)
                .filter(content -> content != null && content.contains("Running RAG pipeline"))
                .findFirst().orElseThrow();
    }

    private boolean awaitMarkdownContaining(ChatView view, String expected, Duration deadline)
            throws InterruptedException {
        long end = System.currentTimeMillis() + deadline.toMillis();
        while (System.currentTimeMillis() < end) {
            roundTrip();
            boolean rendered = $(Markdown.class, view).all().stream()
                    .anyMatch(markdown -> markdown.getContent() != null && markdown.getContent().contains(expected));
            if (rendered) return true;
            Thread.sleep(500L);
        }
        return false;
    }
}
