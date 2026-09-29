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
package org.springaicommunity.playground.webui.observability;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import org.springaicommunity.playground.observability.conversation.ConversationAggregator.ConversationSummary;
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ConversationStats;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.webui.observability.components.BaseDashboardTab;
import org.springaicommunity.playground.webui.observability.components.DashboardData;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas;
import org.springaicommunity.playground.webui.observability.components.DashboardLayout;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas.LineSeries;
import org.springaicommunity.playground.webui.observability.components.DashboardPalette;
import org.springaicommunity.playground.webui.observability.components.FormatUtils;
import org.springaicommunity.playground.webui.observability.components.KpiCard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AgenticChatTab extends BaseDashboardTab {

    private static final DateTimeFormatter SHORT_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LIST_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ObservabilityQueryService queryService;
    private final ObservabilityRingBuffer buffer;

    private final KpiCard activeConversationsCard = new KpiCard("Conversations");
    private final KpiCard avgMessagesCard = new KpiCard("Avg messages / conv");
    private final KpiCard avgCostCard = new KpiCard("Avg cost / conv");
    private final KpiCard avgDurationCard = new KpiCard("Avg duration");
    private final KpiCard multiTurnRateCard = new KpiCard("Multi-turn rate");
    private final KpiCard toolUsingRateCard = new KpiCard("Tool-using rate");
    private final KpiCard ragUsingRateCard = new KpiCard("RAG-using rate");
    private final KpiCard maxLoopDepthCard = new KpiCard("Max loop depth (p95)");

    private final ChartCanvas messagesOverTimeChart = new ChartCanvas();
    private final ChartCanvas conversationsStartedChart = new ChartCanvas();
    private final ChartCanvas loopDepthHistogram = new ChartCanvas();
    private final ChartCanvas durationHistogram = new ChartCanvas();
    private final ChartCanvas topToolsBar = new ChartCanvas();
    private final ChartCanvas modelsBar = new ChartCanvas();
    private final ChartCanvas costPerConvBar = new ChartCanvas();
    private final ChartCanvas statusDonut = new ChartCanvas();

    private final Grid<ConversationSummary> conversationsGrid = new Grid<>(ConversationSummary.class, false);

    public AgenticChatTab(ObservabilityQueryService queryService, ObservabilityRingBuffer buffer) {
        super();
        this.queryService = queryService;
        this.buffer = buffer;

        Div kpiGrid = DashboardLayout.kpiGrid(activeConversationsCard, avgMessagesCard, avgCostCard,
                avgDurationCard, multiTurnRateCard, toolUsingRateCard, ragUsingRateCard,
                maxLoopDepthCard);

        Div grid = DashboardLayout.chartGrid();
        grid.add(
                DashboardLayout.chartCard("Messages over time",
                        "direct LLM vs tool-using per bucket",
                        "Each TraceRecord = one assistant turn. Stacked by tool-call count " +
                                "buckets: 0 tools (direct), 1-2 tools (light agentic), 3+ (deep loop).",
                        messagesOverTimeChart),
                DashboardLayout.chartCard("New conversations / minute",
                        "first message in a conversation",
                        "Counts unique conversation.id with first message in each bucket.",
                        conversationsStartedChart),
                DashboardLayout.chartCard("Agentic loop depth",
                        "tool calls per message",
                        "Distribution of toolCallCount across messages. High loop depth indicates " +
                                "the agent recursed deeply (multiple tool calls before final answer).",
                        loopDepthHistogram),
                DashboardLayout.chartCard("Conversation duration",
                        "first → last message",
                        "How long conversations remain active. Short = one-shot Q&A, long = " +
                                "extended multi-turn or background-running.",
                        durationHistogram),
                DashboardLayout.chartCard("Tools used in conversations",
                        "top tools by occurrence count",
                        "Number of conversations that called each tool at least once.",
                        topToolsBar),
                DashboardLayout.chartCard("Models used in conversations",
                        "top models by conversation count",
                        "Number of conversations that used each model.",
                        modelsBar),
                DashboardLayout.chartCard("Cost per conversation",
                        "top 10 by cost (USD)",
                        "Identifies the most expensive conversations in the window.",
                        costPerConvBar),
                DashboardLayout.chartCard("Conversation outcomes",
                        "worst status per conversation — OK / Error / Cancelled",
                        "Worst status across messages in each conversation.",
                        statusDonut)
        );

        configureConversationsGrid();
        Div gridCard = new Div(conversationsGrid);
        gridCard.getStyle().set("background", "var(--lumo-base-color)")
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s)");

        H3 conversationsHeader = new H3("Conversations — click row to open thread view");
        conversationsHeader.getStyle().set("margin", "var(--lumo-space-l) 0 var(--lumo-space-xs) 0")
                .set("font-size", "var(--lumo-font-size-l)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-weight", "600");

        content.add(kpiGrid, grid, conversationsHeader, gridCard);
    }

    private void configureConversationsGrid() {
        conversationsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        conversationsGrid.addColumn(c -> LIST_FMT.format(Instant.ofEpochMilli(c.firstStartMs())))
                .setHeader("Started").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> shorten(c.conversationId(), 14))
                .setHeader("Conv ID").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> String.valueOf(c.messageCount()))
                .setHeader("Msgs").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> FormatUtils.formatLatencyMs(c.durationMs()))
                .setHeader("Duration").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> String.valueOf(c.toolCalls()))
                .setHeader("Tools").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> String.valueOf(c.ragCalls()))
                .setHeader("RAG").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> FormatUtils.formatNumber(c.totalTokens()))
                .setHeader("Tokens").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> formatUsd(c.cost()))
                .setHeader("Cost").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.addColumn(c -> String.join(", ", c.modelsUsed()))
                .setHeader("Models").setAutoWidth(true).setFlexGrow(1);
        conversationsGrid.addColumn(ConversationSummary::worstStatus)
                .setHeader("Status").setAutoWidth(true).setFlexGrow(0);
        conversationsGrid.setAllRowsVisible(true);
        conversationsGrid.addItemClickListener(e -> {
            if (e.getItem() != null) {
                new ConversationThreadDialog(e.getItem().conversationId(), buffer).open();
            }
        });
    }

    @Override
    public void refresh(Window window) {
        long windowStart = Instant.now().toEpochMilli() - window.totalMs();
        ConversationStats stats = queryService.conversationStats(window);
        List<ConversationSummary> conversations = stats.conversations();

        activeConversationsCard.setValue(String.valueOf(conversations.size()),
                "Unique conversation.id values with at least one trace in window");

        if (conversations.isEmpty()) {
            avgMessagesCard.setValue("—", "No conversations in window");
            avgCostCard.setValue("—", "No conversations in window");
            avgDurationCard.setValue("—", "No conversations in window");
            multiTurnRateCard.setValue("—", "No conversations in window");
            toolUsingRateCard.setValue("—", "No conversations in window");
            ragUsingRateCard.setValue("—", "No conversations in window");
            maxLoopDepthCard.setValue("—", "No conversations in window");
        } else {
            avgMessagesCard.setValue(String.format(Locale.ROOT, "%.1f", stats.avgMessages()),
                    "Mean number of messages per conversation in window");
            avgCostCard.setValue(formatUsd(stats.avgCost()),
                    "Mean ModelPricingService.cost(model, in, out) summed per conversation. "
                            + "Free Ollama models = $0.");
            avgDurationCard.setValue(FormatUtils.formatLatencyMs(stats.avgDurationMs()),
                    "Mean span from first to last message in each conversation");
            multiTurnRateCard.setValue(String.format(Locale.ROOT, "%.0f%%",
                    stats.multiTurn() * 100.0 / conversations.size()),
                    stats.multiTurn() + " of " + conversations.size() + " conversations had >1 message");
            toolUsingRateCard.setValue(String.format(Locale.ROOT, "%.0f%%",
                    stats.toolUsing() * 100.0 / conversations.size()),
                    stats.toolUsing() + " of " + conversations.size()
                            + " conversations invoked at least one tool");
            ragUsingRateCard.setValue(String.format(Locale.ROOT, "%.0f%%",
                    stats.ragUsing() * 100.0 / conversations.size()),
                    stats.ragUsing() + " of " + conversations.size()
                            + " conversations queried a vector store");
            maxLoopDepthCard.setValue(String.valueOf(stats.p95LoopDepth()),
                    "p95 of max tool-call count per message across all conversations");
        }

        List<String> labels = DashboardData.bucketLabelsFromWindow(windowStart, window.bucketMs(), window.buckets());
        messagesOverTimeChart.multiStackedBar(labels, List.of(
                new LineSeries("0 tools (direct)", stats.direct(), DashboardPalette.SUCCESS),
                new LineSeries("1-2 tools (light)", stats.lightAgentic(), DashboardPalette.INFO),
                new LineSeries("3+ tools (deep)", stats.deepLoop(), DashboardPalette.ERROR)
        ));
        conversationsStartedChart.lineChart(labels, "New conversations", stats.newConversationsPerBucket(),
                DashboardPalette.PRIMARY, "");
        loopDepthHistogram.horizontalBarChart(stats.depthBins(), DashboardPalette.ERROR, 5);
        durationHistogram.horizontalBarChart(stats.durationBins(), DashboardPalette.INFO, 5);
        topToolsBar.horizontalBarChart(stats.toolCounts(), DashboardPalette.SUCCESS, 8);
        modelsBar.horizontalBarChart(stats.modelCounts(), DashboardPalette.PRIMARY, 8);
        Map<String, Long> costByConversation = new LinkedHashMap<>();
        stats.topCostMicros().forEach((conversationId, micros) ->
                costByConversation.merge(shortConversationId(conversationId), micros, Long::sum));
        costPerConvBar.horizontalBarChart(costByConversation, DashboardPalette.WARN, 10);
        statusDonut.donutChart(stats.statusCounts(), ChartCanvas.DEFAULT_PALETTE);

        conversationsGrid.setItems(conversations);

        setStatus(conversations.size() + " conversations · "
                + stats.totalMessages() + " messages · "
                + stats.totalToolCalls() + " tool calls · " + stats.totalRagCalls() + " RAG retrievals · "
                + "window: " + window.minutes + "m");
    }

    private static String shorten(String s, int n) {
        if (s == null) return "—";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private String formatUsd(BigDecimal v) {
        if (v == null || v.signum() == 0) return "$0.00";
        if (v.compareTo(new BigDecimal("0.01")) < 0) {
            return String.format(Locale.ROOT, "$%.6f", v.doubleValue());
        }
        return String.format(Locale.ROOT, "$%.4f", v.doubleValue());
    }

    private static String shortConversationId(String conversationId) {
        if (ObservabilityQueryService.UNKNOWN.equals(conversationId)) return "\u2014";
        return conversationId.length() <= 12 ? conversationId : conversationId.substring(0, 12) + "\u2026";
    }
}
