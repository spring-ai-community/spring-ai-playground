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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hilerio.ace.AceEditor;
import com.hilerio.ace.AceMode;
import com.hilerio.ace.AceTheme;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.PendingJavaScriptResult;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextArea;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.JsSandbox;
import org.springaicommunity.playground.service.tool.ChipListBinding;
import org.springaicommunity.playground.service.tool.JsToolExecutor.JsExecutionResult;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator.Inputs;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@JavaScript("./prettier-standalone.js")
@JavaScript("./prettier-plugin-babel.js")
@JavaScript("./prettier-plugin-estree.js")
public class JavascriptToolPlaygroundView extends VerticalLayout {

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        attachEvent.getUI().getPage().executeJs("""
                window.PRETTIER_DEFAULT_OPTIONS = {
                  parser: "babel",
                  printWidth: 200,
                  tabWidth: 2,
                  semi: true,
                  singleQuote: true,
                  trailingComma: "es5",
                  proseWrap: "never"
                };
                """);
    }

    private final FlexLayout staticVariableContainer;
    private final List<StaticVariableForm> staticVariableForms;
    private final AceEditor ace;
    private final TextArea consoleTextArea;
    private final HorizontalLayout consoleStatusRow;
    private final Span consoleStatusBadge;
    private final Span consoleTimingBadge;
    private final Button testRunButton;
    private final Button formatButton;
    private final ObjectMapper objectMapper;
    private final ToolSpecService toolSpecService;
    private final SpringAiPlaygroundOptions options;
    private final SandboxPostureCalculator postureCalculator;
    private final Supplier<List<ToolParamSpec>> currentToolParamsSupplier;

    // Sandbox controls — kept as fields so the live posture badge can react to changes
    private RadioButtonGroup<String> networkModeField;
    private MultiSelectComboBox<String> hostsField;
    private MultiSelectComboBox<String> allowClassesField;
    private MultiSelectComboBox<String> denyClassesField;
    private Span postureBadge;
    private Icon postureIcon;
    private Set<String> yamlBaselineDeny = Set.of();
    private Set<String> yamlBaselineAllow = Set.of();

    public JavascriptToolPlaygroundView(ObjectMapper objectMapper, ToolSpecService toolSpecService,
            SpringAiPlaygroundOptions options, SandboxPostureCalculator postureCalculator,
            Supplier<List<ToolParamSpec>> currentToolParamsSupplier) {
        this.objectMapper = objectMapper;
        this.toolSpecService = toolSpecService;
        this.options = options;
        this.postureCalculator = postureCalculator;
        this.currentToolParamsSupplier = currentToolParamsSupplier;
        this.staticVariableForms = new ArrayList<>();

        setSizeFull();
        setPadding(false);
        setSpacing(true);

        ace = new AceEditor();
        ace.setMinlines(35);
        ace.setSizeFull();
        ace.setTheme(AceTheme.monokai);
        ace.setMode(AceMode.javascript);
        ace.setAutoComplete(true);
        ace.setLiveAutocompletion(true);
        ace.setUseWorker(true);
        ace.setShowInvisibles(true);
        ace.setTabSize(2);
        ace.setWrap(true);
        ace.setShowPrintMargin(false);
        ace.setPlaceholder("Type JavaScript code here…");
        String exampleJs = """
                /**
                 * NOTE TO DEVELOPERS:
                 * This code runs on JavaScript (ECMAScript 2023) inside the JVM using GraalJS.
                 * It is NOT a browser or Node.js environment.
                 *
                 * Unavailable APIs:
                 * - Browser APIs: fetch, XMLHttpRequest, DOM (window/document), timers, etc.
                 * - Node.js APIs: require(), modules, process, built-in modules, etc.
                 *
                 * Available features:
                 * - Java interop via Java.type() is restricted to a safe allowlist (see application.yml)
                 * - Common utilities and HTTP client classes are allowed (e.g. java.util.*, java.net.http.HttpClient, ...)
                 * - Dangerous operations (file I/O, system commands, reflection, etc.) are completely blocked
                 * - console.log output is captured and displayed in the Debug Console below
                 *
                 * Execution model:
                 * - Your script is executed in a sandboxed environment with strict security restrictions
                 * - The script runs fresh on every call (stateless)
                 * - You MUST return a value — this becomes the tool result returned to the agent
                 */
                
                /* ===== Example: Hello World — delete and replace ===== */
                const Instant = Java.type('java.time.Instant');
                
                return {
                  message: 'Hello World from Spring AI Playground 👋',
                  timestamp: new Date().toISOString(),
                  epochMilli: Instant.now().toEpochMilli(),
                  note: 'You can access allow Java classes, configured safely in application.yml!',
                };
                """;
        ace.setValue(exampleJs);
        ace.getElement().getStyle()
                .set("min-height", "400px")
                .set("font-size", "1rem");

        consoleTextArea = new TextArea();
        consoleTextArea.setReadOnly(true);
        consoleTextArea.setWidthFull();
        consoleTextArea.addClassName("debug-console");
        consoleTextArea.setPlaceholder("No run yet. Click Test Run to execute the JS code and see output here.");
        consoleTextArea.getStyle().set("flex-grow", "1");
        consoleTextArea.getElement().getStyle().set("min-height", "300px");

        this.consoleStatusBadge = new Span();
        this.consoleTimingBadge = new Span();
        this.consoleTimingBadge.getElement().setAttribute("theme", "badge contrast pill small");
        this.consoleStatusRow = new HorizontalLayout(this.consoleStatusBadge, this.consoleTimingBadge);
        this.consoleStatusRow.setSpacing(false);
        this.consoleStatusRow.getStyle().set("gap", "0.4em");
        this.consoleStatusRow.setAlignItems(HorizontalLayout.Alignment.CENTER);
        this.consoleStatusRow.setVisible(false);

        VerticalLayout bottomContainer = new VerticalLayout();
        bottomContainer.setPadding(false);
        bottomContainer.setSpacing(true);
        bottomContainer.setWidthFull();
        HorizontalLayout consoleHeader = new HorizontalLayout(new H5("Debug Console"), this.consoleStatusRow);
        consoleHeader.setAlignItems(HorizontalLayout.Alignment.BASELINE);
        consoleHeader.setSpacing(false);
        consoleHeader.getStyle().set("gap", "var(--lumo-space-m)");
        bottomContainer.add(consoleHeader, consoleTextArea);
        bottomContainer.getStyle().set("display", "flex").set("flex-direction", "column");

        this.testRunButton = new Button("Test Run", VaadinIcon.PLAY.create(), e -> runTestJavascript());
        Button clearButton = new Button("Clear", VaadinIcon.ERASER.create(), e -> ace.clear());
        this.formatButton = new Button("Format", VaadinIcon.MAGIC.create(), e -> formatCodeWithPrettier());
        getElement().executeJs("""
                window.waitForPrettier = new Promise((resolve) => {
                    const check = setInterval(() => {
                        if (window.prettier && window.prettierPlugins) {
                            clearInterval(check);
                            resolve();
                        }
                    }, 50);
                });
                """);

        Span staticHint = new Span(
                "Optional key–value pairs available in your code as global variables, injected into the action context");
        staticHint.getElement().getStyle().set("font-size", "0.85em").set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout staticVarsHeader =
                new HorizontalLayout(new H5("Static Variables"), staticHint);
        staticVarsHeader.setAlignItems(Alignment.BASELINE);

        this.staticVariableContainer = new FlexLayout();
        staticVariableContainer.setFlexDirection(FlexLayout.FlexDirection.COLUMN);
        staticVariableContainer.setWidthFull();

        VerticalLayout staticVarsSection = new VerticalLayout(staticVarsHeader, staticVariableContainer);
        staticVarsSection.setPadding(false);
        staticVarsSection.setSpacing(true);
        staticVarsSection.setWidthFull();

        Details sandboxSection = buildSandboxDetails();

        HorizontalLayout actionBar = new HorizontalLayout(testRunButton, clearButton, formatButton);

        actionBar.setPadding(false);
        actionBar.setSpacing(false);
        actionBar.getStyle().set("gap", "var(--lumo-space-s)");
        actionBar.setWidthFull();

        add(new H4("Tool Action"), sandboxSection, staticVarsSection, new H5("JS Code Editor"), ace, actionBar, bottomContainer);
        setFlexGrow(1, ace);
        setFlexGrow(1, bottomContainer);
        addDefaultStaticVariableForm();
    }

    private Details buildSandboxDetails() {
        JsSandbox baseline = this.options.toolStudio() != null
                ? this.options.toolStudio().jsSandbox() : null;
        this.yamlBaselineAllow = baseline != null && baseline.allowClasses() != null
                ? baseline.allowClasses() : Set.of();
        this.yamlBaselineDeny = baseline != null && baseline.denyClasses() != null
                ? baseline.denyClasses() : Set.of();

        this.allowClassesField = chipListField("Allow Classes",
                "Java APIs this tool can use. Removing tightens. Adding a new class can raise risk (L3+).",
                this.yamlBaselineAllow);

        this.denyClassesField = chipListField("Deny Classes",
                "Always blocked. Removing reflection denies → L4. Removing System / Runtime / Process → L5.",
                this.yamlBaselineDeny);

        // Built-in fetch() only. Java HttpClient is gated by Allow Classes instead.
        this.networkModeField = new RadioButtonGroup<>();
        this.networkModeField.setLabel("Built-in fetch() egress");
        this.networkModeField.setItems("blocked", "allowlist", "open");
        this.networkModeField.setValue("blocked");
        this.networkModeField.setHelperText(
                "Built-in fetch() only. Java HttpClient is governed by Allow Classes. "
                        + "blocked = no network · allowlist = listed hosts (L3) · open = any host (L4).");

        this.hostsField = new MultiSelectComboBox<>("Allowed Hosts");
        this.hostsField.setAllowCustomValue(true);
        this.hostsField.setWidthFull();
        this.hostsField.addCustomValueSetListener(e -> {
            Set<String> next = new java.util.LinkedHashSet<>(this.hostsField.getValue());
            next.add(e.getDetail().trim());
            this.hostsField.setValue(next);
        });

        this.networkModeField.addValueChangeListener(e -> {
            this.hostsField.setVisible("allowlist".equals(e.getValue()));
            updatePostureBadge();
        });
        this.hostsField.addValueChangeListener(e -> updatePostureBadge());
        this.allowClassesField.addValueChangeListener(e -> updatePostureBadge());
        this.denyClassesField.addValueChangeListener(e -> updatePostureBadge());
        this.hostsField.setVisible(false);

        VerticalLayout body = new VerticalLayout(
                this.allowClassesField,
                this.denyClassesField,
                this.networkModeField,
                this.hostsField);
        body.setPadding(false);
        body.setSpacing(true);
        body.setWidthFull();

        Span title = new Span("Sandbox & Capabilities");
        title.getStyle().set("font-weight", "var(--lumo-font-weight-semibold)");

        Span separator = new Span("·");
        separator.getStyle()
                .set("color", "var(--lumo-tertiary-text-color)")
                .set("margin", "0 0.6em");

        this.postureIcon = VaadinIcon.LOCK.create();
        this.postureIcon.setSize("1.1em");

        this.postureBadge = new Span();
        this.postureBadge.getStyle().set("margin-inline-start", "0.35em");

        HorizontalLayout summary = new HorizontalLayout(title, separator, this.postureIcon, this.postureBadge);
        summary.setAlignItems(HorizontalLayout.Alignment.CENTER);
        summary.setSpacing(false);

        updatePostureBadge();  // initial paint

        Details details = new Details(summary, body);
        details.setOpened(false);  // collapsed by default — posture visible from header alone
        details.setWidthFull();
        return details;
    }

    private static MultiSelectComboBox<String> chipListField(String label, String helper, Set<String> baseline) {
        MultiSelectComboBox<String> field = new MultiSelectComboBox<>(label);
        field.setHelperText(helper);
        field.setWidthFull();
        field.setAllowCustomValue(true);

        ChipListBinding binding = new ChipListBinding(baseline);
        field.setItems(binding.items());
        field.setValue(binding.selected());

        field.addCustomValueSetListener(e -> {
            if (binding.add(e.getDetail())) {
                field.setItems(binding.items());
                field.setValue(binding.selected());
            }
        });
        field.addValueChangeListener(e -> binding.replaceSelected(e.getValue()));
        return field;
    }

    private void updatePostureBadge() {
        if (this.postureBadge == null) return;
        RiskLevel level = computeRisk();
        this.postureBadge.setText(postureLabel(level) + "  " + level.name());
        String theme = switch (level) {
            case L0, L1 -> "badge success primary small";
            case L2     -> "badge contrast small";
            case L3     -> "badge contrast primary small";
            case L4     -> "badge warning primary small";
            case L5     -> "badge error primary small";
        };
        this.postureBadge.getElement().setAttribute("theme", theme);
        VaadinIcon iconKind = switch (level) {
            case L0, L1, L2 -> VaadinIcon.LOCK;
            case L3         -> VaadinIcon.SHIELD;
            case L4         -> VaadinIcon.UNLOCK;
            case L5         -> VaadinIcon.WARNING;
        };
        String iconColour = switch (level) {
            case L0, L1 -> "var(--lumo-success-color)";
            case L2, L3 -> "var(--lumo-primary-color)";
            case L4     -> "#f0a500";
            case L5     -> "var(--lumo-error-color)";
        };
        this.postureIcon.getElement().setAttribute("icon", "vaadin:" + iconKind.name().toLowerCase().replace('_', '-'));
        this.postureIcon.getStyle().set("color", iconColour);
    }

    private RiskLevel computeRisk() {
        String mode = this.networkModeField == null ? "blocked" : this.networkModeField.getValue();
        Set<String> hosts = this.hostsField == null ? Set.of() : this.hostsField.getValue();
        Set<String> userDeny = this.denyClassesField == null ? Set.of() : this.denyClassesField.getValue();
        Set<String> userAllow = this.allowClassesField == null ? Set.of() : this.allowClassesField.getValue();
        return this.postureCalculator.compute(
                new Inputs(mode, hosts, this.yamlBaselineDeny, userDeny, this.yamlBaselineAllow, userAllow));
    }

    private static String postureLabel(RiskLevel level) {
        return switch (level) {
            case L0 -> "Locked";
            case L1 -> "Locked + clock";
            case L2 -> "Workspace";
            case L3 -> "Network allowlist";
            case L4 -> "Open network";
            case L5 -> "Side effects";
        };
    }

    private void addDefaultStaticVariableForm() {
        addStaticVariableForm(1);
    }

    private StaticVariableForm addStaticVariableForm(int index) {
        StaticVariableForm staticVariableForm = new StaticVariableForm(index, this::removeStaticVariableForm,
                () -> addStaticVariableForm(staticVariableForms.size() + 1));
        staticVariableForms.add(staticVariableForm);
        staticVariableContainer.add(staticVariableForm);
        updateStaticVariableButtons();
        return staticVariableForm;
    }

    private void removeStaticVariableForm(StaticVariableForm form) {
        if (staticVariableForms.size() <= 1) {
            return;
        }

        staticVariableContainer.remove(form);
        staticVariableForms.remove(form);

        for (int i = 0; i < staticVariableForms.size(); i++) {
            staticVariableForms.get(i).updateIndex(i + 1);
        }

        updateStaticVariableButtons();
    }

    private void updateStaticVariableButtons() {
        int size = staticVariableForms.size();
        boolean hasMultiple = size > 1;
        for (int i = 0; i < size; i++) {
            StaticVariableForm form = staticVariableForms.get(i);
            boolean isLast = (i == size - 1);
            form.setAddButtonVisible(isLast);
            form.setDeleteButtonVisible(hasMultiple);
        }
    }

    public List<Map.Entry<String, String>> getStaticVariables() {
        return staticVariableForms.stream().map(staticVariableForm -> {
            String key = staticVariableForm.getKey().trim();
            return !key.isBlank() ? Map.entry(key, staticVariableForm.getValue()) : null;
        }).filter(Objects::nonNull).toList();
    }

    public ToolSpec.SandboxOverrides currentSandboxOverrides() {
        Set<String> userAllow = this.allowClassesField == null ? Set.of() : this.allowClassesField.getValue();
        Set<String> userDeny = this.denyClassesField == null ? Set.of() : this.denyClassesField.getValue();
        Set<String> addAllow = diff(userAllow, this.yamlBaselineAllow);
        Set<String> removeAllow = diff(this.yamlBaselineAllow, userAllow);
        Set<String> addDeny = diff(userDeny, this.yamlBaselineDeny);
        Set<String> removeDeny = diff(this.yamlBaselineDeny, userDeny);
        String networkMode = this.networkModeField == null ? null : this.networkModeField.getValue();
        Set<String> hosts = this.hostsField == null ? Set.of() : this.hostsField.getValue();
        return new ToolSpec.SandboxOverrides(addAllow, removeAllow, addDeny, removeDeny, networkMode, hosts);
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a == null ? Set.of() : a);
        if (b != null) out.removeAll(b);
        return out;
    }

    private void formatCodeWithPrettier() {
        formatButton.setEnabled(false);
        String code = ace.getValue();

        PendingJavaScriptResult result = ace.getElement().executeJs("""
                return (async () => {
                    try {
                        const formatted = await prettier.format($0, {
                            parser: "babel",
                            plugins: prettierPlugins,
                            semi: true,
                            trailingComma: "es5",
                            singleQuote: true,
                            printWidth: 80,
                            tabWidth: 2,
                            bracketSpacing: true
                        });
                        return formatted;
                    } catch (err) {
                        return "ERROR: " + err.message;
                    }
                })();
                """, code);

        result.then(String.class,
                formatted -> UI.getCurrent().access(() -> {
                    if (formatted.startsWith("ERROR:")) {
                        VaadinUtils.showErrorNotification("Formatting Failure: " + formatted);
                    } else {
                        ace.setValue(formatted);
                        ace.focus();
                    }
                    formatButton.setEnabled(true);
                }),
                error -> UI.getCurrent().access(() -> {
                    VaadinUtils.showErrorNotification("JS Error: " + error);
                    formatButton.setEnabled(true);
                })
        );
    }

    private Object convertValueForType(Object testValue, JsonSchemaType type) throws JsonProcessingException {
        if (Objects.nonNull(testValue) && testValue instanceof String strValue && !strValue.isBlank()) {
            return switch (type) {
                case STRING -> strValue;
                case NUMBER -> Double.parseDouble(strValue);
                case INTEGER -> Long.parseLong(strValue);
                case BOOLEAN -> Boolean.parseBoolean(strValue);
                case ARRAY -> this.objectMapper.readValue(strValue,
                        new TypeReference<List<Object>>() {});
                case OBJECT -> this.objectMapper.readValue(strValue,
                        new TypeReference<Map<String, Object>>() {});
            };
        }
        return testValue;
    }

    public boolean runTest() {
        return this.testRunButton.isEnabled() && runTestJavascript();
    }

    private boolean runTestJavascript() {
        this.testRunButton.setEnabled(false);
        consoleTextArea.clear();
        this.consoleStatusRow.setVisible(false);
        long start = System.currentTimeMillis();

        try {
            LinkedHashMap<String, Object> toolParams = new LinkedHashMap<>();
            for (ToolParamSpec spec : this.currentToolParamsSupplier.get()) {
                toolParams.put(spec.name(), convertValueForType(spec.testValue(), spec.type()));
            }
            JsExecutionResult jsExecutionResult =
                    toolSpecService.executeTool("", getStaticVariables(), getCurrentJsCode(),
                            toolParams, currentSandboxOverrides());
            VaadinUtils.getUi(this).access(() -> {
                long end = System.currentTimeMillis();
                consoleTextArea.setValue(buildResultString(jsExecutionResult));
                updateConsoleStatusRow(jsExecutionResult.isOk(), start, end);
                if (!jsExecutionResult.isOk()) {
                    VaadinUtils.showErrorNotification("Error Details:\n" + jsExecutionResult.error());
                }
            });
            return jsExecutionResult.isOk();
        } catch (Exception ex) {
            VaadinUtils.showErrorNotification("Exception: " + ex.getMessage());
            return false;
        } finally {
            this.testRunButton.setEnabled(true);
        }
    }

    private static String buildResultString(JsExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.debugInfo()).append("\n");
        if (result.isOk()) {
            sb.append("\nResult:\n").append(result.result());
        } else {
            sb.append("\nError Details:\n").append(result.error());
        }
        return sb.toString();
    }

    private void updateConsoleStatusRow(boolean ok, long startMs, long endMs) {
        this.consoleStatusBadge.setText(ok ? "✓ Success" : "✗ Error");
        this.consoleStatusBadge.getElement().setAttribute(
                "theme", ok ? "badge success pill small" : "badge error pill small");
        this.consoleTimingBadge.setText(formatDuration(endMs - startMs));
        this.consoleTimingBadge.getElement().setAttribute("title",
                "Started " + formatTs(startMs) + "  →  ended " + formatTs(endMs));
        this.consoleStatusRow.setVisible(true);
    }

    private String formatTs(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }

    private String formatDuration(long ms) {
        return ms + " ms";
    }

    public String getCurrentJsCode() {
        return ace.getValue();
    }

    public void updateContents(List<Map.Entry<String, String>> staticVariables, String code) {
        this.staticVariableForms.clear();
        this.staticVariableContainer.removeAll();
        if (!staticVariables.isEmpty()) {
            for (int i = 0; i < staticVariables.size(); i++)
                addStaticVariableForm(i).update(staticVariables.get(i));
        } else {
            addDefaultStaticVariableForm();
        }
        this.ace.setValue(code);
    }
}