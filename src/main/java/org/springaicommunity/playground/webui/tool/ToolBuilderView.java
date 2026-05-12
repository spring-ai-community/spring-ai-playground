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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.tool.ChipListBinding;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolCategoryCatalog;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.springaicommunity.playground.webui.tool.ToolStudioView.TOOL_CHANGE_EVENT;

public class ToolBuilderView extends VerticalLayout {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final ToolSpec toolSpec;
    private final PropertyChangeSupport toolChangeSupport;
    private final ToolSpecService toolSpecService;
    private final ToolCategoryCatalog categoryCatalog;
    private final HorizontalLayout paramsContainer;
    private final List<ToolParameterForm> paramForms;
    private final TextField toolNameField;
    private final TextArea toolDescriptionField;
    private final ComboBox<String> categoryField;
    private final MultiSelectComboBox<String> tagsField;
    private final JavascriptToolPlaygroundView javascriptToolPlaygroundView;

    public ToolBuilderView(ToolSpec toolSpec, PropertyChangeSupport toolChangeSupport, ToolSpecService toolSpecService,
            ToolCategoryCatalog categoryCatalog, SpringAiPlaygroundOptions options,
            SandboxPostureCalculator postureCalculator, ObjectMapper objectMapper) {
        this.toolSpec = toolSpec;
        this.toolChangeSupport = toolChangeSupport;
        this.toolSpecService = toolSpecService;
        this.categoryCatalog = categoryCatalog;
        this.paramForms = new ArrayList<>();

        setSizeFull();
        setSpacing(true);
        setMargin(false);
        setPadding(false);
        getStyle()
                .set("padding-block", "var(--lumo-space-m)")
                .set("padding-inline-start", "var(--lumo-space-m)");

        this.toolNameField = new TextField("Tool Name");
        this.toolNameField.setPlaceholder(
                "Letters, numbers, and '_' only. Must start with a letter. e.g., getWeather or get_weather");
        this.toolNameField.setRequired(true);
        this.toolNameField.setWidthFull();

        this.toolNameField.addValueChangeListener(e -> {
            String value = e.getValue();

            if (value == null || value.isBlank()) {
                this.toolNameField.setInvalid(true);
                this.toolNameField.setErrorMessage("Tool Name is required.");
                return;
            }

            if (!TOOL_NAME_PATTERN.matcher(value).matches()) {
                this.toolNameField.setInvalid(true);
                this.toolNameField.setErrorMessage(
                        "Use letters, numbers, and '_' only. Must start with a letter.");
                return;
            }

            this.toolNameField.setInvalid(false);
        });

        this.toolDescriptionField = new TextArea("Tool Description");
        this.toolDescriptionField.setPlaceholder("e.g., Get the current weather for a location");
        this.toolDescriptionField.setWidthFull();

        this.categoryField = new ComboBox<>("Category");
        this.categoryField.setItems(this.categoryCatalog.categoryIds());
        this.categoryField.setItemLabelGenerator(id -> this.categoryCatalog.resolveOrFallback(id).displayName());
        this.categoryField.setValue("CUSTOM");

        this.tagsField = new MultiSelectComboBox<>("Tags");
        this.tagsField.setAllowCustomValue(true);

        Set<String> observedTags = new LinkedHashSet<>();
        for (ToolSpec existing : this.toolSpecService.getToolSpecList()) observedTags.addAll(existing.tags());
        ChipListBinding tagsBinding = new ChipListBinding(observedTags);
        tagsBinding.replaceSelected(List.of());
        this.tagsField.setItems(tagsBinding.items());
        this.tagsField.addCustomValueSetListener(e -> {
            if (tagsBinding.add(e.getDetail())) {
                this.tagsField.setItems(tagsBinding.items());
                this.tagsField.setValue(tagsBinding.selected());
            }
        });
        this.tagsField.addValueChangeListener(e -> tagsBinding.replaceSelected(e.getValue()));

        this.toolNameField.setMinWidth("0");
        this.categoryField.setMinWidth("0");
        this.tagsField.setMinWidth("0");
        this.toolNameField.getStyle().set("flex", "2 1 280px");
        this.categoryField.getStyle().set("flex", "1 1 160px");
        this.tagsField.getStyle().set("flex", "1 1 200px");
        Div topRow = new Div(this.toolNameField, this.categoryField, this.tagsField);
        topRow.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("gap", "var(--lumo-space-m)")
                .set("width", "100%")
                .set("align-items", "flex-end");

        Span paramsHint =
                new Span("Structured tool parameters defined in the tool spec and passed by the LLM at call time");
        paramsHint.getElement().getStyle().set("font-size", "0.85em").set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout paramsHeader =
                new HorizontalLayout(new H4("Tool Parameters"), paramsHint);
        paramsHeader.setAlignItems(Alignment.BASELINE);

        this.paramsContainer = new HorizontalLayout();
        paramsContainer.setPadding(false);
        paramsContainer.setSpacing(true);
        paramsContainer.setSizeFull();
        paramsContainer.getStyle().set("overflow-x", "auto");
        paramsContainer.setDefaultVerticalComponentAlignment(Alignment.START);

        VerticalLayout parametersSection = new VerticalLayout(paramsHeader, paramsContainer);
        parametersSection.setPadding(false);
        parametersSection.setSpacing(true);
        parametersSection.setWidthFull();

        this.javascriptToolPlaygroundView = new JavascriptToolPlaygroundView(objectMapper, toolSpecService, options,
                postureCalculator, () -> getCurrentToolParamsAsOpt().orElseGet(List::of));
        this.javascriptToolPlaygroundView.setHeightFull();

        VerticalLayout scrollArea = new VerticalLayout(topRow, toolDescriptionField,
                parametersSection, javascriptToolPlaygroundView);
        scrollArea.setPadding(false);
        scrollArea.setSpacing(true);
        scrollArea.setSizeFull();
        scrollArea.getStyle()
                .set("overflow-y", "auto")
                .set("overflow-x", "hidden")
                .set("min-width", "0")
                .set("padding-inline-end", "var(--lumo-space-m)");

        Button registerToolButton = new Button("Test & Update Tool", e -> testAndUpdateTool(toolNameField,
                toolDescriptionField));
        registerToolButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout bottomMenuLayout = new HorizontalLayout(registerToolButton);
        bottomMenuLayout.setDefaultVerticalComponentAlignment(Alignment.START);
        bottomMenuLayout.setPadding(false);
        bottomMenuLayout.setSpacing(true);
        bottomMenuLayout.setWidthFull();
        bottomMenuLayout.setHeight("95px");
        bottomMenuLayout.getStyle().set("padding-inline-end", "var(--lumo-space-m)");

        add(scrollArea, bottomMenuLayout);

        if (Objects.nonNull(toolSpec)) {
            this.toolNameField.setValue(toolSpec.name());
            toolDescriptionField.setValue(toolSpec.description());

            String resolvedCategory = this.categoryCatalog.resolveOrFallback(toolSpec.category()).id();
            this.categoryField.setValue(resolvedCategory);
            this.tagsField.setValue(toolSpec.tags() == null ? Set.of() : toolSpec.tags());
            if (!toolSpec.params().isEmpty()) {
                toolSpec.params().forEach(param -> addParameterForm(paramForms.size() + 1).updateFields(param));
                this.javascriptToolPlaygroundView.updateContents(toolSpec.staticVariables(), toolSpec.code());
            }
        } else {
            addDefaultParameterForm();
        }
    }

    private void addDefaultParameterForm() {
        addParameterForm(1);
    }

    private void addParameterForm() {
        addParameterForm(paramForms.size() + 1);
    }

    private ToolParameterForm addParameterForm(int index) {
        ToolParamSpec toolParamSpec = new ToolParamSpec("", "", false, JsonSchemaType.STRING, "");
        ToolParameterForm toolParameterForm =
                new ToolParameterForm(toolParamSpec, this::removeParameterForm, this::addParameterForm, index);

        toolParameterForm.setWidth("320px");
        toolParameterForm.setMinWidth("320px");

        paramForms.add(toolParameterForm);
        paramsContainer.add(toolParameterForm);
        updateParameterFormButtons();
        return toolParameterForm;
    }

    private void removeParameterForm(ToolParameterForm form) {
        paramsContainer.remove(form);
        paramForms.remove(form);
        for (int i = 0; i < paramForms.size(); i++) {
            paramForms.get(i).updateIndex(i + 1);
        }
        updateParameterFormButtons();
    }

    private void updateParameterFormButtons() {
        boolean allowDeletion = paramForms.size() > 1;
        for (int i = 0; i < paramForms.size(); i++) {
            ToolParameterForm currentForm = paramForms.get(i);
            boolean isLast = (i == paramForms.size() - 1);
            currentForm.setAddButtonVisible(isLast);
            currentForm.setDeleteButtonVisible(allowDeletion);
        }
    }

    private void testAndUpdateTool(TextField nameField, TextArea descriptionField) {
        String toolName = nameField.getValue();

        if (this.toolNameField.isInvalid()) {
            VaadinUtils.showErrorNotification("Please fix the Tool Name.");
            this.toolNameField.focus();
            return;
        }
        if (Objects.isNull(this.toolSpec) && toolSpecService.getToolSpecAsOpt(toolName).isPresent()) {
            VaadinUtils.showErrorNotification("A tool with this name already exists.");
            this.toolNameField.focus();
            return;
        }

        String toolDescription = descriptionField.getValue();
        getCurrentToolParamsAsOpt().filter(toolParamSpecs -> this.javascriptToolPlaygroundView.runTest())
                .ifPresent(toolParamSpecs -> {
                    String toolId = Optional.ofNullable(this.toolSpec).map(ToolSpec::toolId)
                            .orElseGet(() -> UUID.randomUUID().toString());

                    ToolSpec formSpec = new ToolSpec(toolId, toolName, toolDescription,
                            this.javascriptToolPlaygroundView.getStaticVariables(), toolParamSpecs,
                            this.javascriptToolPlaygroundView.getCurrentJsCode(), ToolSpec.CodeType.Javascript, null)
                            .withCategory(this.categoryField.getValue())
                            .withTags(this.tagsField.getValue() == null ? Set.of() : this.tagsField.getValue())
                            .withSandboxOverrides(this.javascriptToolPlaygroundView.currentSandboxOverrides());
                    ToolSpec registeredToolSpec = toolSpecService.update(formSpec);
                    VaadinUtils.showInfoNotification("Tool '" + toolName + "' registered successfully!");
                    this.toolChangeSupport.firePropertyChange(TOOL_CHANGE_EVENT, this.toolSpec, registeredToolSpec);
                });
    }

    public Optional<List<ToolParamSpec>> getCurrentToolParamsAsOpt() {
        int size = paramForms.size();
        List<ToolParamSpec> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ToolParamSpec spec = paramForms.get(i).getToolParamSpec();
            if (size == 1 && spec == null) {
                return Optional.of(result);
            }
            if (spec == null) {
                VaadinUtils.showErrorNotification("Parameter #" + (i + 1) + " is invalid");
                return Optional.empty();
            }
            result.add(spec);
        }
        return Optional.of(result);
    }

    public Optional<String> getCurrentToolSpecJsonAsOpt() {
        return getCurrentToolParamsAsOpt().map(toolParamSpecs -> {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("name", Optional.ofNullable(toolSpec).map(ToolSpec::name).orElse(""));
            schema.put("description", Optional.ofNullable(toolSpec).map(ToolSpec::description).orElse(""));
            schema.putIfAbsent("parameters", this.toolSpecService.toJsonSchema(toolParamSpecs));
            return schema.toPrettyString();
        });
    }

    public ToolSpec copyCurrentToolSpec() {
        return new ToolSpec(null, this.toolNameField.getValue() + "_COPY",
                this.toolDescriptionField.getValue(), this.javascriptToolPlaygroundView.getStaticVariables(),
                this.getCurrentToolParamsAsOpt().orElseGet(List::of),
                this.javascriptToolPlaygroundView.getCurrentJsCode(), ToolSpec.CodeType.Javascript, null);
    }

}