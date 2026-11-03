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

import com.vaadin.flow.component.html.Div;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ToolBreakdown;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.webui.observability.components.BaseDashboardTab;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas.LineSeries;
import org.springaicommunity.playground.webui.observability.components.DashboardData;
import org.springaicommunity.playground.webui.observability.components.DashboardLayout;
import org.springaicommunity.playground.webui.observability.components.DashboardPalette;
import org.springaicommunity.playground.webui.observability.components.FormatUtils;
import org.springaicommunity.playground.webui.observability.components.KpiCard;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ToolsTab extends BaseDashboardTab {

    private static final DateTimeFormatter SHORT_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ObservabilityQueryService queryService;
    private final SystemMetricsSnapshot systemMetrics;

    private final KpiCard tracesWithToolsCard = new KpiCard("Traces with tools");
    private final KpiCard totalToolCallsCard = new KpiCard("Total tool calls");
    private final KpiCard distinctToolsCard = new KpiCard("Distinct tools");
    private final KpiCard proxiedCallsCard = new KpiCard("Proxied calls");
    private final KpiCard deniedCallsCard = new KpiCard("Denied calls");
    private final KpiCard p95LatencyCard = new KpiCard("p95 latency");
    private final KpiCard errorRateCard = new KpiCard("Error rate");
    private final KpiCard guardBlocksCard = new KpiCard("Sandbox guard blocks");

    private final ChartCanvas toolCallsStacked = new ChartCanvas();
    private final ChartCanvas toolLatencyChart = new ChartCanvas();
    private final ChartCanvas topToolsBar = new ChartCanvas();
    private final ChartCanvas sandboxLevelBar = new ChartCanvas();
    private final ChartCanvas guardBlocksBar = new ChartCanvas();
    private final ChartCanvas callersBar = new ChartCanvas();

    public ToolsTab(ObservabilityQueryService queryService, SystemMetricsSnapshot systemMetrics) {
        super();
        this.queryService = queryService;
        this.systemMetrics = systemMetrics;

        Div intro = buildIntro(
                "Built-in tools — JS sandbox functions and local @Tool methods, including " +
                "self-loopback through the in-process MCP server, the external tools it proxies, and calls " +
                "external MCP clients make to the built-in server. " +
                "Direct external MCP server calls live in the MCP Servers tab; proxied calls show on both " +
                "tabs and are counted once in Overview. Calls refused at the human-in-the-loop gate are " +
                "counted as denied.");

        Div cardRow = new Div();
        cardRow.getStyle().set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "var(--lumo-space-xs)")
                .set("width", "100%");
        cardRow.add(DashboardLayout.kpiGroupHeader("Calls — volume"), DashboardLayout.kpiGrid(
                tracesWithToolsCard, totalToolCallsCard, distinctToolsCard, proxiedCallsCard));
        cardRow.add(DashboardLayout.kpiGroupHeader("Outcome — quality & safety"), DashboardLayout.kpiGrid(
                deniedCallsCard, p95LatencyCard, errorRateCard, guardBlocksCard));

        Div grid = DashboardLayout.chartGrid();
        grid.add(
                DashboardLayout.chartCard("Tool calls / minute",
                        "top 4 tools over time",
                        "Bar height = tool call count per bucket. Stacked by tool name (top 4 by lifetime). " +
                                "Source: spring.ai.tool spans where network.transport is null/in-process, " +
                                "saip.mcp.server is the self-loopback (Tool Studio's auto-exposure), OR " +
                                "saip.tool.origin is wrapped_external (a proxied external tool).",
                        toolCallsStacked),
                DashboardLayout.chartCard("Tool latency p50 / p95 / p99",
                        "ms per bucket",
                        "Wall-clock duration of each tool span, sorted per bucket then percentile-ed.",
                        toolLatencyChart),
                DashboardLayout.chartCard("Top tools",
                        "by call count",
                        "Lifetime call count per spring.ai.tool.definition.name attribute (top 8).",
                        topToolsBar),
                DashboardLayout.chartCard("Sandbox level",
                        "calls by risk level (L0-L5)",
                        "From sandbox.level span attribute (set by ToolSpecService via EffectivePolicyResolver). " +
                                "L0 = baseline, L5 = critical deny-class removal.",
                        sandboxLevelBar),
                DashboardLayout.chartCard("Sandbox guard blocks",
                        "lifetime — top reasons",
                        "Counter sandbox.guard.blocked — counts every FetchPolicyException / " +
                                "FsPolicyException thrown in SafeHttpFetch and SafeFs, tagged by reason.",
                        guardBlocksBar),
                DashboardLayout.chartCard("Callers",
                        "Agentic Chat vs external MCP clients",
                        "Who made the tool calls. External MCP clients calling the built-in server are listed " +
                                "by client name (saip.mcp.client where saip.via = mcp-server); everything else " +
                                "is Agentic Chat.",
                        callersBar)
        );

        content.add(intro, cardRow, grid);
    }

    @Override
    public void refresh(Window window) {
        ToolBreakdown breakdown = queryService.toolBreakdown(window);
        long windowStart = Instant.now().toEpochMilli() - window.totalMs();
        List<String> labels = DashboardData.bucketLabelsFromWindow(windowStart, window.bucketMs(), window.buckets());

        long total = breakdown.total();
        long executed = breakdown.executed();
        tracesWithToolsCard.setValue(String.valueOf(breakdown.distinctTraces()),
                "Distinct trace IDs containing at least one spring.ai.tool span " +
                        "(in-process, self-loopback or proxied)");
        totalToolCallsCard.setValue(String.valueOf(total),
                "Σ spring.ai.tool span count (in-process + self-loopback + proxied — direct external MCP " +
                        "excluded). Includes calls denied at the human-in-the-loop gate.");
        distinctToolsCard.setValue(String.valueOf(breakdown.toolNameCounts().size()),
                "Distinct spring.ai.tool.definition.name values invoked in window");
        proxiedCallsCard.setValue(String.valueOf(breakdown.proxied()),
                "Part of Total tool calls: calls to external MCP tools exposed on the built-in server " +
                        "(saip.tool.origin = wrapped_external). The same calls show on the MCP Servers tab under " +
                        "their upstream server; Overview counts each call once.");
        deniedCallsCard.setValue(String.valueOf(breakdown.denied()),
                "Part of Total tool calls: calls that were requested but not run because human-in-the-loop " +
                        "approval was declined, timed out or could not be asked (saip.hitl.outcome). They are " +
                        "left out of latency and error rate; the Safety tab shows the decisions by side.");
        if (executed == 0) {
            errorRateCard.setValue("0.0%", "no executed tool calls in window");
        } else {
            errorRateCard.setValue(
                    String.format(Locale.ROOT, "%.1f%%", breakdown.error() * 100.0 / executed),
                    breakdown.error() + " errors of " + executed
                            + " executed tool calls (denied calls excluded) · spring.ai.tool span status");
        }

        int buckets = window.buckets();
        double[] p50 = new double[buckets];
        double[] p95 = new double[buckets];
        double[] p99 = new double[buckets];
        long sampleCountAll = 0;
        long sumAll = 0;
        long[] allLatenciesAcc = new long[(int) Math.min(Integer.MAX_VALUE, executed)];
        int allIdx = 0;
        for (int i = 0; i < buckets; i++) {
            long[] arr = breakdown.latencyPerBucket().get(i).stream().mapToLong(Long::longValue).sorted().toArray();
            if (arr.length > 0) {
                p50[i] = DashboardData.percentile(arr, 50);
                p95[i] = DashboardData.percentile(arr, 95);
                p99[i] = DashboardData.percentile(arr, 99);
                for (long v : arr) {
                    if (allIdx < allLatenciesAcc.length) allLatenciesAcc[allIdx++] = v;
                    sumAll += v;
                }
                sampleCountAll += arr.length;
            }
        }
        if (sampleCountAll > 0) {
            long[] all = new long[allIdx];
            System.arraycopy(allLatenciesAcc, 0, all, 0, allIdx);
            Arrays.sort(all);
            p95LatencyCard.setValue(FormatUtils.formatLatencyMs(DashboardData.percentile(all, 95)),
                    "95th percentile across all tool spans in window (" + sampleCountAll + " samples)");
        } else {
            p95LatencyCard.setValue("—", "No tool calls in window");
        }

        List<LineSeries> stackedSeries = new ArrayList<>();
        String[] palette = ChartCanvas.DEFAULT_PALETTE;
        int idx = 0;
        List<Map.Entry<String, long[]>> topEntries = new ArrayList<>(breakdown.callsByToolPerBucket().entrySet());
        topEntries.sort((a, e) -> Long.compare(
                breakdown.toolNameCounts().getOrDefault(e.getKey(), 0L),
                breakdown.toolNameCounts().getOrDefault(a.getKey(), 0L)));
        for (Map.Entry<String, long[]> e : topEntries) {
            if (idx >= 4) break;
            double[] vals = new double[e.getValue().length];
            for (int i = 0; i < vals.length; i++) vals[i] = e.getValue()[i];
            stackedSeries.add(new LineSeries(e.getKey(), vals, palette[idx % palette.length]));
            idx++;
        }
        if (stackedSeries.isEmpty()) {
            stackedSeries.add(new LineSeries("(no data)", new double[buckets], palette[0]));
        }
        toolCallsStacked.multiStackedBar(labels, stackedSeries);

        toolLatencyChart.multiLineChart(labels, List.of(
                new LineSeries("p50", p50, DashboardPalette.INFO),
                new LineSeries("p95", p95, DashboardPalette.WARN),
                new LineSeries("p99", p99, DashboardPalette.ERROR)
        ), " ms");

        topToolsBar.horizontalBarChart(breakdown.toolNameCounts(), DashboardPalette.SUCCESS, 8);
        sandboxLevelBar.horizontalBarChart(breakdown.sandboxLevelCounts(), DashboardPalette.PRIMARY, 6);

        SystemMetricsSnapshot.Snapshot snap = systemMetrics.capture();
        long totalBlocks = snap.sandboxGuardBlocked.values().stream().mapToLong(Long::longValue).sum();
        guardBlocksCard.setValue(String.valueOf(totalBlocks),
                "Σ sandbox.guard.blocked counter — FetchPolicyException + FsPolicyException blocked attempts (lifetime)");
        guardBlocksBar.horizontalBarChart(snap.sandboxGuardBlocked, DashboardPalette.ERROR, 8);
        callersBar.horizontalBarChart(breakdown.callerCounts(), DashboardPalette.INFO, 8);

        setStatus(total + " tool calls · " + breakdown.proxied() + " proxied · " + breakdown.denied() + " denied · "
                + breakdown.externalClient() + " from external clients · "
                + breakdown.toolNameCounts().size() + " distinct tools · "
                + breakdown.distinctTraces() + " traces · " + totalBlocks + " guard blocks · window: "
                + window.minutes + "m");
    }



}
