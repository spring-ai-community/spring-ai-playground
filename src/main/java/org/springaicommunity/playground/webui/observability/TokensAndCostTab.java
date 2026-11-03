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

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.CostBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ExpensiveTrace;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ModelCost;
import org.springaicommunity.playground.observability.ObservabilityTimeSeries;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.pricing.CurrencyService;
import org.springaicommunity.playground.observability.pricing.CurrencyService.CurrencyRate;
import org.springaicommunity.playground.webui.observability.components.BaseDashboardTab;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas;
import org.springaicommunity.playground.webui.observability.components.DashboardData;
import org.springaicommunity.playground.webui.observability.components.DashboardLayout;
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

public class TokensAndCostTab extends BaseDashboardTab {

    private static final DateTimeFormatter HHMM =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ObservabilityQueryService queryService;
    private final ObservabilityTimeSeries timeSeries;
    private final CurrencyService currencyService;

    private final KpiCard totalCostCard = new KpiCard("Total cost");
    private final KpiCard totalTokensCard = new KpiCard("Total tokens");
    private final KpiCard avgCostCard = new KpiCard("Avg cost / call");
    private final KpiCard costRateCard = new KpiCard("Cost rate /min");
    private final KpiCard maxCostCard = new KpiCard("Most expensive call");
    private final KpiCard paidShareCard = new KpiCard("Paid call share");
    private final KpiCard inputCostCard = new KpiCard("Input cost");
    private final KpiCard outputCostCard = new KpiCard("Output cost");

    private final ChartCanvas costOverTime = new ChartCanvas();
    private final ChartCanvas tokensStacked = new ChartCanvas();
    private final ChartCanvas costByProvider = new ChartCanvas();
    private final ChartCanvas costByModel = new ChartCanvas();
    private final ChartCanvas tokensByModel = new ChartCanvas();

    private final Grid<ModelCost> statsGrid = new Grid<>(ModelCost.class, false);
    private final Grid<ExpensiveTrace> topExpensive = new Grid<>(ExpensiveTrace.class, false);
    private Grid.Column<ModelCost> costColumn;

    public TokensAndCostTab(ObservabilityQueryService queryService, ObservabilityTimeSeries timeSeries,
            CurrencyService currencyService) {
        super();
        this.queryService = queryService;
        this.timeSeries = timeSeries;
        this.currencyService = currencyService;

        Div kpiRow = DashboardLayout.kpiGrid(totalCostCard, totalTokensCard, avgCostCard,
                costRateCard, maxCostCard, paidShareCard, inputCostCard, outputCostCard);

        Div grid = DashboardLayout.chartGrid();
        grid.add(
                DashboardLayout.chartCard("Cost over time",
                        "per 1-min bucket (active currency)",
                        "Σ pricingService.cost(model, in, out) per 1-min bucket, multiplied by the " +
                                "active currency rate. Missing prices count as $0 (free).",
                        costOverTime),
                DashboardLayout.chartCard("Tokens (input + output)",
                        "input + output per bucket",
                        "gen_ai.usage.input_tokens + output_tokens summed per bucket. From the trace " +
                                "ring buffer's TraceRecord.inputTokens / outputTokens fields.",
                        tokensStacked),
                DashboardLayout.chartCard("Cost by provider",
                        "share by provider (active currency)",
                        "Per-provider sum of trace cost. Provider from gen_ai.system span attribute.",
                        costByProvider),
                DashboardLayout.chartCard("Cost by model",
                        "top 8 (active currency)",
                        "Per-model cost (top 8 in window). Multiplied by active currency rate.",
                        costByModel),
                DashboardLayout.chartCard("Tokens by model",
                        "top 8 by total tokens",
                        "Total token count (input + output) per model across the window — top 8.",
                        tokensByModel)
        );

        statsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        statsGrid.addColumn(ModelCost::model).setHeader("Model").setAutoWidth(true).setFlexGrow(1);
        statsGrid.addColumn(modelCost -> String.valueOf(modelCost.calls())).setHeader("Calls").setAutoWidth(true);
        statsGrid.addColumn(modelCost -> FormatUtils.formatNumber(modelCost.inputTokens())).setHeader("In tok").setAutoWidth(true);
        statsGrid.addColumn(modelCost -> FormatUtils.formatNumber(modelCost.outputTokens())).setHeader("Out tok").setAutoWidth(true);
        this.costColumn = statsGrid.addColumn(modelCost -> formatUsd(modelCost.cost())).setHeader("Cost").setAutoWidth(true);
        statsGrid.addColumn(modelCost -> formatUsd(modelCost.avgCostPerCall())).setHeader("Avg / call").setAutoWidth(true);
        statsGrid.setAllRowsVisible(true);

        topExpensive.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        topExpensive.addColumn(trace -> HHMM.format(Instant.ofEpochMilli(trace.startEpochMs()))).setHeader("Time").setAutoWidth(true);
        topExpensive.addColumn(trace -> safe(trace.model())).setHeader("Model").setAutoWidth(true);
        topExpensive.addColumn(trace -> FormatUtils.formatNumberOrDash(trace.inputTokens())).setHeader("In").setAutoWidth(true);
        topExpensive.addColumn(trace -> FormatUtils.formatNumberOrDash(trace.outputTokens())).setHeader("Out").setAutoWidth(true);
        topExpensive.addColumn(trace -> formatUsd(trace.cost())).setHeader("Cost").setAutoWidth(true);
        topExpensive.addColumn(trace -> shorten(trace.traceId())).setHeader("Trace").setAutoWidth(true);
        topExpensive.addItemClickListener(e -> {
            if (e.getItem() != null) {
                UI.getCurrent().navigate("observability?tab=traces&trace=" + e.getItem().traceId());
            }
        });
        topExpensive.setAllRowsVisible(true);

        H3 perModelHeader = new H3("Per-model breakdown");
        H3 expensiveHeader = new H3("Top 10 most expensive traces");
        for (H3 h : new H3[] { perModelHeader, expensiveHeader }) {
            h.getStyle().set("margin", "var(--lumo-space-l) 0 var(--lumo-space-xs) 0")
                    .set("font-size", "var(--lumo-font-size-l)");
        }

        content.add(kpiRow, grid,
                perModelHeader, statsGrid,
                expensiveHeader, topExpensive);
    }

    @Override
    public void refresh(Window window) {
        ObservabilityTimeSeries.Series s = timeSeries.compute(window);

        CostBreakdown cost = queryService.costBreakdown(window);
        BigDecimal totalCost = cost.totalCost();
        long totalCalls = cost.totalCalls();
        long paidCalls = cost.paidCalls();
        double[] costPerBucket = cost.costPerBucket();

        totalCostCard.setValue(formatUsd(totalCost),
                "Σ ModelPricingService.cost(model, in, out) across all traces in window · active currency");
        totalTokensCard.setValue(FormatUtils.formatNumber(s.totalTokens()),
                "Σ (input + output tokens) across all traces in window");
        avgCostCard.setValue(formatUsd(cost.averageCost()),
                "Total cost / call count — useful baseline for spend per LLM call");
        costRateCard.setValue(formatUsd(cost.costPerMinute(window.minutes)),
                "Total cost / window minutes — extrapolation requires steady-state traffic");
        maxCostCard.setValue(formatUsd(cost.maxTraceCost()),
                "Single most expensive trace in window — drill into Traces tab to inspect");
        double paidPct = totalCalls == 0 ? 0 : paidCalls * 100.0 / totalCalls;
        paidShareCard.setValue(String.format(Locale.ROOT, "%.0f%% (%d/%d)",
                paidPct, paidCalls, totalCalls),
                "Fraction of traces where pricingService.cost() returned > 0 (i.e. model has pricing configured)");

        double rateMultiplier = currencyService.getActiveRate().rateFromUsd().doubleValue();
        String activeSymbol = currencyService.getActiveRate().symbol();
        double[] costPerBucketActive = new double[costPerBucket.length];
        for (int i = 0; i < costPerBucket.length; i++) {
            costPerBucketActive[i] = costPerBucket[i] * rateMultiplier;
        }
        List<String> labels = DashboardData.bucketLabelsFromStarts(s.bucketStartMs());
        costOverTime.lineChart(labels, "cost", costPerBucketActive, DashboardPalette.PRIMARY, activeSymbol);
        tokensStacked.stackedBarChart(labels,
                "Input", DashboardData.toDouble(s.inTokens()), DashboardPalette.INFO,
                "Output", DashboardData.toDouble(s.outTokens()), DashboardPalette.SUCCESS);

        Map<String, Double> providerCostDouble = new LinkedHashMap<>();
        cost.providerCost().forEach((provider, providerTotal) -> providerCostDouble.put(provider,
                currencyService.convertFromUsd(providerTotal).doubleValue()));
        if (providerCostDouble.values().stream().allMatch(providerTotal -> providerTotal == 0.0)) {
            costByProvider.donutChart(s.totalCallsByProvider(), ChartCanvas.DEFAULT_PALETTE);
        } else {
            costByProvider.donutChartDecimal(providerCostDouble, ChartCanvas.DEFAULT_PALETTE, activeSymbol);
        }

        Map<String, Double> costByModelDouble = new LinkedHashMap<>();
        for (ModelCost stats : cost.models()) {
            costByModelDouble.put(stats.model(),
                    currencyService.convertFromUsd(stats.cost()).doubleValue());
        }
        if (costByModelDouble.values().stream().allMatch(v -> v == 0.0)) {
            costByModel.horizontalBarChart(s.totalCallsByModel(), DashboardPalette.PRIMARY, 8);
        } else {
            costByModel.horizontalBarChartDecimal(costByModelDouble, DashboardPalette.PRIMARY, 8, activeSymbol);
        }

        inputCostCard.setValue(formatUsd(cost.totalInputCost()),
                "Σ input_tokens × model.inputPerMillionTokens / 1M · active currency");
        outputCostCard.setValue(formatUsd(cost.totalOutputCost()),
                "Σ output_tokens × model.outputPerMillionTokens / 1M · active currency");

        tokensByModel.horizontalBarChart(s.totalTokensByModel(), DashboardPalette.SUCCESS, 8);

        if (costColumn != null) costColumn.setHeader("Cost " + currencyService.getActiveCurrency());
        statsGrid.setItems(cost.models());
        topExpensive.setItems(cost.topExpensive());

        setStatus(totalCalls + " calls · " + formatUsd(totalCost) + " spent · "
                + paidCalls + " paid · " + cost.models().size() + " models · "
                + window.minutes + "m window");
    }

    private String formatUsd(BigDecimal usd) {
        CurrencyRate rate = currencyService.getActiveRate();
        BigDecimal v = currencyService.convertFromUsd(usd);
        String symbol = rate.symbol() == null ? "$" : rate.symbol();
        if (v == null || v.signum() == 0) return symbol + "0.00";
        if (v.compareTo(new BigDecimal("0.01")) < 0) {
            return String.format(Locale.ROOT, "%s%.6f", symbol, v.doubleValue());
        }
        return String.format(Locale.ROOT, "%s%.4f", symbol, v.doubleValue());
    }

    private static String shorten(String text) {
        if (text == null) return "—";
        return text.length() <= 12 ? text : text.substring(0, 12) + "…";
    }

    private static String safe(String text) { return text == null ? "—" : text; }

}
