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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.observability.ObservabilityProperties;
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.SpanRecord;
import org.springaicommunity.playground.observability.TraceRecord;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.logs.LogQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ConversationStats;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.CostBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ServerStatusSummary;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ToolBreakdown;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.chat.OllamaMonitorService;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorInsightsManagementContributionTest {

    @TempDir
    Path homeDir;

    private final ObservabilityQueryService queryService = mock(ObservabilityQueryService.class);
    private final ObservabilityRingBuffer traceBuffer = new ObservabilityRingBuffer(new ObservabilityProperties());
    private final SystemMetricsSnapshot metricsSnapshot = mock(SystemMetricsSnapshot.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<OllamaMonitorService> ollama =
            mock(ObjectProvider.class);

    private MonitorInsightsManagementContribution contribution() {
        return new MonitorInsightsManagementContribution(queryService, traceBuffer, metricsSnapshot, ollama,
                new LogQueryService(homeDir));
    }

    private ManagementTool tool(String name) {
        return contribution().tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static TraceRecord trace(String traceId, long start, String status, List<SpanRecord> spans) {
        return new TraceRecord(traceId, "conv-" + traceId, "msg", "ollama", "qwen", start, 10, status, 5L, 6L, 11L,
                "stop", true, 1, false, "user", "sid", Set.of("b_tool", "a_tool"), Set.of("srv"), spans, Map.of());
    }

    @Test
    void tracesAreFilteredLimitedAndNewestLast() {
        traceBuffer.add(trace("t1", 1000, TraceRecord.STATUS_OK, List.of()));
        traceBuffer.add(trace("t2", 2000, TraceRecord.STATUS_ERROR, List.of()));
        traceBuffer.add(trace("t3", 3000, TraceRecord.STATUS_OK, List.of()));

        ManagementResult all = tool("saip_monitor_traces").handle(Map.of("limit", 2), ManagementCaller.ANONYMOUS);
        ManagementResult errors = tool("saip_monitor_traces").handle(Map.of("status", "error"),
                ManagementCaller.ANONYMOUS);
        ManagementResult recent = tool("saip_monitor_traces").handle(Map.of("sinceEpochMs", 2500),
                ManagementCaller.ANONYMOUS);

        MonitorInsightsManagementContribution.TracesData data = (MonitorInsightsManagementContribution.TracesData)
                all.data();
        assertThat(data.total()).isEqualTo(3);
        assertThat(data.truncated()).isTrue();
        assertThat(data.traces()).extracting(MonitorInsightsManagementContribution.TraceSummary::traceId)
                .containsExactly("t2", "t3");
        assertThat(data.traces().getFirst().toolNames()).containsExactly("a_tool", "b_tool");
        assertThat(((MonitorInsightsManagementContribution.TracesData) errors.data()).traces())
                .extracting(MonitorInsightsManagementContribution.TraceSummary::traceId).containsExactly("t2");
        assertThat(((MonitorInsightsManagementContribution.TracesData) recent.data()).traces())
                .extracting(MonitorInsightsManagementContribution.TraceSummary::traceId).containsExactly("t3");
    }

    @Test
    void aTraceShowsItsSpansWithMaskedAndShortenedAttributes() {
        String longValue = "x".repeat(400);
        traceBuffer.add(trace("t1", 1000, TraceRecord.STATUS_OK, List.of(new SpanRecord("s1", null, "spring.ai.tool",
                1000, 5, "OK", Map.of("api_key", "sk-live-1", "note", longValue, "auth",
                        "Bearer abcdefghijklmnop")))));

        ManagementResult result = tool("saip_monitor_trace_get").handle(Map.of("traceId", "t1"),
                ManagementCaller.ANONYMOUS);

        MonitorInsightsManagementContribution.TraceDetail detail = (MonitorInsightsManagementContribution.TraceDetail)
                result.data();
        Map<String, String> attributes = detail.spans().getFirst().attributes();
        assertThat(attributes.get("api_key")).isEqualTo(ManagementSecrets.MASK);
        assertThat(attributes.get("note")).hasSize(303).endsWith("...");
        assertThat(attributes.get("auth")).doesNotContain("abcdefghijklmnop");
        assertThatThrownBy(() -> tool("saip_monitor_trace_get").handle(Map.of("traceId", "nope"),
                ManagementCaller.ANONYMOUS)).hasMessageContaining("saip_monitor_traces");
    }

    @Test
    void logsAreTailedFilteredMaskedAndBounded() throws IOException {
        Path logDirectory = Files.createDirectories(homeDir.resolve("logs"));
        StringBuilder log = new StringBuilder();
        for (int index = 0; index < 30; index++) {
            log.append("2026-09-30 10:00:").append(String.format("%02d", index))
                    .append(".000 [main] INFO  x.Y [user= sid= conv= msg= traceId=t spanId=s] - line ").append(index)
                    .append(index == 29 ? " Authorization: Bearer abcdefghijklmnop" : "").append('\n');
        }
        log.append("2026-09-30 10:01:00.000 [main] ERROR x.Y [user= sid= conv= msg= traceId=t spanId=s] - boom\n");
        Files.writeString(logDirectory.resolve("spring-ai-playground.log"), log.toString());

        ManagementResult tail = tool("saip_monitor_logs_tail").handle(Map.of("lines", 3),
                ManagementCaller.ANONYMOUS);
        ManagementResult errors = tool("saip_monitor_logs_tail").handle(Map.of("level", "error"),
                ManagementCaller.ANONYMOUS);

        MonitorInsightsManagementContribution.LogsData data = (MonitorInsightsManagementContribution.LogsData)
                tail.data();
        assertThat(data.lines()).hasSize(3).extracting(MonitorInsightsManagementContribution.LogEntry::message)
                .anyMatch(message -> message.contains("Bearer ***")).noneMatch(message ->
                        message.contains("abcdefghijklmnop"));
        assertThat(data.matched()).isEqualTo(31);
        assertThat(data.truncated()).isTrue();
        assertThat(((MonitorInsightsManagementContribution.LogsData) errors.data()).lines())
                .extracting(MonitorInsightsManagementContribution.LogEntry::message).containsExactly("boom");
    }

    @Test
    void aMissingLogFileIsReportedNotFailed() {
        ManagementResult result = tool("saip_monitor_logs_tail").handle(Map.of(), ManagementCaller.ANONYMOUS);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("not available");
        assertThat(((MonitorInsightsManagementContribution.LogsData) result.data()).lines()).isEmpty();
    }

    @Test
    void theOverviewCombinesTheSameBreakdownsAsTheScreens() {
        when(queryService.mcpBreakdown(Window.LAST_30M)).thenReturn(new McpBreakdown(Map.of(), Map.of(), Map.of(),
                Map.of(), 8, 2, 0, 0, Map.of(), List.of()));
        when(queryService.toolBreakdown(Window.LAST_30M)).thenReturn(new ToolBreakdown(Map.of(), Map.of("L0", 3L),
                Map.of(), 3, 0, 0, 0, Map.of(), 3, List.of()));
        when(queryService.costBreakdown(Window.LAST_30M)).thenReturn(new CostBreakdown(List.of(), Map.of(),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, 4, new double[0], List.of()));
        ConversationStats stats = mock(ConversationStats.class);
        when(stats.conversations()).thenReturn(List.of());
        when(stats.totalMessages()).thenReturn(7L);
        when(queryService.conversationStats(Window.LAST_30M)).thenReturn(stats);
        when(queryService.serverStatusSummary()).thenReturn(new ServerStatusSummary(Map.of("OK", 1L), 1, 1));

        ManagementResult result = tool("saip_monitor_overview").handle(Map.of("window", "30m"),
                ManagementCaller.ANONYMOUS);

        MonitorInsightsManagementContribution.OverviewData data = (MonitorInsightsManagementContribution.OverviewData)
                result.data();
        assertThat(data.window()).isEqualTo("30m");
        assertThat(data.mcpCalls().total()).isEqualTo(10);
        assertThat(data.toolCalls().bySandboxLevel()).containsEntry("L0", 3L);
        assertThat(data.llm().calls()).isEqualTo(4);
        assertThat(data.conversations().messages()).isEqualTo(7);
        assertThat(data.servers().up()).isEqualTo(1);
    }

}
