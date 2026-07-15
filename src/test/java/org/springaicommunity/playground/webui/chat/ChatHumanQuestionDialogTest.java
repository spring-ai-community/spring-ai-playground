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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.HumanQuestion;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ChatHumanQuestionDialogTest extends SpringBrowserlessTest {

    @Test
    void approvingConfirmDialogReturnsApproveAnswer() throws Exception {
        ChatHumanQuestionHandler handler = new ChatHumanQuestionHandler(UI.getCurrent(), 10);
        HumanQuestion question = HumanQuestion.approval("run-tool", "Approve tool", "Run deleteFile?");

        CompletableFuture<Map<String, String>> answer =
                CompletableFuture.supplyAsync(() -> handler.ask(List.of(question)));
        ConfirmDialog dialog = awaitAttached(() -> $(ConfirmDialog.class).all().stream()
                .findFirst().orElse(null));
        test(dialog).confirm();

        assertThat(answer.get(10, TimeUnit.SECONDS)).containsEntry("run-tool", "Approve");
    }

    @Test
    void decliningConfirmDialogReturnsDeclineAnswer() throws Exception {
        ChatHumanQuestionHandler handler = new ChatHumanQuestionHandler(UI.getCurrent(), 10);
        HumanQuestion question = HumanQuestion.approval("run-tool", "Approve tool", "Run deleteFile?");

        CompletableFuture<Map<String, String>> answer =
                CompletableFuture.supplyAsync(() -> handler.ask(List.of(question)));
        ConfirmDialog dialog = awaitAttached(() -> $(ConfirmDialog.class).all().stream()
                .findFirst().orElse(null));
        test(dialog).cancel();

        assertThat(answer.get(10, TimeUnit.SECONDS)).containsEntry("run-tool", "Decline");
    }

    @Test
    void batchDialogCollectsRadioSelectionsOnConfirm() throws Exception {
        ChatHumanQuestionHandler handler = new ChatHumanQuestionHandler(UI.getCurrent(), 10);
        List<HumanQuestion> questions = List.of(
                HumanQuestion.approval("first-tool", "Approve tool", "Run first?"),
                HumanQuestion.approval("second-tool", "Approve tool", "Run second?"));

        CompletableFuture<Map<String, String>> answer =
                CompletableFuture.supplyAsync(() -> handler.ask(questions));
        Dialog dialog = awaitAttached(() -> $(Dialog.class)
                .withCondition(d -> "Tool approvals required".equals(d.getHeaderTitle()))
                .all().stream().findFirst().orElse(null));

        RadioButtonGroup<String> firstChoice = $(RadioButtonGroup.class, dialog).all().getFirst();
        firstChoice.setValue("Approve");
        test($(Button.class, dialog)
                .withCondition(button -> "Confirm".equals(button.getText()))
                .first()).click();

        Map<String, String> answers = answer.get(10, TimeUnit.SECONDS);
        assertThat(answers)
                .containsEntry("first-tool", "Approve")
                .containsEntry("second-tool", "Decline");
    }

    @Test
    void cancelPendingFallsBackToDefaultDeclineAnswers() throws Exception {
        ChatHumanQuestionHandler handler = new ChatHumanQuestionHandler(UI.getCurrent(), 10);
        HumanQuestion question = HumanQuestion.approval("run-tool", "Approve tool", "Run deleteFile?");

        CompletableFuture<Map<String, String>> answer =
                CompletableFuture.supplyAsync(() -> handler.ask(List.of(question)));
        awaitAttached(() -> $(ConfirmDialog.class).all().stream().findFirst().orElse(null));
        handler.cancelPending();

        assertThat(answer.get(10, TimeUnit.SECONDS)).containsEntry("run-tool", "Decline");
    }

    private <T> T awaitAttached(Supplier<T> query) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            roundTrip();
            T found = query.get();
            if (found != null) {
                return found;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("dialog did not appear");
    }

}
