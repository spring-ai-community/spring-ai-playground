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
package org.springaicommunity.playground.service.management;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer.RiskEvent;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ServerStatusSummary;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.mcp.client.McpClientService.OAuthSnapshot;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger.Fingerprint;
import org.springaicommunity.playground.service.mcp.risk.WrappedExternalToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@Component
public class MonitorManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "monitor";

    private static final int LIMIT_DEFAULT = 50;
    private static final int LIMIT_MAX = 200;
    private static final Map<String, Window> WINDOWS = Map.of("1m", Window.LAST_1M, "5m", Window.LAST_5M,
            "10m", Window.LAST_10M, "20m", Window.LAST_20M, "30m", Window.LAST_30M, "1h", Window.LAST_1H,
            "3h", Window.LAST_3H);

    private final ObservabilityQueryService queryService;
    private final McpRiskEventRingBuffer riskEvents;
    private final McpToolHashLedger hashLedger;
    private final SystemMetricsSnapshot metricsSnapshot;
    private final ManagementAuditLog auditLog;
    private final MeterRegistry meterRegistry;

    public MonitorManagementContribution(ObservabilityQueryService queryService, McpRiskEventRingBuffer riskEvents,
            McpToolHashLedger hashLedger, SystemMetricsSnapshot metricsSnapshot, ManagementAuditLog auditLog,
            MeterRegistry meterRegistry) {
        this.queryService = queryService;
        this.riskEvents = riskEvents;
        this.hashLedger = hashLedger;
        this.metricsSnapshot = metricsSnapshot;
        this.auditLog = auditLog;
        this.meterRegistry = meterRegistry;
    }

    public record NoInput() {
    }

    public record WindowInput(
            @Nullable @JsonPropertyDescription("Time window: 1m, 5m, 10m, 20m, 30m, 1h (default) or 3h.") String window) {
    }

    public record EventsInput(
            @Nullable @JsonPropertyDescription("Only events at or after this epoch millisecond time.") Long sinceEpochMs,
            @Nullable @JsonPropertyDescription("Only events of this severity, for example warn or info.") String severity,
            @Nullable @JsonPropertyDescription("Only events of this type, for example MANAGEMENT or HASH_LEDGER_MISMATCH.") String type,
            @Nullable @JsonPropertyDescription("Maximum events, newest last, default 50, at most 200.") Integer limit) {
    }

    public record LogInput(
            @Nullable @JsonPropertyDescription("Only entries at or after this epoch millisecond time.") Long sinceEpochMs,
            @Nullable @JsonPropertyDescription("Maximum entries, newest last, default 50, at most 200.") Integer limit) {
    }

    public record ReviewPending(String server, String tool, String status) {
    }

    public record McpStatusData(ServerStatusSummary summary, List<OAuthSnapshot> oauth,
                                List<ReviewPending> definitionReviewPending) {
    }

    public record ToolCallsData(String window, long total, long ok, long error, long denied, long proxied,
                                Map<String, Long> byServer, Map<String, Long> byTool,
                                Map<String, Long> byTransport, Map<String, Long> byCaller) {
    }

    public record ProxiedCall(String exposedAs, String server, String tool, long ok, long error, long totalMs,
                              long averageMs) {
    }

    public record ProxiedCallsData(long total, long failed, List<ProxiedCall> calls) {
    }

    public record EventsData(List<RiskEvent> events, int total, boolean truncated) {
    }

    public record HitlData(Map<String, Long> byOutcome, Map<String, Long> bySideAndOutcome) {
    }

    public record LogData(List<ManagementAuditLog.Entry> entries, int total, boolean truncated) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(mcpStatus(), toolCalls(), proxiedCalls(), riskEvents(), hitl(), managementLog());
    }

    private ManagementTool mcpStatus() {
        return ManagementTool.named("saip_monitor_mcp_status", CATEGORY, NoInput.class).data(McpStatusData.class)
                .description("Connection status counts of the registered MCP servers, OAuth token state, and "
                        + "exposed tools whose definition changed and awaits review.")
                .idempotent()
                .handle((input, caller) -> {
                    List<ReviewPending> pending = this.hashLedger.snapshot().stream()
                            .filter(fingerprint -> fingerprint.status() != Fingerprint.LifecycleStatus.ACTIVE)
                            .map(fingerprint -> new ReviewPending(fingerprint.serverId(), fingerprint.toolName(),
                                    fingerprint.status().name()))
                            .toList();
                    ServerStatusSummary summary = this.queryService.serverStatusSummary();
                    return ManagementResult.ok(summary.up() + " of " + summary.total() + " server(s) are up.",
                            new McpStatusData(summary, this.queryService.oauthSnapshots(), pending));
                });
    }

    private ManagementTool toolCalls() {
        return ManagementTool.named("saip_monitor_tool_calls", CATEGORY, WindowInput.class)
                .data(ToolCallsData.class)
                .description("Calls that reached tools of registered MCP servers in a time window: totals, "
                        + "failures and counts per server, upstream tool, transport and caller (Agentic Chat or "
                        + "the external client's name). Covers calls Agentic Chat made directly and calls made "
                        + "through proxied tools by Agentic Chat or external clients (counted in proxied). "
                        + "denied = proxied calls this app refused at the human-in-the-loop gate; they never "
                        + "reached a server and are not in total. Per exposed tool totals since start: "
                        + "saip_monitor_proxied_calls.")
                .idempotent()
                .handle((input, caller) -> {
                    Window window = windowOf(input.window());
                    McpBreakdown breakdown = this.queryService.mcpBreakdown(window);
                    return ManagementResult.ok(breakdown.total() + " call(s), " + breakdown.error() + " failed.",
                            new ToolCallsData(windowName(window), breakdown.total(), breakdown.ok(),
                                    breakdown.error(), breakdown.denied(), breakdown.proxied(),
                                    breakdown.serverCounts(),
                                    breakdown.toolCounts(), breakdown.transportCounts(),
                                    breakdown.callerCounts()));
                });
    }

    private ManagementTool proxiedCalls() {
        return ManagementTool.named("saip_monitor_proxied_calls", CATEGORY, NoInput.class)
                .data(ProxiedCallsData.class)
                .description("Calls external MCP clients made to the tools this app proxies from registered MCP "
                        + "servers, since the app started: per exposed tool the successful and failed calls and "
                        + "how long they took. Calls a person declined are not counted; see saip_monitor_hitl.")
                .idempotent()
                .handle((input, caller) -> {
                    Map<String, long[]> byAlias = new TreeMap<>();
                    Map<String, String[]> origin = new HashMap<>();
                    for (Timer timer : this.meterRegistry.find(WrappedExternalToolCallback.PROXIED_CALL_METER)
                            .timers()) {
                        String alias = timer.getId().getTag("alias");
                        long[] counts = byAlias.computeIfAbsent(alias, key -> new long[3]);
                        boolean failed = "error".equals(timer.getId().getTag("outcome"));
                        counts[failed ? 1 : 0] += timer.count();
                        counts[2] += (long) timer.totalTime(TimeUnit.MILLISECONDS);
                        origin.put(alias, new String[] {timer.getId().getTag("server"),
                                timer.getId().getTag("tool")});
                    }
                    List<ProxiedCall> calls = byAlias.entrySet().stream().map(entry -> {
                        long[] counts = entry.getValue();
                        long all = counts[0] + counts[1];
                        return new ProxiedCall(entry.getKey(), origin.get(entry.getKey())[0],
                                origin.get(entry.getKey())[1], counts[0], counts[1], counts[2],
                                all == 0 ? 0 : counts[2] / all);
                    }).sorted(Comparator.comparingLong((ProxiedCall call) -> call.ok() + call.error()).reversed())
                            .toList();
                    long total = calls.stream().mapToLong(call -> call.ok() + call.error()).sum();
                    long failed = calls.stream().mapToLong(ProxiedCall::error).sum();
                    return ManagementResult.ok(total + " proxied call(s), " + failed + " failed.",
                            new ProxiedCallsData(total, failed, calls));
                });
    }

    private ManagementTool riskEvents() {
        return ManagementTool.named("saip_monitor_risk_events", CATEGORY, EventsInput.class)
                .data(EventsData.class)
                .description("Recent safety events: tool description poisoning hits, changed tool definitions, "
                        + "risk floor overrides, composition changes and management tool calls.")
                .idempotent()
                .handle((input, caller) -> {
                    long since = input.sinceEpochMs() == null ? 0L : input.sinceEpochMs();
                    List<RiskEvent> matching = this.riskEvents.snapshot().stream()
                            .filter(event -> event.epochMs() >= since)
                            .filter(event -> input.severity() == null
                                    || input.severity().equalsIgnoreCase(event.severity()))
                            .filter(event -> input.type() == null || input.type().equalsIgnoreCase(event.type()))
                            .toList();
                    int limit = limitOf(input.limit());
                    List<RiskEvent> page = matching.subList(Math.max(0, matching.size() - limit), matching.size());
                    return ManagementResult.ok(matching.size() + " event(s).",
                            new EventsData(page, matching.size(), matching.size() > page.size()));
                });
    }

    private ManagementTool hitl() {
        return ManagementTool.named("saip_monitor_hitl", CATEGORY, NoInput.class).data(HitlData.class)
                .description("How human approval requests were answered since the app started: approved, "
                        + "declined, denied and timed out, per side (chat or MCP server).")
                .idempotent()
                .handle((input, caller) -> {
                    SystemMetricsSnapshot.Snapshot snapshot = this.metricsSnapshot.capture();
                    return ManagementResult.ok("Approval decisions since start.",
                            new HitlData(snapshot.mcpHitlByOutcome, snapshot.mcpHitlBySideOutcome));
                });
    }

    private ManagementTool managementLog() {
        return ManagementTool.named("saip_monitor_management_log", CATEGORY, LogInput.class).data(LogData.class)
                .description("Audit trail of management tool calls: time, client, tool, approval level, outcome "
                        + "(OK, ERROR, REFUSED, DECLINED, DENIED) and a masked argument summary.")
                .idempotent()
                .handle((input, caller) -> {
                    long since = input.sinceEpochMs() == null ? 0L : input.sinceEpochMs();
                    int limit = limitOf(input.limit());
                    List<ManagementAuditLog.Entry> all = this.auditLog.latest(since, Integer.MAX_VALUE);
                    List<ManagementAuditLog.Entry> page = all.subList(Math.max(0, all.size() - limit), all.size());
                    return ManagementResult.ok(all.size() + " entr" + (all.size() == 1 ? "y." : "ies."),
                            new LogData(page, all.size(), all.size() > page.size()));
                });
    }

    private static int limitOf(Integer requested) {
        return requested == null ? LIMIT_DEFAULT : Math.max(1, Math.min(requested, LIMIT_MAX));
    }

    private static Window windowOf(String text) {
        if (text == null || text.isBlank()) {
            return Window.LAST_1H;
        }
        Window window = WINDOWS.get(text.trim().toLowerCase(Locale.ROOT));
        if (window == null) {
            throw new IllegalArgumentException("window must be one of 1m, 5m, 10m, 20m, 30m, 1h, 3h.");
        }
        return window;
    }

    private static String windowName(Window window) {
        return WINDOWS.entrySet().stream().filter(entry -> entry.getValue() == window).map(Map.Entry::getKey)
                .findFirst().orElse(window.name());
    }

}
