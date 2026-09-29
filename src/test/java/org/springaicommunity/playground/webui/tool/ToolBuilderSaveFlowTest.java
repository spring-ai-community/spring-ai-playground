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
package org.springaicommunity.playground.webui.tool;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolDraftInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ToolBuilderSaveFlowTest extends SpringBrowserlessTest {

    @Autowired
    private ToolSpecService toolSpecService;

    @AfterEach
    void cleanup() {
        for (String name : List.of("qa_ui_draft", "qa_ui_dup")) {
            this.toolSpecService.getToolSpecAsOpt(name)
                    .ifPresent(spec -> this.toolSpecService.deleteToolSpec(spec.toolId()));
        }
    }

    private ToolBuilderView openNewToolForm() {
        ToolStudioView view = navigate(ToolStudioView.class);
        Button newTool = $(Button.class, view)
                .withCondition(button -> "New Tool".equals(button.getTooltip().getText())).first();
        test(newTool).click();
        roundTrip();
        return $(ToolBuilderView.class, view).first();
    }

    private static List<String> notificationTexts(List<Notification> notifications) {
        return notifications.stream().map(notification -> notification.getElement().getProperty("text")).toList();
    }

    @Test
    void saveDraftGoesThroughTheServiceAndReportsTheDraft() {
        ToolBuilderView builder = openNewToolForm();
        TextField nameField = $(TextField.class, builder)
                .withCondition(field -> "Tool Name".equals(field.getLabel())).first();
        test(nameField).setValue("qa_ui_draft");
        Button saveDraft = $(Button.class, builder)
                .withCondition(button -> "Save Draft".equals(button.getText())).first();

        test(saveDraft).click();
        roundTrip();

        assertThat(notificationTexts($(Notification.class).all())).contains("Tool 'qa_ui_draft' saved as Draft.");
        assertThat(this.toolSpecService.getToolSpecAsOpt("qa_ui_draft")).map(ToolSpec::draft).contains(true);
    }

    @Test
    void duplicateNameIsRefusedByTheService() {
        this.toolSpecService.saveDraft(new ToolDraftInput(null, "qa_ui_dup", "d", List.of(), List.of(),
                "return 1;", "CUSTOM", Set.of(), null, null));
        ToolBuilderView builder = openNewToolForm();
        TextField nameField = $(TextField.class, builder)
                .withCondition(field -> "Tool Name".equals(field.getLabel())).first();
        test(nameField).setValue("qa_ui_dup");
        Button saveDraft = $(Button.class, builder)
                .withCondition(button -> "Save Draft".equals(button.getText())).first();

        test(saveDraft).click();
        roundTrip();

        assertThat(notificationTexts($(Notification.class).all())).contains("A tool with this name already exists.");
    }
}
