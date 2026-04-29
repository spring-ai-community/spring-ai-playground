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
package org.springaicommunity.playground.webui.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@CssImport("./playground/mcp-inspector-styles.css")
public class McpServerInspectorView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String ARG_REQUIRED = "Required field";

    private static final ObjectMapper INSPECTOR_OBJECT_MAPPER = new ObjectMapper();

    public record ToolInfo(
            String displayTitle,
            String name,
            String description,
            McpSchema.ToolAnnotations annotations,
            List<String> required,
            Map<String, Map<String, Object>> propertySchemas) {}

    private final McpServerInfo serverInfo;
    private final McpClientService clientService;
    private final List<ToolInfo> allTools;
    private final List<ToolCard> cards = new ArrayList<>();
    private final VerticalLayout cardsContainer = new VerticalLayout();

    public McpServerInspectorView(McpServerInfo serverInfo, McpClientService clientService,
            List<McpSchema.Tool> tools) {
        this.serverInfo = Objects.requireNonNull(serverInfo);
        this.clientService = Objects.requireNonNull(clientService);
        this.allTools = tools.stream().map(this::toToolInfo).toList();

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        cardsContainer.setPadding(false);
        cardsContainer.setSpacing(false);
        cardsContainer.setWidthFull();
        cardsContainer.getStyle().set("padding", "0 1em 1em");
        for (int i = 0; i < allTools.size(); i++) {
            ToolCard card = new ToolCard(allTools.get(i), i + 1);
            cards.add(card);
            cardsContainer.add(card);
        }

        add(createHeader(), createTabs(), createToolbar(), cardsContainer);
    }

    private Component createHeader() {
        H4 logo = new H4("MCP Inspector");
        logo.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");

        HorizontalLayout headLayout = new HorizontalLayout(logo);
        headLayout.setWidthFull();
        headLayout.setPadding(true);
        headLayout.setAlignItems(FlexComponent.Alignment.CENTER);
        headLayout.setJustifyContentMode(FlexComponent.JustifyContentMode.START);
        return headLayout;
    }

    private Tabs createTabs() {
        Tabs tabs = new Tabs(
                tab(VaadinIcon.TOOLS, "Tools")
//                , tab(VaadinIcon.DATABASE, "Resources"),
//                tab(VaadinIcon.CLIPBOARD_TEXT, "Prompts"),
//                tab(VaadinIcon.CLOUD_O, "Ping"),
//                tab(VaadinIcon.COG, "Roots"),
//                tab(VaadinIcon.CUBES, "Sampling")
        );
        tabs.setWidthFull();
        tabs.setSelectedIndex(0);
        return tabs;
    }

    private static Tab tab(VaadinIcon icon, String caption) {
        return new Tab(icon.create(), new Text(caption));
    }

    private Component createToolbar() {
        TextField search = new TextField();
        search.setPlaceholder("Search tools by name or description…");
        search.setClearButtonVisible(true);
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setWidth("420px");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(150);
        search.addValueChangeListener(e -> applyFilter(e.getValue()));

        Span count = new Span(allTools.size() + " tool" + (allTools.size() == 1 ? "" : "s"));
        count.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");

        HorizontalLayout bar = new HorizontalLayout(search, count);
        bar.setWidthFull();
        bar.setPadding(false);
        bar.setSpacing(true);
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle().set("padding", "0 1em 0.6em").set("gap", "0.8em");
        return bar;
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        for (ToolCard card : cards) {
            card.setVisible(card.matches(needle));
        }
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private class ToolCard extends Div {
        private final ToolInfo tool;
        private final Map<String, AbstractField<?, ?>> fields = new LinkedHashMap<>();
        private final Div inlineResultPanel = new Div();
        private Map<String, Object> lastArgs;
        private McpSchema.CallToolResult lastResult;
        private String lastError;
        private long lastElapsedMs;
        private String lastTimestamp;
        private boolean rawView = false;

        ToolCard(ToolInfo tool, int displayIndex) {
            this.tool = tool;
            setWidthFull();
            getStyle()
                    .set("box-sizing", "border-box")
                    .set("border", "1px solid var(--lumo-contrast-10pct)")
                    .set("border-radius", "var(--lumo-border-radius-m)")
                    .set("padding", "0.9em 1.1em")
                    .set("margin-bottom", "0.7em")
                    .set("background-color", "var(--lumo-base-color)");

            Button runButton = new Button(VaadinIcon.PLAY.create());
            runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ICON);
            runButton.setTooltipText("Run tool");
            runButton.addClickListener(e -> handleRun());

            Span titleSpan = new Span(pickFirstNonBlank(tool.displayTitle(), tool.name()));
            titleSpan.getStyle().set("font-weight", "600").set("font-size", "1em");

            HorizontalLayout titleRow = new HorizontalLayout(runButton, titleSpan);
            titleRow.setWidthFull();
            titleRow.setPadding(false);
            titleRow.setSpacing(true);
            titleRow.setAlignItems(FlexComponent.Alignment.CENTER);
            titleRow.getStyle().set("gap", "0.6em");
            add(titleRow);

            if (tool.displayTitle() != null && !tool.displayTitle().isBlank()
                    && !tool.displayTitle().equals(tool.name())) {
                Span nameSpan = new Span(tool.name());
                nameSpan.getStyle()
                        .set("font-family", "var(--lumo-font-family-monospace)")
                        .set("font-size", "0.8em")
                        .set("color", "var(--lumo-secondary-text-color)")
                        .set("display", "block")
                        .set("margin", "0.1em 0 0 2.4em");
                add(nameSpan);
            }

            HorizontalLayout badges = annotationBadges(tool.annotations());
            if (badges.getComponentCount() > 0) {
                badges.getStyle().set("margin", "0.3em 0 0 2.4em");
                add(badges);
            }

            if (tool.description() != null && !tool.description().isBlank()) {
                Div desc = new Div();
                desc.setText(tool.description());
                desc.getStyle()
                        .set("white-space", "pre-wrap")
                        .set("color", "var(--lumo-body-text-color)")
                        .set("font-size", "0.9em")
                        .set("line-height", "1.5")
                        .set("padding", "0.6em 0.9em")
                        .set("background-color", "var(--lumo-contrast-5pct)")
                        .set("border-left", "3px solid var(--lumo-primary-color)")
                        .set("border-radius", "0 var(--lumo-border-radius-s) var(--lumo-border-radius-s) 0")
                        .set("margin", "0.7em 0 0.3em");
                add(desc);
            }

            if (!tool.propertySchemas().isEmpty()) {
                add(sectionLabel("Parameters"));
                VerticalLayout argsLayout = new VerticalLayout();
                argsLayout.setPadding(false);
                argsLayout.setSpacing(false);
                argsLayout.getStyle().set("gap", "0.6em");
                tool.propertySchemas().forEach((key, schema) -> {
                    AbstractField<?, ?> field = buildArgField(key, schema, tool.required().contains(key));
                    fields.put(key, field);
                    argsLayout.add(field);
                });
                add(argsLayout);
            }

            inlineResultPanel.setVisible(false);
            inlineResultPanel.getStyle()
                    .set("margin-top", "0.8em")
                    .set("padding-top", "0.6em")
                    .set("border-top", "1px solid var(--lumo-contrast-10pct)");
            add(inlineResultPanel);
        }

        boolean matches(String needle) {
            if (needle.isEmpty()) return true;
            return contains(tool.name(), needle) || contains(tool.displayTitle(), needle)
                    || contains(tool.description(), needle);
        }

        private void handleRun() {
            Map<String, Object> args = new LinkedHashMap<>();
            for (var entry : fields.entrySet()) {
                String key = entry.getKey();
                AbstractField<?, ?> field = entry.getValue();
                boolean required = tool.required().contains(key);
                Object value;
                try {
                    value = readArgValue(key, field, required);
                } catch (IllegalArgumentException ex) {
                    lastArgs = args;
                    lastResult = null;
                    lastError = ex.getMessage();
                    lastElapsedMs = 0L;
                    lastTimestamp = now();
                    renderInline();
                    return;
                }
                if (value != null) args.put(key, value);
            }

            long startNs = System.nanoTime();
            try {
                Optional<McpSchema.CallToolResult> resultOpt =
                        clientService.callTool(serverInfo, tool.name(), args, null);
                lastArgs = args;
                lastResult = resultOpt.orElse(null);
                lastError = null;
                lastElapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                lastTimestamp = now();
            } catch (Exception ex) {
                lastArgs = args;
                lastResult = null;
                lastError = ex.getMessage();
                lastElapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                lastTimestamp = now();
            }
            renderInline();
        }

        private void renderInline() {
            inlineResultPanel.removeAll();
            inlineResultPanel.setVisible(true);

            String label;
            boolean isError = lastError != null;
            if (isError) label = "ERROR";
            else if (lastResult == null) label = "NO RESULT";
            else label = "OK";
            inlineResultPanel.add(buildHeader(label, isError));

            inlineResultPanel.add(sectionLabel("Request"));
            inlineResultPanel.add(detailLine(rawView ? rawRequestJson() : argsJson(lastArgs), false));

            if (isError) {
                inlineResultPanel.add(sectionLabel("Error"));
                inlineResultPanel.add(detailLine(lastError, true));
                return;
            }

            inlineResultPanel.add(sectionLabel("Response"));
            if (lastResult == null) {
                Span msg = new Span("(no content)");
                msg.getStyle().set("color", "var(--lumo-secondary-text-color)")
                        .set("font-family", "var(--lumo-font-family-monospace)")
                        .set("font-size", "0.85em");
                inlineResultPanel.add(msg);
                return;
            }
            if (rawView) {
                inlineResultPanel.add(detailLine(rawResponseJson(), false));
            } else {
                renderResultInto(inlineResultPanel, lastResult);
            }
        }

        private Component buildHeader(String label, boolean error) {
            HorizontalLayout statusRow = statusHeader(label, lastElapsedMs, error);

            Button toggle = new Button(rawView ? "Formatted" : "Raw");
            toggle.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_CONTRAST);
            if (rawView) toggle.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            toggle.setTooltipText(rawView ? "Show parsed view" : "Show raw JSON-RPC payload");
            toggle.addClickListener(e -> {
                rawView = !rawView;
                renderInline();
            });
            toggle.getStyle().set("margin-left", "0.4em");

            Button close = new Button(VaadinIcon.CLOSE_SMALL.create());
            close.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
            close.setTooltipText("Dismiss this result");
            close.addClickListener(e -> dismissResult());

            statusRow.add(toggle, close);
            return statusRow;
        }

        private void dismissResult() {
            inlineResultPanel.removeAll();
            inlineResultPanel.setVisible(false);
            lastArgs = null;
            lastResult = null;
            lastError = null;
            lastElapsedMs = 0L;
            lastTimestamp = null;
            rawView = false;
        }

        private String argsJson(Map<String, Object> args) {
            try {
                return INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        args == null ? Map.of() : args);
            } catch (JsonProcessingException e) {
                return String.valueOf(args);
            }
        }

        private String rawRequestJson() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", "tools/call");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", tool.name());
            params.put("arguments", lastArgs == null ? Map.of() : lastArgs);
            envelope.put("params", params);
            try {
                return INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
            } catch (JsonProcessingException e) {
                return String.valueOf(envelope);
            }
        }

        private String rawResponseJson() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("result", lastResult);
            try {
                return INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
            } catch (JsonProcessingException e) {
                return String.valueOf(envelope);
            }
        }

        private HorizontalLayout statusHeader(String label, long elapsedMs, boolean error) {
            Span badge = new Span(label);
            String badgeBg = error ? "var(--lumo-error-color-10pct)" : "var(--lumo-success-color-10pct)";
            String badgeColor = error ? "var(--lumo-error-text-color)" : "var(--lumo-success-text-color)";
            badge.getStyle()
                    .set("font-size", "0.7em")
                    .set("font-weight", "600")
                    .set("letter-spacing", "0.05em")
                    .set("padding", "0.15em 0.6em")
                    .set("border-radius", "999px")
                    .set("background-color", badgeBg)
                    .set("color", badgeColor);

            Span elapsed = new Span(elapsedMs + " ms");
            elapsed.getStyle()
                    .set("font-family", "var(--lumo-font-family-monospace)")
                    .set("font-size", "0.85em")
                    .set("color", "var(--lumo-secondary-text-color)");

            Span name = new Span(tool.name());
            name.getStyle()
                    .set("font-family", "var(--lumo-font-family-monospace)")
                    .set("font-size", "0.85em")
                    .set("color", "var(--lumo-body-text-color)");

            Span timestamp = new Span(now());
            timestamp.getStyle()
                    .set("font-family", "var(--lumo-font-family-monospace)")
                    .set("font-size", "0.8em")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("margin-left", "auto");

            HorizontalLayout row = new HorizontalLayout(badge, name, elapsed, timestamp);
            row.setWidthFull();
            row.setPadding(false);
            row.setSpacing(false);
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            row.getStyle().set("gap", "0.7em").set("margin-bottom", "0.6em");
            return row;
        }

        private Component sectionLabel(String text) {
            Span label = new Span(text);
            label.getStyle()
                    .set("font-size", "0.72em")
                    .set("font-weight", "600")
                    .set("letter-spacing", "0.06em")
                    .set("text-transform", "uppercase")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("display", "block")
                    .set("margin", "0.4em 0 0.2em");
            return label;
        }

        private Component detailLine(String text, boolean error) {
            return codeBlock(text == null ? "" : text, error);
        }

    }

    private Div codeBlock(String text, boolean error) {
        boolean blank = text == null || text.trim().isEmpty();
        String displayText = blank ? "(empty)" : text;
        Pre pre = new Pre(displayText);
        pre.getStyle()
                .set("white-space", "pre-wrap")
                .set("word-break", "break-word")
                .set("margin", "0")
                .set("padding", "0.6em 2.5em 0.6em 0.8em")
                .set("font-family", "var(--lumo-font-family-monospace)")
                .set("font-size", "0.85em")
                .set("line-height", "1.4")
                .set("min-height", "1.4em")
                .set("color", blank ? "var(--lumo-secondary-text-color)"
                        : (error ? "var(--lumo-error-text-color)" : "var(--lumo-body-text-color)"));

        Div container = new Div(pre);
        container.getStyle()
                .set("position", "relative")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background-color", error ? "var(--lumo-error-color-10pct)"
                        : "var(--lumo-contrast-5pct)");

        if (!blank) {
            Button copy = iconCopyButton(text);
            copy.getStyle()
                    .set("position", "absolute")
                    .set("top", "0.3em")
                    .set("right", "0.3em");
            container.add(copy);
        }
        return container;
    }

    private Button iconCopyButton(String payload) {
        Button btn = new Button(VaadinIcon.COPY_O.create());
        btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        btn.setTooltipText("Copy to clipboard");
        btn.addClickListener(e -> {
            UI ui = UI.getCurrent();
            if (ui == null || payload == null) return;
            ui.getPage().executeJs("navigator.clipboard.writeText($0)", payload);
            org.springaicommunity.playground.webui.VaadinUtils.showInfoNotification("Copied to clipboard");
        });
        return btn;
    }

    private void renderResultInto(Div container, McpSchema.CallToolResult result) {
        List<McpSchema.Content> contents = result.content();
        if (contents == null || contents.isEmpty()) {
            Span empty = new Span("(empty content)");
            empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
            container.add(empty);
            return;
        }
        for (int i = 0; i < contents.size(); i++) {
            Component block = buildContentBlock(contents.get(i));
            if (i > 0 && block instanceof com.vaadin.flow.component.HasStyle hs) {
                hs.getStyle().set("margin-top", "0.4em");
            }
            container.add(block);
        }
    }

    private HorizontalLayout annotationBadges(McpSchema.ToolAnnotations annotations) {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(false);
        row.setPadding(false);
        row.getStyle().set("gap", "0.3em").set("flex-wrap", "wrap").set("margin-top", "0.3em");
        if (annotations == null) return row;
        addBadge(row, Boolean.TRUE.equals(annotations.readOnlyHint()), "read-only", "var(--lumo-primary-color)");
        addBadge(row, Boolean.TRUE.equals(annotations.destructiveHint()), "destructive", "var(--lumo-error-color)");
        addBadge(row, Boolean.TRUE.equals(annotations.idempotentHint()), "idempotent", "var(--lumo-contrast-50pct)");
        addBadge(row, Boolean.TRUE.equals(annotations.openWorldHint()), "open-world",
                "var(--lumo-warning-text-color)");
        return row;
    }

    private void addBadge(HorizontalLayout row, boolean show, String label, String color) {
        if (!show) return;
        Span badge = new Span(label);
        badge.getStyle()
                .set("font-size", "0.75em")
                .set("padding", "0.1em 0.5em")
                .set("border-radius", "999px")
                .set("border", "1px solid " + color)
                .set("color", color)
                .set("white-space", "nowrap");
        row.add(badge);
    }

    private AbstractField<?, ?> buildArgField(String key, Map<String, Object> schema, boolean required) {
        String type = asString(schema.get("type"));
        Object enumObj = schema.get("enum");
        String description = asString(schema.get("description"));

        if (enumObj instanceof List<?> enumList && !enumList.isEmpty()) {
            ComboBox<String> combo = new ComboBox<>(key);
            List<String> opts = new ArrayList<>();
            for (Object e : enumList) opts.add(String.valueOf(e));
            combo.setItems(opts);
            combo.setWidthFull();
            combo.setPlaceholder("Select…");
            if (description != null) combo.setHelperText(description);
            if (required) combo.setRequiredIndicatorVisible(true);
            return combo;
        }

        if ("boolean".equalsIgnoreCase(type)) {
            Checkbox cb = new Checkbox(key);
            if (description != null) cb.setHelperText(description);
            return cb;
        }

        if ("integer".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) {
            NumberField nf = new NumberField(key);
            nf.setWidthFull();
            Number min = asNumber(schema.get("minimum"));
            Number max = asNumber(schema.get("maximum"));
            if (min != null) nf.setMin(min.doubleValue());
            if (max != null) nf.setMax(max.doubleValue());
            if ("integer".equalsIgnoreCase(type)) nf.setStep(1);
            if (description != null) nf.setHelperText(description);
            if (required) nf.setRequiredIndicatorVisible(true);
            return nf;
        }

        if ("array".equalsIgnoreCase(type) || "object".equalsIgnoreCase(type)) {
            TextArea ta = new TextArea(key);
            ta.setWidthFull();
            ta.setPlaceholder(type.equalsIgnoreCase("array") ? "[ … ]" : "{ … }");
            ta.setHeight("5em");
            ta.getStyle().set("font-family", "var(--lumo-font-family-monospace)");
            if (description != null) ta.setHelperText(description + " (JSON)");
            else ta.setHelperText("JSON");
            if (required) ta.setRequiredIndicatorVisible(true);
            return ta;
        }

        TextField tf = new TextField(key);
        tf.setWidthFull();
        if (description != null) tf.setHelperText(description);
        if (required) tf.setRequiredIndicatorVisible(true);
        return tf;
    }

    private static Object readArgValue(String key, AbstractField<?, ?> field, boolean required) {
        if (field instanceof Checkbox cb) return cb.getValue();
        if (field instanceof NumberField nf) {
            Double v = nf.getValue();
            if (v == null) {
                if (required) throw new IllegalArgumentException(ARG_REQUIRED + ": " + key);
                return null;
            }
            return v == v.intValue() ? Integer.valueOf(v.intValue()) : v;
        }
        if (field instanceof ComboBox<?> combo) {
            Object v = combo.getValue();
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (required) throw new IllegalArgumentException(ARG_REQUIRED + ": " + key);
                return null;
            }
            return v;
        }
        if (field instanceof TextArea ta) {
            String raw = ta.getValue() == null ? "" : ta.getValue().trim();
            if (raw.isEmpty()) {
                if (required) throw new IllegalArgumentException(ARG_REQUIRED + ": " + key);
                return null;
            }
            try {
                return INSPECTOR_OBJECT_MAPPER.readValue(raw, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid JSON for '" + key + "': " + e.getOriginalMessage());
            }
        }
        if (field instanceof TextField tf) {
            String raw = tf.getValue() == null ? "" : tf.getValue().trim();
            if (raw.isEmpty()) {
                if (required) throw new IllegalArgumentException(ARG_REQUIRED + ": " + key);
                return null;
            }
            return raw;
        }
        return ((HasValue<?, ?>) field).getValue();
    }

    private Component buildContentBlock(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent text) {
            return codeBlock(tryPrettyJson(text.text()), false);
        }
        if (content instanceof McpSchema.ImageContent image) {
            return imageBlock(image);
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            return codeBlock(prettyPrint(embedded), false);
        }
        return codeBlock(String.valueOf(content), false);
    }

    private Component imageBlock(McpSchema.ImageContent image) {
        String mime = image.mimeType() == null ? "image/png" : image.mimeType();
        Image img = new Image("data:" + mime + ";base64," + image.data(), "tool result image");
        img.getStyle().set("max-width", "100%").set("max-height", "20em").set("display", "block");

        Div container = new Div(img);
        container.getStyle()
                .set("position", "relative")
                .set("padding", "0.6em")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background-color", "var(--lumo-contrast-5pct)");

        Button copy = iconCopyButton(image.data());
        copy.getStyle()
                .set("position", "absolute")
                .set("top", "0.3em")
                .set("right", "0.3em");
        container.add(copy);
        return container;
    }

    private String tryPrettyJson(String maybeJson) {
        if (maybeJson == null || maybeJson.isBlank()) return maybeJson == null ? "" : maybeJson;
        String trimmed = maybeJson.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return maybeJson;
        try {
            JsonNode parsed = INSPECTOR_OBJECT_MAPPER.readTree(trimmed);
            return INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            return maybeJson;
        }
    }

    private String prettyPrint(Object value) {
        try {
            return INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private ToolInfo toToolInfo(McpSchema.Tool tool) {
        String displayTitle = pickFirstNonBlank(annotationsTitle(tool.annotations()), tool.title());
        String name = Optional.ofNullable(tool.name()).orElse(displayTitle == null ? "" : displayTitle);
        Map<String, Map<String, Object>> propertySchemas = extractPropertySchemas(tool.inputSchema());
        List<String> req = Optional.ofNullable(tool.inputSchema())
                .map(McpSchema.JsonSchema::required)
                .orElseGet(List::of);
        return new ToolInfo(displayTitle, name, tool.description(), tool.annotations(), req, propertySchemas);
    }

    private static String annotationsTitle(McpSchema.ToolAnnotations annotations) {
        return annotations == null ? null : annotations.title();
    }

    private static String pickFirstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> extractPropertySchemas(McpSchema.JsonSchema schema) {
        if (schema == null || schema.properties() == null) return Map.of();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : schema.properties().entrySet()) {
            Map<String, Object> propSchema =
                    e.getValue() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            out.put(e.getKey(), propSchema);
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Number asNumber(Object o) {
        if (o instanceof Number n) return n;
        if (o instanceof String s) {
            try { return Double.valueOf(s); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }
}
