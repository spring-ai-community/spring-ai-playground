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
package org.springaicommunity.playground.observability.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.observability.ObservabilityProperties;
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.SpanRecord;
import org.springaicommunity.playground.observability.TraceRecord;
import org.springaicommunity.playground.observability.VectorOperationLog;
import org.springaicommunity.playground.observability.VectorOperationLog.RecentQuery;
import org.springaicommunity.playground.observability.VectorOperationLog.VectorOperation;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.pricing.ModelPricing;
import org.springaicommunity.playground.observability.pricing.ModelPricingService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ConversationStats;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.CostBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.OAuthSummary;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ToolBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.ToolMcpBreakdown;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.VectorOps;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.VectorStats;
import org.springaicommunity.playground.service.PersistenceExecutor;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.OAuthSnapshot;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityQueryServiceTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String LOOPBACK = "spring-ai-playground-test";

    @TempDir
    Path home;

    private PersistenceExecutor executor;
    private ObservabilityRingBuffer buffer;
    private ObservabilityQueryService service;

    @BeforeEach
    void seed() {
        this.executor = new PersistenceExecutor();
        this.buffer = new ObservabilityRingBuffer(new ObservabilityProperties());
        ModelPricingService pricing = new ModelPricingService(this.home, this.executor);
        pricing.upsert(new ModelPricing("paid", new BigDecimal("2"), new BigDecimal("4")));
        McpClientService mcpClientService = mock(McpClientService.class);
        when(mcpClientService.isSelfLoopback(anyString())).thenAnswer(inv -> LOOPBACK.equals(inv.getArgument(0)));
        VectorOperationLog vectorOperationLog = new VectorOperationLog();
        this.service = new ObservabilityQueryService(this.buffer, mcpClientService, pricing, vectorOperationLog);

        long recent = NOW - Duration.ofMinutes(1).toMillis();
        this.buffer.add(trace("t1", "c1", "paid", "openai", recent, 1000L, 500L, 1, List.of(
                span("s1", "spring.ai.tool", recent, 30, "OK", Map.of("spring.ai.tool.definition.name", "getWeather",
                        "sandbox.level", "L0")),
                span("s2", "spring.ai.tool", recent, 120, "ERROR", Map.of("spring.ai.tool.definition.name", "echo",
                        "network.transport", "http", "saip.mcp.server", "everything")),
                span("s3", "spring.ai.tool", recent, 40, "OK", Map.of("spring.ai.tool.definition.name", "getTime",
                        "network.transport", "http", "saip.mcp.server", LOOPBACK)))));
        vectorOperationLog.record(new VectorOperation(recent, 15, "query", "simple", 4L, 0.35, 2, "c1", false),
                "wifi password");
        vectorOperationLog.record(new VectorOperation(NOW - Duration.ofHours(5).toMillis(), 9, "add", "simple",
                null, null, null, null, false), null);
        long earlier = NOW - Duration.ofMinutes(3).toMillis();
        this.buffer.add(trace("t2", "c1", "free", "ollama", earlier, 10L, 20L, 4, List.of(
                span("s5", "spring.ai.tool", earlier, 10, "OK", null))));
        this.buffer.add(trace("t3", "c2", "free", "ollama", NOW - Duration.ofHours(5).toMillis(), 1L, 1L, 0,
                List.of()));
    }

    @AfterEach
    void drain() throws Exception {
        this.executor.awaitCompletion(Duration.ofSeconds(5));
    }

    private static TraceRecord trace(String id, String conversationId, String model, String provider, long start,
            Long in, Long out, int toolCalls, List<SpanRecord> spans) {
        return new TraceRecord(id, conversationId, "msg-" + id, provider, model, start, 500L, TraceRecord.STATUS_OK,
                in, out, in + out, "stop", toolCalls > 0, toolCalls, true, "user", "session", Set.of(), Set.of(),
                spans, Map.of());
    }

    private static SpanRecord span(String id, String name, long start, long duration, String status,
            Map<String, String> attributes) {
        return new SpanRecord(id, null, name, start, duration, status, attributes);
    }

    @Test
    void toolOnlyTraceFromAnExternalClientIsNotCostedAsAnLlmCall() {
        long before = service.costBreakdown(Window.LAST_10M, NOW).totalCalls();
        long recent = NOW - Duration.ofMinutes(2).toMillis();
        this.buffer.add(new TraceRecord("local-ext", null, null, null, null, recent, 12L, TraceRecord.STATUS_OK,
                null, null, null, null, true, 1, false, null, null, Set.of("team_echo"), Set.of("everything"),
                List.of(span("x1", "spring.ai.tool", recent, 12, "OK", Map.of("spring.ai.tool.definition.name",
                        "team_echo", "network.transport", "pipe", "saip.mcp.server", "everything",
                        "saip.tool.origin", "wrapped_external", "saip.upstream.tool", "echo"))), Map.of()));

        assertThat(service.costBreakdown(Window.LAST_10M, NOW).totalCalls()).isEqualTo(before);
        assertThat(service.mcpBreakdown(Window.LAST_10M, NOW).proxied()).isEqualTo(1);
    }

    @Test
    void deniedCallsCountAsToolCallsButNotAsExecutedOrMcpServerCalls() {
        long recent = NOW - Duration.ofMinutes(2).toMillis();
        this.buffer.add(trace("t-denied", "c9", "free", "ollama", recent, 1L, 1L, 2, List.of(
                span("d1", "spring.ai.tool", recent, 0, "OK", Map.of("spring.ai.tool.definition.name", "team_sum",
                        "network.transport", "pipe", "saip.mcp.server", "everything",
                        "saip.tool.origin", "wrapped_external", "saip.upstream.tool", "get-sum",
                        "saip.hitl.outcome", "declined")),
                span("d2", "spring.ai.tool", recent, 0, "OK", Map.of("spring.ai.tool.definition.name", "writeFile",
                        "saip.hitl.outcome", "timeout", "sandbox.level", "L4")))));

        ToolBreakdown tools = service.toolBreakdown(Window.LAST_10M, NOW);
        assertThat(tools.denied()).isEqualTo(2);
        assertThat(tools.total()).isEqualTo(5);
        assertThat(tools.executed()).isEqualTo(3);
        assertThat(tools.proxied()).isZero();
        assertThat(tools.toolNameCounts()).containsEntry("team_sum", 1L).containsEntry("writeFile", 1L);
        assertThat(tools.sandboxLevelCounts()).doesNotContainKey("L4");
        assertThat(tools.latencyPerBucket().stream().mapToInt(List::size).sum()).isEqualTo(3);

        McpBreakdown mcp = service.mcpBreakdown(Window.LAST_10M, NOW);
        assertThat(mcp.total()).isEqualTo(1);
        assertThat(mcp.denied()).isEqualTo(1);
        assertThat(mcp.toolCounts()).doesNotContainKey("get-sum");
        assertThat(mcp.callerCounts()).containsExactly(Map.entry(ObservabilityQueryService.CHAT_CALLER, 1L));

        ToolMcpBreakdown all = service.toolMcpBreakdown(Window.LAST_10M, NOW);
        assertThat(all.toolCounts()).containsEntry("team_sum", 1L).containsEntry("writeFile", 1L);
        assertThat(all.transportCounts()).doesNotContainKey("pipe");
    }

    @Test
    void externalClientCallsAreAttributedToTheClientAndTheRestToAgenticChat() {
        long recent = NOW - Duration.ofMinutes(2).toMillis();
        this.buffer.add(new TraceRecord("local-js", null, null, null, null, recent, 9L, TraceRecord.STATUS_OK,
                null, null, null, null, true, 1, false, null, null, Set.of("getCurrentTime"), Set.of(LOOPBACK),
                List.of(span("j1", "spring.ai.tool", recent, 9, "OK", Map.of("spring.ai.tool.definition.name",
                        "getCurrentTime", "network.transport", "tcp", "saip.mcp.server", LOOPBACK,
                        "saip.via", "mcp-server", "saip.mcp.client", "curl-qa"))), Map.of()));
        this.buffer.add(new TraceRecord("local-px", null, null, null, null, recent, 12L, TraceRecord.STATUS_OK,
                null, null, null, null, true, 1, false, null, null, Set.of("team_echo"), Set.of("everything"),
                List.of(span("x1", "spring.ai.tool", recent, 12, "OK", Map.of("spring.ai.tool.definition.name",
                        "team_echo", "network.transport", "pipe", "saip.mcp.server", "everything",
                        "saip.tool.origin", "wrapped_external", "saip.upstream.tool", "echo",
                        "saip.via", "mcp-server"))), Map.of()));

        ToolBreakdown tools = service.toolBreakdown(Window.LAST_10M, NOW);
        assertThat(tools.toolNameCounts()).containsEntry("getCurrentTime", 1L).containsEntry("team_echo", 1L);
        assertThat(tools.callerCounts()).containsOnly(Map.entry(ObservabilityQueryService.CHAT_CALLER, 3L),
                Map.entry("curl-qa", 1L), Map.entry(ObservabilityQueryService.UNKNOWN_CLIENT, 1L));
        assertThat(tools.externalClient()).isEqualTo(2);

        McpBreakdown mcp = service.mcpBreakdown(Window.LAST_10M, NOW);
        assertThat(mcp.callerCounts()).containsOnly(Map.entry(ObservabilityQueryService.CHAT_CALLER, 1L),
                Map.entry(ObservabilityQueryService.UNKNOWN_CLIENT, 1L));
        assertThat(mcp.externalClient()).isEqualTo(1);
    }

    @Test
    void proxiedCallShowsOnBothBreakdownsAndIsCountedOnceOverall() {
        long recent = NOW - Duration.ofMinutes(2).toMillis();
        this.buffer.add(trace("t-proxy", "c9", "free", "ollama", recent, 1L, 1L, 1, List.of(
                span("p1", "spring.ai.tool", recent, 25, "OK", Map.of("spring.ai.tool.definition.name", "team_sum",
                        "network.transport", "pipe", "saip.mcp.server", "everything",
                        "saip.tool.origin", "wrapped_external", "saip.upstream.tool", "get-sum",
                        "saip.tool.exposed_alias", "team_sum")))));

        McpBreakdown mcp = service.mcpBreakdown(Window.LAST_10M, NOW);
        assertThat(mcp.toolCounts()).containsOnly(Map.entry("echo", 1L), Map.entry("get-sum", 1L));
        assertThat(mcp.serverCounts()).containsExactly(Map.entry("everything", 2L));
        assertThat(mcp.transportCounts()).containsOnly(Map.entry("http", 1L), Map.entry("pipe", 1L));
        assertThat(mcp.total()).isEqualTo(2);
        assertThat(mcp.proxied()).isEqualTo(1);

        ToolBreakdown tools = service.toolBreakdown(Window.LAST_10M, NOW);
        assertThat(tools.toolNameCounts()).containsEntry("team_sum", 1L).doesNotContainKey("get-sum")
                .doesNotContainKey("echo");
        assertThat(tools.total()).isEqualTo(4);
        assertThat(tools.proxied()).isEqualTo(1);

        ToolMcpBreakdown all = service.toolMcpBreakdown(Window.LAST_10M, NOW);
        assertThat(all.toolCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(5);
        assertThat(all.toolCounts()).containsEntry("team_sum", 1L);
        assertThat(all.serverCounts()).containsEntry("everything", 2L);
    }

    @Test
    void mcpAndToolBreakdownsSplitExternalFromLocalCalls() {
        McpBreakdown mcp = service.mcpBreakdown(Window.LAST_10M, NOW);
        assertThat(mcp.toolCounts()).containsExactly(Map.entry("echo", 1L));
        assertThat(mcp.serverCounts()).containsExactly(Map.entry("everything", 1L));
        assertThat(mcp.transportCounts()).containsExactly(Map.entry("http", 1L));
        assertThat(mcp.error()).isEqualTo(1);
        assertThat(mcp.ok()).isZero();
        assertThat(mcp.total()).isEqualTo(1);
        assertThat(mcp.proxied()).isZero();
        assertThat(mcp.denied()).isZero();
        assertThat(mcp.externalClient()).isZero();
        assertThat(mcp.callsByTransportPerBucket().get("http")).hasSize(Window.LAST_10M.buckets());
        assertThat(mcp.latencyPerBucket().stream().mapToInt(List::size).sum()).isEqualTo(1);

        ToolBreakdown tools = service.toolBreakdown(Window.LAST_10M, NOW);
        assertThat(tools.toolNameCounts()).containsExactly(Map.entry("getWeather", 1L), Map.entry("getTime", 1L),
                Map.entry("(unnamed)", 1L));
        assertThat(tools.sandboxLevelCounts()).containsExactly(Map.entry("L0", 1L), Map.entry("unknown", 2L));
        assertThat(tools.total()).isEqualTo(3);
        assertThat(tools.proxied()).isZero();
        assertThat(tools.distinctTraces()).isEqualTo(2);

        ToolMcpBreakdown all = service.toolMcpBreakdown(Window.LAST_10M, NOW);
        assertThat(all.toolCounts()).containsKeys("getWeather", "echo", "getTime", "(unnamed)");
        assertThat(all.transportCounts()).containsExactly(Map.entry("in-process", 2L), Map.entry("http", 2L));
        assertThat(all.serverCounts()).containsExactly(Map.entry("everything", 1L), Map.entry(LOOPBACK, 1L));
    }

    @Test
    void vectorQueryLatencyLeavesOutAddAndDeleteOperations() {
        VectorOperationLog vectorOperationLog = new VectorOperationLog();
        ObservabilityQueryService vectorOnly = new ObservabilityQueryService(this.buffer,
                mock(McpClientService.class), new ModelPricingService(this.home, this.executor), vectorOperationLog);
        long recent = NOW - Duration.ofMinutes(1).toMillis();
        vectorOperationLog.record(new VectorOperation(recent, 78, "query", "simple", 4L, 0.35, 4, null, false),
                "hotel cap in London");
        vectorOperationLog.record(new VectorOperation(recent, 258, "add", "simple", null, null, null, null, false),
                null);
        vectorOperationLog.record(new VectorOperation(recent, 40, "delete", "simple", null, null, null, null, false),
                null);

        VectorStats stats = vectorOnly.vectorStats(Window.LAST_10M, NOW);

        assertThat(stats.totalOps()).isEqualTo(3);
        assertThat(stats.totalLatencyMs()).isEqualTo(78);
        assertThat(stats.latencySamples()).isEqualTo(1);
        assertThat(stats.latencyPerBucket().stream().mapToInt(List::size).sum()).isEqualTo(1);
    }

    @Test
    void vectorAndCostAggregationsUseOnlyTracesInsideTheWindow() {
        VectorOps ops = service.vectorOps(Window.LAST_10M, NOW);
        assertThat(ops.opsPerBucketDouble()).hasSize(Window.LAST_10M.buckets());
        assertThat(Arrays.stream(ops.opsPerBucket()).sum()).isEqualTo(1);
        assertThat(ops.topKHistogram()).containsExactly(Map.entry("k=4", 1L));

        VectorStats stats = service.vectorStats(Window.LAST_10M, NOW);
        assertThat(stats.totalQueries()).isEqualTo(1);
        assertThat(stats.totalOps()).isEqualTo(1);
        assertThat(stats.topKDistribution()).containsExactly(Map.entry(4L, 1L));
        assertThat(stats.similarityBands()).containsExactly(Map.entry("[0.3, 0.5)", 1L));
        assertThat(stats.avgReturnedDocs()).containsExactly(Map.entry("simple", 2L));
        assertThat(stats.dbSystems()).containsExactly(Map.entry("simple", 1L));
        assertThat(stats.totalLatencyMs()).isEqualTo(15);
        assertThat(stats.latencySamples()).isEqualTo(1);
        assertThat(service.recentVectorQueries()).extracting(RecentQuery::text).containsExactly("wifi password");

        assertThat(service.windowCost(Window.LAST_10M, NOW)).isEqualByComparingTo("0.004");
        assertThat(service.windowCost(Window.LAST_1M, NOW + Duration.ofHours(1).toMillis())).isZero();

        CostBreakdown cost = service.costBreakdown(Window.LAST_10M, NOW);
        assertThat(cost.totalCalls()).isEqualTo(2);
        assertThat(cost.paidCalls()).isEqualTo(1);
        assertThat(cost.totalCost()).isEqualByComparingTo("0.004");
        assertThat(cost.totalInputCost()).isEqualByComparingTo("0.002");
        assertThat(cost.totalOutputCost()).isEqualByComparingTo("0.002");
        assertThat(cost.maxTraceCost()).isEqualByComparingTo("0.004");
        assertThat(cost.averageCost()).isEqualByComparingTo("0.002");
        assertThat(cost.costPerMinute(10)).isEqualByComparingTo("0.0004");
        assertThat(cost.models()).extracting(model -> model.model()).containsExactly("paid", "free");
        assertThat(cost.models().getFirst().avgCostPerCall()).isEqualByComparingTo("0.004");
        assertThat(cost.providerCost()).containsKeys("openai", "ollama");
        assertThat(cost.topExpensive()).extracting(trace -> trace.traceId()).containsExactly("t1", "t2");
    }

    @Test
    void conversationStatsBinDepthsDurationsAndStatuses() {
        ConversationStats stats = service.conversationStats(Window.LAST_10M, NOW);
        assertThat(stats.conversations()).hasSize(1);
        assertThat(stats.avgMessages()).isEqualTo(2.0);
        assertThat(stats.multiTurn()).isEqualTo(1);
        assertThat(stats.toolUsing()).isEqualTo(1);
        assertThat(stats.p95LoopDepth()).isEqualTo(4);
        assertThat(stats.depthBins()).containsEntry("1", 1L).containsEntry("3-5", 1L).containsEntry("0", 0L);
        assertThat(stats.durationBins().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
        assertThat(Arrays.stream(stats.newConversationsPerBucket()).sum()).isEqualTo(1.0);
        assertThat(stats.totalMessages()).isEqualTo(2);
        assertThat(stats.totalToolCalls()).isEqualTo(5);
        assertThat(stats.topCostMicros()).containsExactly(Map.entry("c1", 4000L));
        assertThat(stats.statusCounts()).containsExactly(Map.entry(TraceRecord.STATUS_OK, 1L));
    }

    @Test
    void staticHelpersSummariseOAuthLifecycleAndBands() {
        List<OAuthSnapshot> rows = List.of(
                new OAuthSnapshot("a", "STREAMABLE_HTTP", "OK", null, NOW + 60_000, false),
                new OAuthSnapshot("b", "STREAMABLE_HTTP", "OK", null, null, false),
                new OAuthSnapshot("c", "SSE", "AWAITING_AUTHORIZATION", "https://auth", null, false),
                new OAuthSnapshot("d", "SSE", "OFFLINE", null, null, true));
        OAuthSummary oauth = ObservabilityQueryService.oauthSummary(rows, NOW);
        assertThat(oauth.authorized()).isEqualTo(1);
        assertThat(oauth.awaiting()).isEqualTo(1);
        assertThat(oauth.errored()).isEqualTo(1);
        assertThat(oauth.expiringSoon()).isEqualTo(1);

        Map<String, long[]> lifecycle = ObservabilityQueryService.lifecycleByTransportClass(
                Map.of("STDIO:everything / started", 2L, "STREAMABLE_HTTP:remote / failed", 1L, "garbage", 9L),
                Map.of());
        assertThat(lifecycle.get("started")).containsExactly(2L, 0L);
        assertThat(lifecycle.get("failed")).containsExactly(0L, 1L);
        assertThat(ObservabilityQueryService.lifecycleByTransportClass(Map.of(), Map.of("started", 3L))
                .get("started")).containsExactly(3L, 0L);

        assertThat(ObservabilityQueryService.similarityBand(0.05)).isEqualTo("[0.0, 0.1)");
        assertThat(ObservabilityQueryService.similarityBand(0.95)).isEqualTo("[0.9, 1.0]");
        assertThat(ObservabilityQueryService.bucketIndex(NOW, NOW - 600_000, 60_000, 10)).isEqualTo(9);
        assertThat(ObservabilityQueryService.bucketIndex(NOW - 900_000, NOW - 600_000, 60_000, 10)).isZero();
    }
}
