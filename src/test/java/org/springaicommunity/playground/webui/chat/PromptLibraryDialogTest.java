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
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.Preset;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetService;
import org.springaicommunity.playground.service.chat.ChatSystemPromptTemplateRenderer;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PromptLibraryDialogTest extends SpringBrowserlessTest {

    @Autowired
    private ChatSystemPromptPresetService presetService;

    @Autowired
    private ChatSystemPromptTemplateRenderer templateRenderer;

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private ToolSpecPersistenceService toolSpecPersistenceService;

    @Test
    void browsingEveryPresetRendersDetailAndApplyDeliversSelection() {
        AtomicReference<Preset> applied = new AtomicReference<>();
        PromptLibraryDialog dialog = new PromptLibraryDialog(this.presetService, this.templateRenderer,
                applied::set, name -> ToolSpecService.ToolReadiness.READY,
                this.toolSpecPersistenceService.getDefaultToolSpecs().stream()
                        .filter(spec -> spec.name() != null).toList(),
                this.toolSpecService::riskLevelOf, this.toolSpecService::categoryOf);
        dialog.open();
        roundTrip();

        for (Preset preset : this.presetService.presets()) {
            HorizontalLayout row = presetRow(dialog, preset.displayName());
            ComponentUtil.fireEvent(row, new ClickEvent<>(row));
            roundTrip();
        }

        Button apply = $(Button.class, dialog)
                .withCondition(button -> "Apply to chat".equals(button.getText()))
                .first();
        test(apply).click();
        roundTrip();

        assertThat(applied.get()).isNotNull();
        assertThat(dialog.isOpened()).isFalse();
    }

    @Test
    void savingATemplateWithoutANameMarksTheFieldAndStoresNothing() {
        PromptLibraryDialog dialog = openTemplateEditor();
        int before = this.presetService.userTemplates().size();

        test(button(dialog, "Save template")).click();
        roundTrip();

        TextField nameField = nameField(dialog);
        assertThat(nameField.isInvalid()).isTrue();
        assertThat(nameField.getErrorMessage()).isEqualTo("Name is required");
        assertThat(this.presetService.userTemplates()).hasSize(before);
    }

    @Test
    void savingATemplateStoresItThroughTheService() {
        PromptLibraryDialog dialog = openTemplateEditor();
        String displayName = "Browserless template";
        test(nameField(dialog)).setValue(displayName);
        test($(TextArea.class, dialog).withCondition(area -> area.getLabel() != null
                && area.getLabel().startsWith("Prompt")).single()).setValue("Answer about {{topic}}.");

        test(button(dialog, "Save template")).click();
        roundTrip();

        Optional<Preset> saved = this.presetService.userTemplates().stream()
                .filter(preset -> displayName.equals(preset.displayName())).findFirst();
        try {
            assertThat(saved).isPresent();
            assertThat(saved.get().prompt()).isEqualTo("Answer about {{topic}}.");
        } finally {
            saved.ifPresent(preset -> this.presetService.delete(preset.id()));
        }
    }

    private PromptLibraryDialog openTemplateEditor() {
        PromptLibraryDialog dialog = new PromptLibraryDialog(this.presetService, this.templateRenderer,
                preset -> {}, name -> ToolSpecService.ToolReadiness.READY,
                this.toolSpecPersistenceService.getDefaultToolSpecs().stream()
                        .filter(spec -> spec.name() != null).toList(),
                this.toolSpecService::riskLevelOf, this.toolSpecService::categoryOf);
        dialog.open();
        roundTrip();
        test(button(dialog, "New template")).click();
        roundTrip();
        return dialog;
    }

    private Button button(PromptLibraryDialog dialog, String text) {
        return $(Button.class, dialog).withCondition(candidate -> text.equals(candidate.getText())).single();
    }

    private TextField nameField(PromptLibraryDialog dialog) {
        return $(TextField.class, dialog).withCondition(field -> "Name".equals(field.getLabel())).single();
    }

    private HorizontalLayout presetRow(PromptLibraryDialog dialog, String displayName) {
        return $(Span.class, dialog)
                .withCondition(span -> displayName.equals(span.getText()))
                .all().stream()
                .map(Component::getParent)
                .flatMap(Optional::stream)
                .filter(HorizontalLayout.class::isInstance)
                .map(HorizontalLayout.class::cast)
                .filter(layout -> "pointer".equals(layout.getStyle().get("cursor")))
                .findFirst().orElseThrow();
    }

}
