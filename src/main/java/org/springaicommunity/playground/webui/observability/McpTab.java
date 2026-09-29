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

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.OAuthSummary;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ServerStatusSummary;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.mcp.client.McpClientService.OAuthSnapshot;
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

public class McpTab extends BaseDashboardTab {

    private static final DateTimeFormatter SHORT_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ObservabilityQueryService queryService;
    private final SystemMetricsSnapshot systemMetrics;

    private final KpiCard activeServersCard = new KpiCard("MCP servers");
    private final KpiCard serversUpCard = new KpiCard("Servers up");
    private final KpiCard mcpCallsCard = new KpiCard("MCP tool calls");
    private final KpiCard distinctToolsCard = new KpiCard("Distinct tools");
    private final KpiCard p95LatencyCard = new KpiCard("p95 latency");
    private final KpiCard errorRateCard = new KpiCard("Error rate");
    private final KpiCard oauthAuthorizedCard = new KpiCard("OAuth authorized");
    private final KpiCard oauthAwaitingCard = new KpiCard("OAuth awaiting");
    private final KpiCard oauthErroredCard = new KpiCard("OAuth errored / offline");
    private final KpiCard oauthExpiringCard = new KpiCard("OAuth expiring < 5 min");

    private final Grid<OAuthSnapshot> oauthGrid = new Grid<>(OAuthSnapshot.class, false);

    private final ChartCanvas mcpCallsStacked = new ChartCanvas();
    private final ChartCanvas latencyChart = new ChartCanvas();
    private final ChartCanvas topServersBar = new ChartCanvas();
    private final ChartCanvas topToolsBar = new ChartCanvas();
    private final ChartCanvas transportDonut = new ChartCanvas();
    private final ChartCanvas serverStatusBar = new ChartCanvas();
    private final ChartCanvas lifecycleStacked = new ChartCanvas();

    public McpTab(ObservabilityQueryService queryService, SystemMetricsSnapshot systemMetrics) {
        super();
        this.queryService = queryService;
        this.systemMetrics = systemMetrics;

        Div serverKpis = DashboardLayout.kpiGrid(activeServersCard, serversUpCard, mcpCallsCard,
                distinctToolsCard, p95LatencyCard, errorRateCard);
        Div oauthKpis = DashboardLayout.kpiGrid(oauthAuthorizedCard, oauthAwaitingCard,
                oauthErroredCard, oauthExpiringCard);
        Div cardRow = new Div();
        cardRow.getStyle().set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "var(--lumo-space-xs)")
                .set("width", "100%");
        cardRow.add(DashboardLayout.kpiGroupHeader("Servers — volume & quality"), serverKpis);
        cardRow.add(DashboardLayout.kpiGroupHeader("OAuth state"), oauthKpis);

        configureOAuthGrid();
        Div oauthGridCard = new Div(oauthGrid);
        oauthGridCard.getStyle()
                .set("background", "var(--lumo-base-color)")
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s)");
        H3 oauthHeader = new H3("OAuth state");
        oauthHeader.getStyle().set("margin", "var(--lumo-space-l) 0 var(--lumo-space-xs) 0")
                .set("font-size", "var(--lumo-font-size-l)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-weight", "600");

        Div grid = DashboardLayout.chartGrid();
        grid.add(
                DashboardLayout.chartCard("MCP calls / minute",
                        "by transport — STDIO / HTTP / SSE",
                        "Bar height = spring.ai.tool span count per bucket (external MCP only — " +
                                "self-loopback excluded). Stacked by network.transport tag.",
                        mcpCallsStacked),
                DashboardLayout.chartCard("Latency p50 / p95 / p99",
                        "ms — across transports",
                        "Wall-clock duration of MCP tool spans, percentile-ed per bucket (exact).",
                        latencyChart),
                DashboardLayout.chartCard("Top servers",
                        "by call count",
                        "spring.ai.tool spans grouped by saip.mcp.server attribute (top 8 in window).",
                        topServersBar),
                DashboardLayout.chartCard("Top MCP tools",
                        "by call count",
                        "Same MCP spans grouped by spring.ai.tool.definition.name (top 8).",
                        topToolsBar),
                DashboardLayout.chartCard("Transport mix",
                        "STDIO / HTTP / SSE",
                        "Distribution by network.transport tag — shows which transports the workload uses.",
                        transportDonut),
                DashboardLayout.chartCard("Server status",
                        "current state by server",
                        "Live read of McpClientService.snapshotStatuses() — OK / OFFLINE / ERROR / " +
                                "AWAITING_AUTHORIZATION. Self-loopback excluded.",
                        serverStatusBar),
                DashboardLayout.chartCard("Lifecycle events by transport class",
                        "stdio (process) vs remote (network) — connect / disconnect / error / auth",
                        "Counter mcp.server.lifecycle aggregated per outcome × {STDIO, remote}. " +
                                "STDIO failures are process crashes; remote are network drops + reconnects.",
                        lifecycleStacked)
        );

        content.add(cardRow, grid, oauthHeader, oauthGridCard);
    }

    private void configureOAuthGrid() {
        oauthGrid.addColumn(OAuthSnapshot::serverName).setHeader("Server").setAutoWidth(true);
        oauthGrid.addColumn(OAuthSnapshot::transport).setHeader("Transport").setAutoWidth(true);
        oauthGrid.addColumn(OAuthSnapshot::status).setHeader("Status").setAutoWidth(true);
        oauthGrid.addColumn(o -> o.accessTokenExpiresAtMs() == null
                ? "—" : formatRelativeExpiry(o.accessTokenExpiresAtMs()))
                .setHeader("Token expires").setAutoWidth(true);
        oauthGrid.addColumn(o -> o.hasRefreshToken() ? "yes" : "no")
                .setHeader("Refresh").setAutoWidth(true);
        oauthGrid.addComponentColumn(o -> {
            if (o.authorizationUrl() == null || o.authorizationUrl().isBlank()) {
                return new Div();
            }
            Anchor link = new Anchor(o.authorizationUrl(), "");
            link.setTarget("_blank");
            link.add(new Button("Authorize", VaadinIcon.UNLOCK.create()));
            return link;
        }).setHeader("").setAutoWidth(true);
        oauthGrid.setAllRowsVisible(true);
    }

    @Override
    public void refresh(Window window) {
        McpBreakdown breakdown = queryService.mcpBreakdown(window);
        long windowStart = Instant.now().toEpochMilli() - window.totalMs();
        List<String> labels = DashboardData.bucketLabelsFromWindow(windowStart, window.bucketMs(), window.buckets());

        long total = breakdown.ok() + breakdown.error();
        activeServersCard.setValue(String.valueOf(breakdown.serverCounts().size()),
                "Distinct saip.mcp.server values invoked in window (external MCP only, self-loopback excluded)");
        mcpCallsCard.setValue(String.valueOf(total),
                "Σ spring.ai.tool span count where network.transport ≠ in-process and saip.mcp.server ≠ self-loopback");
        distinctToolsCard.setValue(String.valueOf(breakdown.toolCounts().size()),
                "Distinct spring.ai.tool.definition.name values invoked through external MCP servers");
        if (total == 0) {
            errorRateCard.setValue("0.0%", "no external MCP calls in window");
        } else {
            errorRateCard.setValue(
                    String.format(Locale.ROOT, "%.1f%%", breakdown.error() * 100.0 / total),
                    breakdown.error() + " errors of " + total + " MCP tool calls · spring.ai.tool span status");
        }

        int buckets = window.buckets();
        double[] p50 = new double[buckets];
        double[] p95 = new double[buckets];
        double[] p99 = new double[buckets];
        List<Long> allLatencies = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            long[] arr = breakdown.latencyPerBucket().get(i).stream().mapToLong(Long::longValue).sorted().toArray();
            if (arr.length > 0) {
                p50[i] = DashboardData.percentile(arr, 50);
                p95[i] = DashboardData.percentile(arr, 95);
                p99[i] = DashboardData.percentile(arr, 99);
                for (long v : arr) allLatencies.add(v);
            }
        }
        if (allLatencies.isEmpty()) {
            p95LatencyCard.setValue("—", "No MCP calls in window");
        } else {
            long[] all = allLatencies.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(all);
            p95LatencyCard.setValue(FormatUtils.formatLatencyMs(DashboardData.percentile(all, 95)),
                    "95th percentile across all MCP tool spans in window (" + all.length + " samples)");
        }

        List<LineSeries> stackedSeries = new ArrayList<>();
        String[] palette = ChartCanvas.DEFAULT_PALETTE;
        int idx = 0;
        List<Map.Entry<String, long[]>> transportEntries = new ArrayList<>(breakdown.callsByTransportPerBucket().entrySet());
        transportEntries.sort((a, e) -> Long.compare(
                breakdown.transportCounts().getOrDefault(e.getKey(), 0L),
                breakdown.transportCounts().getOrDefault(a.getKey(), 0L)));
        for (Map.Entry<String, long[]> e : transportEntries) {
            if (idx >= 4) break;
            double[] vals = new double[e.getValue().length];
            for (int i = 0; i < vals.length; i++) vals[i] = e.getValue()[i];
            stackedSeries.add(new LineSeries(e.getKey(), vals, palette[idx % palette.length]));
            idx++;
        }
        if (stackedSeries.isEmpty()) {
            stackedSeries.add(new LineSeries("(no data)", new double[buckets], palette[0]));
        }
        mcpCallsStacked.multiStackedBar(labels, stackedSeries);

        latencyChart.multiLineChart(labels, List.of(
                new LineSeries("p50", p50, DashboardPalette.INFO),
                new LineSeries("p95", p95, DashboardPalette.WARN),
                new LineSeries("p99", p99, DashboardPalette.ERROR)
        ), " ms");

        topServersBar.horizontalBarChart(breakdown.serverCounts(), DashboardPalette.PRIMARY, 8);
        topToolsBar.horizontalBarChart(breakdown.toolCounts(), DashboardPalette.SUCCESS, 8);
        transportDonut.donutChart(breakdown.transportCounts(), ChartCanvas.DEFAULT_PALETTE);

        ServerStatusSummary servers = queryService.serverStatusSummary();
        long up = servers.up();
        serversUpCard.setValue(up + " / " + servers.total(),
                "External MCP servers currently OK / total configured · from McpClientService.snapshotStatuses()");
        serverStatusBar.horizontalBarChart(servers.statusCounts(), DashboardPalette.INFO, 6);

        SystemMetricsSnapshot.Snapshot snap = systemMetrics.capture();
        Map<String, long[]> lifecycleByTransportClass = ObservabilityQueryService.lifecycleByTransportClass(
                snap.mcpLifecycleByServer, snap.mcpLifecycleByOutcome);
        lifecycleStacked.horizontalStackedBar(lifecycleByTransportClass,
                List.of("STDIO (process)", "Remote (HTTP/SSE)"),
                List.of(DashboardPalette.INFO, DashboardPalette.SUCCESS));

        List<OAuthSnapshot> oauthRows = queryService.oauthSnapshots();
        OAuthSummary oauth = queryService.oauthSummary(oauthRows);
        long authorized = oauth.authorized();
        long awaiting = oauth.awaiting();
        long errored = oauth.errored();
        long expiringSoon = oauth.expiringSoon();
        oauthAuthorizedCard.setValue(String.valueOf(authorized),
                "MCP servers with OAuth status = OK and an actual access/refresh token bound. " +
                        "Servers without OAuth config (self-loopback, unauthenticated HTTP) are excluded.");
        oauthAwaitingCard.setValue(String.valueOf(awaiting),
                "MCP servers in AWAITING_AUTHORIZATION — need user to click Authorize");
        oauthErroredCard.setValue(String.valueOf(errored),
                "MCP servers in ERROR / OFFLINE state");
        oauthExpiringCard.setValue(String.valueOf(expiringSoon),
                "MCP OAuth access tokens that will expire in less than 5 minutes (likely needs refresh)");
        oauthGrid.setItems(oauthRows);

        setStatus(total + " MCP calls · " + breakdown.serverCounts().size() + " active servers · "
                + servers.total() + " configured · " + up + " up · "
                + authorized + " OAuth authorized · " + awaiting + " awaiting · "
                + "window: " + window.minutes + "m");
    }

    private static String formatRelativeExpiry(long expiresAtMs) {
        long deltaMs = expiresAtMs - System.currentTimeMillis();
        if (deltaMs <= 0) {
            return "expired " + humanizeDuration(-deltaMs) + " ago";
        }
        return "in " + humanizeDuration(deltaMs);
    }

    private static String humanizeDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        long d = h / 24;
        return d + "d " + (h % 24) + "h";
    }


}
