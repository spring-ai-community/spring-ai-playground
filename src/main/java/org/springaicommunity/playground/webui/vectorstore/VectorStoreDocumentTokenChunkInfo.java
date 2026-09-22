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

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.TokenTextSplitInfo;

import static org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.DEFAULT_TOKEN_TEXT_SPLIT_INFO;

public class VectorStoreDocumentTokenChunkInfo extends VerticalLayout {

    private final NumberField chunkSizeField;
    private final NumberField minChunkSizeCharsField;
    private final NumberField minChunkLengthToEmbedField;
    private final NumberField maxNumChunksField;
    private final Checkbox keepSeparatorCheckbox;
    private final ComboBox<String> encodingTypeCombo;

    public VectorStoreDocumentTokenChunkInfo() {
        setMargin(false);
        setPadding(false);
        setSpacing(false);
        setWidthFull();

        Span title = new Span("Token Text Splitter Settings");
        title.getStyle().set("display", "block").set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-body-text-color)")
                .set("margin", "var(--lumo-space-m) 0 var(--lumo-space-xs) 0");
        add(title);

        chunkSizeField = createValidatedField("Chunk Size", DEFAULT_TOKEN_TEXT_SPLIT_INFO.chunkSize(), 1, 10000,
                "Enter 1~10,000.");
        minChunkSizeCharsField =
                createValidatedField("Min Chunk Size Chars", DEFAULT_TOKEN_TEXT_SPLIT_INFO.minChunkSizeChars(), 1,
                        10000, "Enter 1~10,000.");
        minChunkLengthToEmbedField =
                createValidatedField("Min Chunk Length To Embed", DEFAULT_TOKEN_TEXT_SPLIT_INFO.minChunkLengthToEmbed(),
                        1, 10000, "Enter 1~10,000.");
        maxNumChunksField =
                createValidatedField("Max Num Chunks", DEFAULT_TOKEN_TEXT_SPLIT_INFO.maxNumChunks(), 1, 10000,
                        "Enter 1~10,000.");

        keepSeparatorCheckbox = new Checkbox("Keep Separator");
        keepSeparatorCheckbox.setValue(DEFAULT_TOKEN_TEXT_SPLIT_INFO.keepSeparator());

        encodingTypeCombo = new ComboBox<>("Encoding Type");
        encodingTypeCombo.setItems(OfflineEtlPipelineService.encodingTypeNames());
        encodingTypeCombo.setValue(DEFAULT_TOKEN_TEXT_SPLIT_INFO.encodingType());
        encodingTypeCombo.setWidthFull();
        encodingTypeCombo.setHelperText("Tokenizer used to count chunk tokens (CL100K_BASE fits most models).");

        Div paramGrid = new Div(chunkSizeField, minChunkSizeCharsField, minChunkLengthToEmbedField, maxNumChunksField);
        paramGrid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "1fr 1fr")
                .set("gap", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("width", "100%")
                .set("padding", "var(--lumo-space-xs) 0 var(--lumo-space-s) 0");

        HorizontalLayout keepSeparatorLayout = new HorizontalLayout(encodingTypeCombo, keepSeparatorCheckbox);
        keepSeparatorLayout.setWidthFull();
        keepSeparatorLayout.setAlignItems(FlexComponent.Alignment.BASELINE);
        keepSeparatorLayout.setJustifyContentMode(FlexComponent.JustifyContentMode.START);
        keepSeparatorLayout.getStyle().set("padding-bottom", "var(--lumo-space-m)");

        add(paramGrid, keepSeparatorLayout);
    }

    private NumberField createValidatedField(String label, double defaultVal, double min, double max, String errorMsg) {
        NumberField field = new NumberField(label);
        field.setValue(defaultVal);
        field.setStep(1);
        field.setMin(min);
        field.setMax(max);
        field.setWidthFull();
        field.setRequiredIndicatorVisible(true);
        field.addValueChangeListener(ev -> {
            Double v = ev.getValue();
            boolean invalid = v == null || v < min || v > max;
            field.setInvalid(invalid);
            field.setErrorMessage(invalid ? errorMsg : null);
        });
        return field;
    }

    private boolean validateAllFields() {
        boolean valid = true;
        valid &= !chunkSizeField.isInvalid();
        valid &= !minChunkSizeCharsField.isInvalid();
        valid &= !minChunkLengthToEmbedField.isInvalid();
        valid &= !maxNumChunksField.isInvalid();

        valid &= chunkSizeField.getValue() != null;
        valid &= minChunkSizeCharsField.getValue() != null;
        valid &= minChunkLengthToEmbedField.getValue() != null;
        valid &= maxNumChunksField.getValue() != null;

        return valid;
    }

    public TokenTextSplitInfo collectInput() {
        if (!validateAllFields()) {
            throw new IllegalStateException("Some fields are invalid or empty");
        }
        return new TokenTextSplitInfo(chunkSizeField.getValue().intValue(),
                minChunkSizeCharsField.getValue().intValue(), minChunkLengthToEmbedField.getValue().intValue(),
                maxNumChunksField.getValue().intValue(),
                keepSeparatorCheckbox.getValue(),
                encodingTypeCombo.getValue()
        );
    }
}
