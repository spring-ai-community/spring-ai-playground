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
package org.springaicommunity.playground.webui.mcp.inspector.primitives.server;

import tools.jackson.core.JacksonException;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpNotificationStore;
import org.springaicommunity.playground.service.mcp.risk.McpToolPoisoningScanner;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator;
import org.springaicommunity.playground.webui.CountdownLabel;
import org.springaicommunity.playground.webui.mcp.McpRiskChip;
import org.springaicommunity.playground.webui.mcp.inspector.InspectorHelpers;
import org.springaicommunity.playground.webui.mcp.inspector.InspectorHelpers.ToolInfo;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.InlineResultPanel;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.PrimitiveCardLayout;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolPrimitive extends Div {

    private final ToolInfo tool;
    private final McpServerInfo serverInfo;
    private final McpClientService clientService;
    private final Map<String, AbstractField<?, ?>> fields = new LinkedHashMap<>();
    private final InlineResultPanel inlineResultPanel = new InlineResultPanel();
    private Map<String, Object> lastArgs;
    private McpSchema.CallToolResult lastResult;
    private String lastError;
    private long lastElapsedMs;
    private String lastTimestamp;
    private boolean rawView = false;
    private boolean running = false;
    private final Div runningHint = new Div();
    private final Div approvalPanel = new Div();
    private String pendingApprovalId;

    public ToolPrimitive(ToolInfo tool, int displayIndex, McpServerInfo serverInfo,
            McpClientService clientService, McpToolRiskEvaluator.ToolRiskView risk) {
        this.tool = tool;
        this.serverInfo = serverInfo;
        this.clientService = clientService;
        setWidthFull();
        PrimitiveCardLayout.applyCardStyle(this);

        String displayTitle = InspectorHelpers.pickFirstNonBlank(tool.displayTitle(), tool.name());
        HorizontalLayout titleRow = PrimitiveCardLayout.titleRow(VaadinIcon.PLAY, "Run tool", displayTitle,
                this::handleRun);
        if (tool.description() != null && !tool.description().isBlank()) {
            titleRow.getChildren()
                    .filter(c -> c instanceof Span)
                    .findFirst()
                    .ifPresent(span -> Tooltip.forComponent(span).withText(tool.description()));
        }
        if (risk != null && risk.finalLevel() != null) {
            String tip = buildRiskTooltip(risk);
            titleRow.add(McpRiskChip.mitigated(risk.finalLevel(), risk.inherentLevel(), risk.floorTrigger())
                    .withTooltip(tip));
        }
        add(titleRow);

        if (tool.displayTitle() != null && !tool.displayTitle().isBlank()
                && !tool.displayTitle().equals(tool.name())) {
            add(PrimitiveCardLayout.subNameLabel(tool.name()));
        }

        HorizontalLayout badges = InspectorHelpers.annotationBadges(tool.annotations());
        if (badges.getComponentCount() > 0) {
            badges.getStyle().set("margin", "0.3em 0 0 2.4em");
            add(badges);
        }
        if (risk != null && risk.poisoningResult() != null && !risk.poisoningResult().clean()) {
            add(buildPoisoningWarning(risk.poisoningResult()));
        }

        if (!tool.propertySchemas().isEmpty()) {
            add(InspectorHelpers.simpleSectionLabel("Parameters"));
            VerticalLayout argsLayout = new VerticalLayout();
            argsLayout.setPadding(false);
            argsLayout.setSpacing(false);
            argsLayout.getStyle().set("gap", "0.6em");
            tool.propertySchemas().forEach((key, schema) -> {
                AbstractField<?, ?> field =
                        InspectorHelpers.buildArgField(key, schema, tool.required().contains(key));
                fields.put(key, field);
                argsLayout.add(field);
            });
            add(argsLayout);
        }

        runningHint.setVisible(false);
        runningHint.getStyle().set("display", "flex").set("gap", "0.4em").set("flex-wrap", "wrap")
                .set("margin", "0.5em 0 0 2.4em")
                .set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        add(runningHint);
        approvalPanel.setVisible(false);
        approvalPanel.getStyle().set("margin", "0.5em 0 0 2.4em").set("padding", "0.6em 0.8em")
                .set("border", "1px solid var(--lumo-primary-color-50pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("background-color", "var(--lumo-primary-color-10pct)");
        add(approvalPanel);
        inlineResultPanel.setOnDismiss(this::clearLastResultState);
        add(inlineResultPanel);
    }

    public boolean isRunning() {
        return this.running;
    }

    public void showApprovalRequest(McpNotificationStore.PendingElicitation pending, Runnable openElicitationTab) {
        this.pendingApprovalId = pending.id();
        this.approvalPanel.removeAll();
        Span text = new Span("Approval needed: " + pending.request().message());
        text.getStyle().set("display", "block").set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout actions = new HorizontalLayout();
        actions.setPadding(false);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        actions.getStyle().set("margin-top", "0.4em").set("gap", "0.6em").set("flex-wrap", "wrap");
        if (InspectorHelpers.requiresNoInput(pending.request())) {
            Button accept = new Button("Accept", VaadinIcon.CHECK.create(), click -> answer(pending,
                    McpSchema.ElicitResult.Action.ACCEPT));
            accept.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
            Button decline = new Button("Decline", click -> answer(pending, McpSchema.ElicitResult.Action.DECLINE));
            decline.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            actions.add(accept, decline);
        }
        actions.add(new CountdownLabel(pending.deadline(), " left to answer"));
        Button review = new Button("Review", click -> openElicitationTab.run());
        review.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        actions.add(review);
        this.approvalPanel.add(text, actions);
        this.approvalPanel.setVisible(true);
    }

    public void clearApprovalRequest(String pendingId) {
        if (pendingId == null || !pendingId.equals(this.pendingApprovalId)) return;
        hideApprovalPanel();
    }

    private void answer(McpNotificationStore.PendingElicitation pending, McpSchema.ElicitResult.Action action) {
        this.clientService.completeElicitation(this.serverInfo, pending.id(),
                new McpSchema.ElicitResult(action, Map.of()));
        hideApprovalPanel();
    }

    private void hideApprovalPanel() {
        this.pendingApprovalId = null;
        this.approvalPanel.removeAll();
        this.approvalPanel.setVisible(false);
    }

    public boolean matches(String needle) {
        if (needle.isEmpty()) return true;
        return InspectorHelpers.contains(tool.name(), needle)
                || InspectorHelpers.contains(tool.displayTitle(), needle)
                || InspectorHelpers.contains(tool.description(), needle);
    }

    private void handleRun() {
        Map<String, Object> args = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            AbstractField<?, ?> field = entry.getValue();
            boolean required = tool.required().contains(key);
            Object value;
            try {
                value = InspectorHelpers.readArgValue(key, field, required);
            } catch (IllegalArgumentException ex) {
                lastArgs = args;
                lastResult = null;
                lastError = ex.getMessage();
                lastElapsedMs = 0L;
                lastTimestamp = InspectorHelpers.now();
                renderInline();
                return;
            }
            if (value != null) args.put(key, value);
        }

        if (this.running) return;
        UI ui = UI.getCurrent();
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        setRunning(true);
        long startNs = System.nanoTime();
        Thread.ofVirtual().name("mcp-inspector-tool-call").start(() -> {
            if (callerMdc != null) MDC.setContextMap(callerMdc);
            McpSchema.CallToolResult result = null;
            String error = null;
            try {
                result = this.clientService.callTool(this.serverInfo, this.tool.name(), args,
                        Map.of(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY, true)).orElse(null);
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            } finally {
                MDC.clear();
            }
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            McpSchema.CallToolResult finalResult = result;
            String finalError = error;
            try {
                ui.access(() -> showCallOutcome(args, finalResult, finalError, elapsedMs));
            } catch (UIDetachedException ignore) {
            }
        });
    }

    private void showCallOutcome(Map<String, Object> args, McpSchema.CallToolResult result, String error,
            long elapsedMs) {
        this.lastArgs = args;
        this.lastResult = result;
        this.lastError = error;
        this.lastElapsedMs = elapsedMs;
        this.lastTimestamp = InspectorHelpers.now();
        setRunning(false);
        renderInline();
    }

    private void setRunning(boolean running) {
        this.running = running;
        this.runningHint.removeAll();
        if (running) {
            if (this.clientService.inspectorCallAwaitsApproval(this.serverInfo, this.tool.name())) {
                this.runningHint.add(new Span(
                        "Running... answer any Sampling or Elicitation request from its tab while this call waits."),
                        new CountdownLabel(this.clientService.approvalWait(), " left to approve"));
            } else {
                this.runningHint.add(new Span(
                        "Running... answer any Sampling or Elicitation request from its tab "
                                + "before the call times out."),
                        new CountdownLabel(this.clientService.requestTimeout(), " left before the call times out"));
            }
        }
        this.runningHint.setVisible(running);
        if (!running) hideApprovalPanel();
    }

    private void renderInline() {
        InlineResultPanel.Render render = inlineResultPanel.render()
                .extraHeaderButton(buildRawToggle())
                .addPreResponseSection(InspectorHelpers.simpleSectionLabel("Request"))
                .addPreResponseSection(InspectorHelpers.codeBlock(
                        rawView ? rawRequestJson() : argsJson(lastArgs), false));

        if (lastError != null) {
            render.commitError(tool.name(), lastElapsedMs, lastError);
            return;
        }
        if (lastResult == null) {
            // outer call returned Optional.empty → "NO RESULT" badge, but original
            // still shows the "Response" section label before the placeholder
            render.emptyBadgeLabel("NO RESULT")
                    .commitEmptyResponse(tool.name(), lastElapsedMs, "(no content)");
            return;
        }
        if (rawView) {
            render.commitOk(tool.name(), lastElapsedMs,
                    List.of(InspectorHelpers.codeBlock(rawResponseJson(), false)));
            return;
        }
        List<McpSchema.Content> contents = lastResult.content();
        if (contents == null || contents.isEmpty()) {
            // result was non-null but the content list is empty → "OK" badge with placeholder body
            render.emptyBadgeLabel("OK").commitEmptyResponse(tool.name(), lastElapsedMs, "(empty content)");
            return;
        }
        List<Component> blocks = new ArrayList<>(contents.size());
        for (McpSchema.Content content : contents) {
            blocks.add(InspectorHelpers.buildContentBlock(content));
        }
        render.commitOk(tool.name(), lastElapsedMs, blocks);
    }

    private Button buildRawToggle() {
        Button toggle = new Button(rawView ? "Formatted" : "Raw");
        toggle.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_CONTRAST);
        if (rawView) toggle.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        toggle.setTooltipText(rawView ? "Show parsed view" : "Show raw JSON-RPC payload");
        toggle.addClickListener(e -> {
            rawView = !rawView;
            renderInline();
        });
        return toggle;
    }

    private void clearLastResultState() {
        lastArgs = null;
        lastResult = null;
        lastError = null;
        lastElapsedMs = 0L;
        lastTimestamp = null;
        rawView = false;
    }

    private String argsJson(Map<String, Object> args) {
        try {
            return InspectorHelpers.INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    args == null ? Map.of() : args);
        } catch (JacksonException e) {
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
            return InspectorHelpers.INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (JacksonException e) {
            return String.valueOf(envelope);
        }
    }

    private static String buildRiskTooltip(McpToolRiskEvaluator.ToolRiskView risk) {
        StringBuilder sb = new StringBuilder(McpRiskChip.levelRationale(risk.finalLevel()));
        if (risk.inherentLevel() != null && risk.inherentLevel() != risk.finalLevel()) {
            sb.append(" · inherent ").append(risk.inherentLevel().name())
                    .append(" lowered to ").append(risk.finalLevel().name())
                    .append(" by required approval (HITL)");
        }
        if (risk.publishLevel() != null && risk.publishLevel() != risk.finalLevel()) {
            sb.append(" · publish ").append(risk.publishLevel().name());
        }
        if (risk.floorTrigger() != null) {
            sb.append(" · floor: ").append(risk.floorTrigger());
        }
        sb.append(" · hover tool name for description");
        return sb.toString();
    }

    private static Div buildPoisoningWarning(McpToolPoisoningScanner.ScanResult result) {
        StringBuilder text = new StringBuilder("⚠ Poisoning patterns detected (");
        text.append(result.matches().size()).append("): ");
        boolean first = true;
        for (McpToolPoisoningScanner.Match match : result.matches()) {
            if (!first) text.append(", ");
            text.append(match.pattern().name());
            first = false;
        }
        text.append(". Publish blocked.");
        Div warning = new Div();
        warning.setText(text.toString());
        warning.getStyle()
                .set("margin", "0.5em 0 0 2.4em")
                .set("padding", "0.4em 0.7em")
                .set("background-color", "#fdecea")
                .set("color", "#b71c1c")
                .set("border-left", "3px solid #e53935")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("font-size", "12px")
                .set("font-weight", "600");
        return warning;
    }

    private String rawResponseJson() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("result", lastResult);
        try {
            return InspectorHelpers.INSPECTOR_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (JacksonException e) {
            return String.valueOf(envelope);
        }
    }
}
