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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springaicommunity.playground.observability.VectorOperationLog;
import org.springaicommunity.playground.observability.VectorOperationLog.RecentQuery;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.VectorStats;
import org.springaicommunity.playground.observability.ObservabilityTimeSeries;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.webui.observability.components.BaseDashboardTab;
import org.springaicommunity.playground.webui.observability.components.ChartCanvas;
import org.springaicommunity.playground.webui.observability.components.DashboardData;
import org.springaicommunity.playground.webui.observability.components.DashboardLayout;
import org.springaicommunity.playground.webui.observability.components.DashboardPalette;
import org.springaicommunity.playground.webui.observability.components.FormatUtils;
import org.springaicommunity.playground.webui.observability.components.KpiCard;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VectorTab extends BaseDashboardTab {

    private static final DateTimeFormatter RECENT_QUERY_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObservabilityQueryService queryService;
    private final ObservabilityTimeSeries timeSeries;

    private final KpiCard tracesWithRagCard = new KpiCard("Traces with RAG");
    private final KpiCard totalQueriesCard = new KpiCard("Vector queries");
    private final KpiCard avgTopKCard = new KpiCard("Avg top_k");
    private final KpiCard avgQueryLatencyCard = new KpiCard("Avg query latency");

    private final ChartCanvas opsByTypeChart = new ChartCanvas();
    private final ChartCanvas queryLatencyChart = new ChartCanvas();
    private final ChartCanvas topKHistogramChart = new ChartCanvas();
    private final ChartCanvas similarityHistogramChart = new ChartCanvas();
    private final ChartCanvas dbSystemsDonut = new ChartCanvas();
    private final ChartCanvas avgReturnedDocsBar = new ChartCanvas();
    private final VerticalLayout recentQueriesList = new VerticalLayout();

    public VectorTab(ObservabilityQueryService queryService, ObservabilityTimeSeries timeSeries) {
        super();
        this.queryService = queryService;
        this.timeSeries = timeSeries;

        Div cardRow = DashboardLayout.kpiGrid(tracesWithRagCard, totalQueriesCard,
                avgTopKCard, avgQueryLatencyCard);

        Div grid = DashboardLayout.chartGrid();
        grid.add(
                DashboardLayout.chartCard("Operations / minute", "query / add / delete",
                        "db.vector.client.operation spans per bucket, stacked by db.operation.name. " +
                                "Reflects RAG query traffic plus index updates.",
                        opsByTypeChart),
                DashboardLayout.chartCard("Query latency p50 / p95 / p99", "ms per bucket",
                        "Wall-clock duration of db.vector.client.operation spans (query only), percentile-ed per bucket.",
                        queryLatencyChart),
                DashboardLayout.chartCard("top_k distribution", "requested top_k buckets",
                        "db.vector.query.top_k attribute on query spans — how many docs the LLM asked the vector store for.",
                        topKHistogramChart),
                DashboardLayout.chartCard("Similarity threshold distribution", "0.0 = any, 1.0 = exact",
                        "db.vector.query.similarity_threshold attribute. Higher = stricter match.",
                        similarityHistogramChart),
                DashboardLayout.chartCard("DB systems", "from db.system",
                        "db.system span attribute (simple_vector_store, pgvector, chroma, …).",
                        dbSystemsDonut),
                DashboardLayout.chartCard("Avg returned docs by db", "from db.vector.query.response",
                        "Mean of db.vector.query.response.documents.count per backend.",
                        avgReturnedDocsBar)
        );

        this.recentQueriesList.setPadding(false);
        this.recentQueriesList.setSpacing(false);
        grid.add(DashboardLayout.chartCard("Recent searches", "last " + VectorOperationLog.RECENT_QUERY_LIMIT
                        + ", kept in memory only",
                "Query text of the latest document-store searches. Held in memory, never written to disk, "
                        + "and cleared on restart.",
                this.recentQueriesList));

        content.add(cardRow, grid);
    }

    private void refreshRecentQueries() {
        this.recentQueriesList.removeAll();
        List<RecentQuery> recentQueries = this.queryService.recentVectorQueries();
        if (recentQueries.isEmpty()) {
            this.recentQueriesList.add(recentQueryLine("No searches yet."));
            return;
        }
        for (RecentQuery recentQuery : recentQueries) {
            String returnedDocuments = recentQuery.returnedDocuments() == null
                    ? "?" : recentQuery.returnedDocuments().toString();
            String time = RECENT_QUERY_TIME.format(Instant.ofEpochMilli(recentQuery.epochMs()));
            this.recentQueriesList.add(recentQueryLine(time + " · " + returnedDocuments + " docs · "
                    + recentQuery.durationMs() + " ms · " + recentQuery.text()));
        }
    }

    private static Span recentQueryLine(String text) {
        Span line = new Span(text);
        line.getStyle().set("font-size", "var(--lumo-font-size-s)").set("padding", "2px 0")
                .set("overflow-wrap", "anywhere");
        return line;
    }

    @Override
    public void refresh(Window window) {
        ObservabilityTimeSeries.Series s = timeSeries.compute(window);
        List<String> labels = DashboardData.bucketLabelsFromStarts(s.bucketStartMs());
        int buckets = window.buckets();
        VectorStats vectorStats = queryService.vectorStats(window);
        long totalQueries = vectorStats.totalQueries();
        long topKSamples = vectorStats.topKSamples();
        long totalTopK = vectorStats.totalTopK();
        long latencySamples = vectorStats.latencySamples();
        long totalLatencyMs = vectorStats.totalLatencyMs();

        tracesWithRagCard.setValue(String.valueOf(s.tracesWithRag()),
                "Distinct trace IDs containing at least one db.vector.client.operation span");
        totalQueriesCard.setValue(String.valueOf(totalQueries),
                "Σ db.vector.client.operation spans where db.operation.name = query");
        avgTopKCard.setValue(topKSamples == 0 ? "—" : String.valueOf(totalTopK / topKSamples),
                "Mean of db.vector.query.top_k attribute across query spans (" + topKSamples + " samples)");
        avgQueryLatencyCard.setValue(latencySamples == 0 ? "—"
                : FormatUtils.formatLatencyMs((double) totalLatencyMs / latencySamples),
                "Mean wall-clock duration of query spans (" + latencySamples + " samples)");

        opsByTypeChart.multiStackedBar(labels, List.of(
                new ChartCanvas.LineSeries("query", DashboardData.toDouble(vectorStats.queryPerBucket()), DashboardPalette.INFO),
                new ChartCanvas.LineSeries("add", DashboardData.toDouble(vectorStats.addPerBucket()), DashboardPalette.SUCCESS),
                new ChartCanvas.LineSeries("delete", DashboardData.toDouble(vectorStats.deletePerBucket()), DashboardPalette.ERROR)
        ));

        double[] p50 = new double[buckets];
        double[] p95 = new double[buckets];
        double[] p99 = new double[buckets];
        for (int i = 0; i < buckets; i++) {
            long[] arr = vectorStats.latencyPerBucket().get(i).stream().mapToLong(Long::longValue).sorted().toArray();
            if (arr.length > 0) {
                p50[i] = DashboardData.percentile(arr, 50);
                p95[i] = DashboardData.percentile(arr, 95);
                p99[i] = DashboardData.percentile(arr, 99);
            }
        }
        queryLatencyChart.multiLineChart(labels, List.of(
                new ChartCanvas.LineSeries("p50", p50, DashboardPalette.INFO),
                new ChartCanvas.LineSeries("p95", p95, DashboardPalette.WARN),
                new ChartCanvas.LineSeries("p99", p99, DashboardPalette.ERROR)
        ), "ms");

        Map<String, Long> topKLabeled = new LinkedHashMap<>();
        vectorStats.topKDistribution().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> topKLabeled.put("k=" + e.getKey(), e.getValue()));
        topKHistogramChart.horizontalBarChart(topKLabeled, DashboardPalette.PRIMARY, 10);

        similarityHistogramChart.horizontalBarChart(vectorStats.similarityBands(), DashboardPalette.INFO, 10);

        dbSystemsDonut.donutChart(vectorStats.dbSystems(), ChartCanvas.DEFAULT_PALETTE);

        avgReturnedDocsBar.horizontalBarChart(vectorStats.avgReturnedDocs(), DashboardPalette.SUCCESS, 6);

        refreshRecentQueries();

        setStatus(vectorStats.totalOps() + " vector ops · " + vectorStats.dbSystems().size() + " db systems · "
                + s.tracesWithRag() + " RAG traces · window: " + window.minutes + "m");
    }


}
