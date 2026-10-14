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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.RagPipelineExecutor;
import org.springaicommunity.playground.service.vectorstore.TraceEvent;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class VectorStorePipelineRunProgressTest extends SpringBrowserlessTest {

    @Test
    void stepsShowSettingsFirstThenEachResultAsItArrivesAndFoldBehindTheFinalAnswer() throws Exception {
        CountDownLatch retrievalDelivered = new CountDownLatch(1);
        CountDownLatch llmFinished = new CountDownLatch(1);
        List<Document> joined = List.of(new Document("alpha text"), new Document("beta text"));
        List<Document> finalDocs = List.of(joined.getFirst());
        RagPipelineExecutor executor = mock(RagPipelineExecutor.class);
        RagPipelineExecutor.Session session = mock(RagPipelineExecutor.Session.class);
        when(executor.newSession()).thenReturn(session);
        when(session.run(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<TraceEvent> trace = invocation.getArgument(2);
            trace.accept(TraceEvent.info("retrieve", "scope: (all docs) · topK=4 · threshold=0.35"));
            trace.accept(TraceEvent.info("joiner", "done 120ms · 2 unique docs", joined));
            retrievalDelivered.countDown();
            llmFinished.await(10, TimeUnit.SECONDS);
            trace.accept(TraceEvent.info("postProcess", "done 1ms · 1 docs", finalDocs));
            trace.accept(TraceEvent.info("augment", "done 2ms · prompt 11 chars", "prompt text"));
            trace.accept(TraceEvent.info("llm", "done 2000ms · 10 chars"));
            trace.accept(TraceEvent.info("done", "total 2345ms"));
            return new RagPipelineExecutor.RunResult(joined, finalDocs, "prompt text", "the answer", 2345L);
        });
        VectorStorePipelineRunView view = new VectorStorePipelineRunView(executor);
        UI.getCurrent().add(view);
        view.showPipeline(new RagPipeline("p1", "naive", null, List.of(), null, null, null,
                new RagPipeline.GenerationConfig(true, null, null, true, null), 0L, 0L));
        roundTrip();

        test($(TextField.class, view).first()).setValue("what is rag");
        test($(Button.class, view).withCondition(button -> "Run".equals(button.getText())).first()).click();
        assertThat(retrievalDelivered.await(10, TimeUnit.SECONDS)).isTrue();

        String pendingText = pollUntil(() -> {
            drainUi();
            return pendingBubble(view).map(bubble -> bubble.getElement().getTextRecursively()).orElse("");
        }, text -> text.contains("2 unique docs"));
        assertThat(pendingText)
                .contains("Running pipeline…")
                .contains("Pre-Retrieval(none, raw query)")
                .contains("RetrievalScope: all documents")
                .contains("Post-RetrievalRe-rank by score")
                .contains("AugmentEmpty context: allowed")
                .contains("LLMRun LLM: on")
                .contains("alpha text").contains("beta text")
                .doesNotContain("Final Answer");
        assertThat(pendingText).containsPattern("\\d+\\.\\d s");

        llmFinished.countDown();
        String finalText = pollUntil(() -> {
            drainUi();
            return view.getElement().getTextRecursively();
        }, text -> text.contains("Final Answer"));
        assertThat(finalText)
                .contains("Final Answerthe answer")
                .contains("Retrieved 2 docs → 1 final · 2.3 s")
                .doesNotContain("2345 ms");
        assertThat(finalText.indexOf("Final Answer")).isLessThan(finalText.indexOf("Steps (5)"));
        assertThat(pendingBubble(view)).isEmpty();
        assertThat($(Details.class, view).all())
                .extracting(Details::getSummaryText)
                .contains("Steps (5)", "Documents (1)", "Final prompt (11 chars)", "Trace (6 events)");
        assertThat($(Details.class, view).all()).noneMatch(Details::isOpened);
        assertThat(finalText).containsPattern("LLMRun LLM: on\\d+\\.\\d s");
    }

    private Optional<Div> pendingBubble(VectorStorePipelineRunView view) {
        return $(Div.class, view).withCondition(div -> div.hasClassName("vstore-chat-pending"))
                .all().stream().findFirst();
    }

    private void drainUi() {
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
        roundTrip();
    }

    private static <T> T pollUntil(Supplier<T> supplier, Predicate<T> ready)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        T value = supplier.get();
        while (!ready.test(value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            value = supplier.get();
        }
        return value;
    }
}
