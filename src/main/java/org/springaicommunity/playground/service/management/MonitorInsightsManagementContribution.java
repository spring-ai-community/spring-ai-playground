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
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.SpanRecord;
import org.springaicommunity.playground.observability.TraceRecord;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.logs.LogQueryService;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogFilter;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogLine;
import org.springaicommunity.playground.observability.logs.LogQueryService.LogQueryResult;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ConversationStats;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.CostBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ModelCost;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ServerStatusSummary;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ToolBreakdown;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.chat.OllamaMonitorService;
import org.springaicommunity.playground.service.chat.OllamaMonitorService.OllamaStatus;
import org.springaicommunity.playground.service.chat.OllamaMonitorService.RunningModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MonitorInsightsManagementContribution implements ManagementToolContribution {

    private static final int LOG_LINES_DEFAULT = 100;
    private static final int LOG_LINES_MAX = 500;
    private static final int LOG_MESSAGE_LIMIT = 500;
    private static final int SPAN_LIMIT = 200;
    private static final int ATTRIBUTE_LIMIT = 40;
    private static final int ATTRIBUTE_VALUE_LIMIT = 300;
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final ObservabilityQueryService queryService;
    private final ObservabilityRingBuffer traceBuffer;
    private final SystemMetricsSnapshot metricsSnapshot;
    private final ObjectProvider<OllamaMonitorService> ollamaMonitorProvider;
    private final LogQueryService logQueryService;

    public MonitorInsightsManagementContribution(ObservabilityQueryService queryService,
            ObservabilityRingBuffer traceBuffer, SystemMetricsSnapshot metricsSnapshot,
            ObjectProvider<OllamaMonitorService> ollamaMonitorProvider, LogQueryService logQueryService) {
        this.queryService = queryService;
        this.traceBuffer = traceBuffer;
        this.metricsSnapshot = metricsSnapshot;
        this.ollamaMonitorProvider = ollamaMonitorProvider;
        this.logQueryService = logQueryService;
    }

    public record NoInput() {
    }

    public record TracesInput(
            @Nullable @JsonPropertyDescription("Only traces that started at or after this epoch millisecond time.") Long sinceEpochMs,
            @Nullable @JsonPropertyDescription("Only traces with this status: OK, ERROR or CANCELLED.") String status,
            @Nullable @JsonPropertyDescription("Only traces of this conversation id.") String conversationId,
            @Nullable @JsonPropertyDescription("Maximum traces, newest last, default 50, at most 200.") Integer limit) {
    }

    public record TraceInput(@JsonPropertyDescription("Trace id from saip_monitor_traces.") String traceId) {
    }

    public record LogsInput(
            @Nullable @JsonPropertyDescription("Only lines of this level: ERROR, WARN, INFO, DEBUG or TRACE.") String level,
            @Nullable @JsonPropertyDescription("Only lines that contain this text, ignoring case.") String text,
            @Nullable @JsonPropertyDescription("Number of lines from the end, default 100, at most 500.") Integer lines) {
    }

    public record CallCounts(long total, long ok, long error) {
    }

    public record ToolCounts(long total, long ok, long error, Map<String, Long> bySandboxLevel) {
    }

    public record ModelUsage(String model, long calls, long inputTokens, long outputTokens, BigDecimal cost) {
    }

    public record LlmUsage(long calls, long paidCalls, BigDecimal totalCost, List<ModelUsage> models) {
    }

    public record ConversationCounts(int conversations, long messages, long toolCalls, long ragCalls) {
    }

    public record OverviewData(String window, CallCounts mcpCalls, ToolCounts toolCalls, LlmUsage llm,
                               ConversationCounts conversations, ServerStatusSummary servers) {
    }

    public record TraceSummary(String traceId, String conversationId, String provider, String model,
                               long startEpochMs, long durationMs, String status, Long totalTokens,
                               int toolCallCount, List<String> toolNames, List<String> serverNames) {
    }

    public record TracesData(List<TraceSummary> traces, int total, boolean truncated) {
    }

    public record SpanData(String spanId, String parentSpanId, String name, long startEpochMs, long durationMs,
                           String status, Map<String, String> attributes) {
    }

    public record TraceDetail(TraceSummary summary, List<SpanData> spans, int totalSpans, boolean truncated) {
    }

    public record OllamaData(boolean active, boolean reachable, String version, int installedModels,
                             List<String> runningModels, long vramMegabytes) {
    }

    public record SystemData(long heapUsedMegabytes, long heapMaxMegabytes, long threadsLive,
                             double processCpuUsage, double systemCpuUsage, long cpuCount, double loadAverage1m,
                             long uptimeSeconds, long diskFreeMegabytes, long diskTotalMegabytes,
                             long memoryFreeMegabytes, long memoryTotalMegabytes, long httpRequests,
                             OllamaData ollama) {
    }

    public record LogEntry(String time, String level, String logger, String traceId, String message) {
    }

    public record LogsData(List<LogEntry> lines, int matched, boolean truncated, String source) {
    }

    @Override
    public String category() {
        return MonitorManagementContribution.CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(overview(), traces(), traceGet(), system(), logsTail());
    }

    private ManagementTool overview() {
        return ManagementTool.named("saip_monitor_overview", MonitorManagementContribution.CATEGORY,
                        MonitorManagementContribution.WindowInput.class)
                .data(OverviewData.class)
                .description("One-page summary of a time window: MCP tool calls, tool calls with sandbox levels, "
                        + "LLM calls and cost per model, conversation counts and MCP server status. The same "
                        + "numbers as the Observability screens.")
                .idempotent()
                .handle((input, caller) -> {
                    Window window = MonitorManagementContribution.windowOf(input.window());
                    McpBreakdown mcp = this.queryService.mcpBreakdown(window);
                    ToolBreakdown tools = this.queryService.toolBreakdown(window);
                    CostBreakdown cost = this.queryService.costBreakdown(window);
                    ConversationStats conversations = this.queryService.conversationStats(window);
                    OverviewData data = new OverviewData(MonitorManagementContribution.windowName(window),
                            new CallCounts(mcp.total(), mcp.ok(), mcp.error()),
                            new ToolCounts(tools.total(), tools.ok(), tools.error(), tools.sandboxLevelCounts()),
                            new LlmUsage(cost.totalCalls(), cost.paidCalls(), cost.totalCost(),
                                    cost.models().stream().map(this::usageOf).toList()),
                            new ConversationCounts(conversations.conversations().size(),
                                    conversations.totalMessages(), conversations.totalToolCalls(),
                                    conversations.totalRagCalls()),
                            this.queryService.serverStatusSummary());
                    return ManagementResult.ok("Overview of the last " + data.window() + ".", data);
                });
    }

    private ManagementTool traces() {
        return ManagementTool.named("saip_monitor_traces", MonitorManagementContribution.CATEGORY, TracesInput.class)
                .data(TracesData.class)
                .description("Recent request traces of Agentic Chat and the MCP Inspector: model, duration, status, "
                        + "tokens, tool calls. Newest last. Use saip_monitor_trace_get for the spans of one trace.")
                .idempotent()
                .handle((input, caller) -> {
                    long since = input.sinceEpochMs() == null ? 0L : input.sinceEpochMs();
                    List<TraceRecord> matching = this.traceBuffer.snapshot().stream()
                            .filter(trace -> trace.startEpochMs() >= since)
                            .filter(trace -> input.status() == null || input.status().equalsIgnoreCase(trace.status()))
                            .filter(trace -> input.conversationId() == null
                                    || input.conversationId().equals(trace.conversationId()))
                            .toList();
                    int limit = MonitorManagementContribution.limitOf(input.limit());
                    List<TraceSummary> page = matching.subList(Math.max(0, matching.size() - limit), matching.size())
                            .stream().map(MonitorInsightsManagementContribution::summaryOf).toList();
                    return ManagementResult.ok(matching.size() + " trace(s).",
                            new TracesData(page, matching.size(), matching.size() > page.size()));
                });
    }

    private ManagementTool traceGet() {
        return ManagementTool.named("saip_monitor_trace_get", MonitorManagementContribution.CATEGORY,
                        TraceInput.class)
                .data(TraceDetail.class)
                .description("The spans of one trace: what ran, in which order, how long it took and with which "
                        + "outcome. Attribute values are shortened and secrets are masked.")
                .idempotent()
                .handle((input, caller) -> {
                    TraceRecord trace = this.traceBuffer.snapshot().stream()
                            .filter(candidate -> input.traceId().equals(candidate.traceId())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No trace with id '" + input.traceId()
                                    + "' in memory. List them with saip_monitor_traces."));
                    List<SpanRecord> spans = trace.spans() == null ? List.of() : trace.spans();
                    List<SpanData> page = spans.stream().limit(SPAN_LIMIT).map(MonitorInsightsManagementContribution::spanOf)
                            .toList();
                    return ManagementResult.ok("Trace " + input.traceId() + " has " + spans.size() + " span(s).",
                            new TraceDetail(summaryOf(trace), page, spans.size(), spans.size() > page.size()));
                });
    }

    private ManagementTool system() {
        return ManagementTool.named("saip_monitor_system", MonitorManagementContribution.CATEGORY, NoInput.class)
                .data(SystemData.class)
                .description("Health of the machine and the app: heap and system memory, CPU, threads, disk, HTTP "
                        + "request count and the state of Ollama.")
                .idempotent()
                .handle((input, caller) -> {
                    SystemMetricsSnapshot.Snapshot snapshot = this.metricsSnapshot.capture();
                    SystemData data = new SystemData(megabytes(snapshot.jvmHeapUsedBytes),
                            megabytes(snapshot.jvmHeapMaxBytes), snapshot.jvmThreadsLive, snapshot.processCpuUsage,
                            snapshot.systemCpuUsage, snapshot.systemCpuCount, snapshot.systemLoadAverage1m,
                            (long) snapshot.processUptimeSeconds, megabytes(snapshot.diskFreeBytes),
                            megabytes(snapshot.diskTotalBytes), megabytes(snapshot.systemPhysicalMemoryFreeBytes),
                            megabytes(snapshot.systemPhysicalMemoryTotalBytes), snapshot.httpRequestsTotal,
                            ollamaData());
                    return ManagementResult.ok("Heap " + data.heapUsedMegabytes() + " of " + data.heapMaxMegabytes()
                            + " MB, " + data.threadsLive() + " threads.", data);
                });
    }

    private ManagementTool logsTail() {
        return ManagementTool.named("saip_monitor_logs_tail", MonitorManagementContribution.CATEGORY,
                        LogsInput.class)
                .data(LogsData.class)
                .description("The end of the app log, optionally filtered by level or text. Secrets in lines are "
                        + "masked, long lines are shortened.")
                .idempotent()
                .handle((input, caller) -> {
                    LogQueryResult result = this.logQueryService.query(new LogFilter(
                            input.level() == null ? LogQueryService.NO_FILTER_VALUE : input.level().toUpperCase(),
                            null, input.text(), true, false, null, null));
                    if (result.status() != LogQueryService.Status.OK) {
                        return ManagementResult.ok("The log file is not available (" + result.status() + ").",
                                new LogsData(List.of(), 0, false, this.logQueryService.logFile().toString()));
                    }
                    int lines = input.lines() == null ? LOG_LINES_DEFAULT
                            : Math.max(1, Math.min(input.lines(), LOG_LINES_MAX));
                    List<LogLine> matched = result.matched();
                    List<LogEntry> page = matched.subList(Math.max(0, matched.size() - lines), matched.size())
                            .stream().map(MonitorInsightsManagementContribution::entryOf).toList();
                    return ManagementResult.ok(matched.size() + " matching line(s).",
                            new LogsData(page, matched.size(), matched.size() > page.size(),
                                    this.logQueryService.logFile().toString()));
                });
    }

    private OllamaData ollamaData() {
        OllamaMonitorService monitor = this.ollamaMonitorProvider.getIfAvailable();
        OllamaStatus status = monitor == null ? null : monitor.cachedSnapshot();
        if (status == null) {
            return new OllamaData(false, false, null, 0, List.of(), 0);
        }
        return new OllamaData(status.active(), status.reachable(), status.version(), status.installedModels().size(),
                status.runningModels().stream().map(RunningModel::name).toList(),
                status.totalVramBytes() / BYTES_PER_MEGABYTE);
    }

    private ModelUsage usageOf(ModelCost cost) {
        return new ModelUsage(cost.model(), cost.calls(), cost.inputTokens(), cost.outputTokens(), cost.cost());
    }

    private static TraceSummary summaryOf(TraceRecord trace) {
        return new TraceSummary(trace.traceId(), trace.conversationId(), trace.provider(), trace.model(),
                trace.startEpochMs(), trace.durationMs(), trace.status(), trace.totalTokens(), trace.toolCallCount(),
                trace.toolNames() == null ? List.of() : trace.toolNames().stream().sorted().toList(),
                trace.serverNames() == null ? List.of() : trace.serverNames().stream().sorted().toList());
    }

    private static SpanData spanOf(SpanRecord span) {
        return new SpanData(span.spanId(), span.parentSpanId(), span.name(), span.startEpochMs(), span.durationMs(),
                span.status(), maskedAttributes(span.attributes()));
    }

    private static Map<String, String> maskedAttributes(Map<String, String> attributes) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (attributes == null) {
            return masked;
        }
        attributes.entrySet().stream().limit(ATTRIBUTE_LIMIT).forEach(entry -> {
            String value = entry.getValue() == null ? "" : entry.getValue();
            String safe = ManagementSecrets.isSensitiveName(entry.getKey()) ? ManagementSecrets.MASK
                    : ManagementSecrets.maskText(value);
            masked.put(entry.getKey(), safe.length() <= ATTRIBUTE_VALUE_LIMIT ? safe
                    : safe.substring(0, ATTRIBUTE_VALUE_LIMIT) + "...");
        });
        return masked;
    }

    private static LogEntry entryOf(LogLine line) {
        String message = ManagementSecrets.maskText(line.message());
        return new LogEntry(line.time(), line.level(), line.logger(), line.traceId(),
                message.length() <= LOG_MESSAGE_LIMIT ? message : message.substring(0, LOG_MESSAGE_LIMIT) + "...");
    }

    private static long megabytes(double bytes) {
        return (long) (bytes / BYTES_PER_MEGABYTE);
    }

}
