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

import org.springaicommunity.playground.observability.McpToolObservationFilter;
import org.springaicommunity.playground.observability.ObservabilityRingBuffer;
import org.springaicommunity.playground.observability.SpanRecord;
import org.springaicommunity.playground.observability.TraceRecord;
import org.springaicommunity.playground.observability.VectorOperationLog;
import org.springaicommunity.playground.observability.VectorOperationLog.RecentQuery;
import org.springaicommunity.playground.observability.VectorOperationLog.VectorOperation;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.conversation.ConversationAggregator;
import org.springaicommunity.playground.observability.conversation.ConversationAggregator.ConversationSummary;
import org.springaicommunity.playground.observability.pricing.ModelPricingService;
import org.springaicommunity.playground.service.mcp.DeniedToolCallRecorder;
import org.springaicommunity.playground.service.mcp.ExternalClientToolCallObserver;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.OAuthSnapshot;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpClientService.StatusEntry;
import org.springaicommunity.playground.service.mcp.risk.McpRiskMdcKeys;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ObservabilityQueryService {

    public static final String TOOL_SPAN = "spring.ai.tool";
    public static final String UNNAMED_TOOL = "(unnamed)";
    public static final String UNKNOWN = "(unknown)";
    public static final String IN_PROCESS = "in-process";

    public static final String CHAT_CALLER = "Agentic Chat";
    public static final String UNKNOWN_CLIENT = "(unknown client)";

    private static final String TOOL_NAME = "spring.ai.tool.definition.name";

    public record McpBreakdown(Map<String, Long> toolCounts, Map<String, Long> serverCounts,
            Map<String, Long> transportCounts, Map<String, long[]> callsByTransportPerBucket, long ok, long error,
            long denied, long proxied, Map<String, Long> callerCounts, List<List<Long>> latencyPerBucket) {

        public long total() {
            return this.ok + this.error;
        }

        public long externalClient() {
            return externalClientCalls(this.callerCounts);
        }
    }

    public record ToolBreakdown(Map<String, Long> toolNameCounts, Map<String, Long> sandboxLevelCounts,
            Map<String, long[]> callsByToolPerBucket, long ok, long error, long denied, long proxied,
            Map<String, Long> callerCounts, long distinctTraces, List<List<Long>> latencyPerBucket) {

        public long total() {
            return this.ok + this.error + this.denied;
        }

        public long executed() {
            return this.ok + this.error;
        }

        public long externalClient() {
            return externalClientCalls(this.callerCounts);
        }
    }

    public record ToolMcpBreakdown(Map<String, Long> toolCounts, Map<String, Long> serverCounts,
            Map<String, Long> transportCounts) {}

    public record VectorOps(long[] opsPerBucket, Map<String, Long> topKHistogram) {

        public double[] opsPerBucketDouble() {
            double[] out = new double[this.opsPerBucket.length];
            for (int i = 0; i < out.length; i++) out[i] = this.opsPerBucket[i];
            return out;
        }
    }

    public record VectorStats(long[] queryPerBucket, long[] addPerBucket, long[] deletePerBucket,
            List<List<Long>> latencyPerBucket, Map<String, Long> dbSystems, Map<Long, Long> topKDistribution,
            Map<String, Long> similarityBands, Map<String, Long> avgReturnedDocs, long totalQueries, long totalTopK,
            long topKSamples, long totalLatencyMs, long latencySamples) {

        public long totalOps() {
            long total = 0;
            for (long value : this.queryPerBucket) total += value;
            for (long value : this.addPerBucket) total += value;
            for (long value : this.deletePerBucket) total += value;
            return total;
        }
    }

    public record ModelCost(String model, long calls, long inputTokens, long outputTokens, BigDecimal cost) {

        public BigDecimal avgCostPerCall() {
            if (this.calls == 0) return BigDecimal.ZERO;
            return this.cost.divide(BigDecimal.valueOf(this.calls), 6, RoundingMode.HALF_UP);
        }
    }

    public record ExpensiveTrace(String traceId, String model, String provider, long startEpochMs, long inputTokens,
            long outputTokens, BigDecimal cost) {}

    public record CostBreakdown(List<ModelCost> models, Map<String, BigDecimal> providerCost, BigDecimal totalCost,
            BigDecimal totalInputCost, BigDecimal totalOutputCost, BigDecimal maxTraceCost, long paidCalls,
            long totalCalls, double[] costPerBucket, List<ExpensiveTrace> topExpensive) {

        public BigDecimal averageCost() {
            return this.totalCalls == 0 ? BigDecimal.ZERO
                    : this.totalCost.divide(BigDecimal.valueOf(this.totalCalls), 6, RoundingMode.HALF_UP);
        }

        public BigDecimal costPerMinute(int minutes) {
            return minutes == 0 ? BigDecimal.ZERO
                    : this.totalCost.divide(BigDecimal.valueOf(minutes), 6, RoundingMode.HALF_UP);
        }
    }

    public record ConversationStats(List<ConversationSummary> conversations, double avgMessages, BigDecimal avgCost,
            double avgDurationMs, long multiTurn, long toolUsing, long ragUsing, int p95LoopDepth, double[] direct,
            double[] lightAgentic, double[] deepLoop, double[] newConversationsPerBucket, Map<String, Long> depthBins,
            Map<String, Long> durationBins, Map<String, Long> toolCounts, Map<String, Long> modelCounts,
            Map<String, Long> topCostMicros, Map<String, Long> statusCounts, long totalMessages, long totalToolCalls,
            long totalRagCalls) {}

    public record ServerStatusSummary(Map<String, Long> statusCounts, long up, int total) {}

    public record OAuthSummary(long authorized, long awaiting, long errored, long expiringSoon) {}

    private final ObservabilityRingBuffer buffer;
    private final McpClientService mcpClientService;
    private final ModelPricingService pricingService;
    private final VectorOperationLog vectorOperationLog;

    public ObservabilityQueryService(ObservabilityRingBuffer buffer, McpClientService mcpClientService,
            ModelPricingService pricingService, VectorOperationLog vectorOperationLog) {
        this.vectorOperationLog = vectorOperationLog;
        this.buffer = buffer;
        this.mcpClientService = mcpClientService;
        this.pricingService = pricingService;
    }

    public McpBreakdown mcpBreakdown(Window window) {
        return mcpBreakdown(window, System.currentTimeMillis());
    }

    public McpBreakdown mcpBreakdown(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        Map<String, Long> toolCounts = new LinkedHashMap<>();
        Map<String, Long> serverCounts = new LinkedHashMap<>();
        Map<String, Long> transportCounts = new LinkedHashMap<>();
        Map<String, long[]> callsByTransportPerBucket = new LinkedHashMap<>();
        List<List<Long>> latencyPerBucket = emptyBuckets(buckets);
        long ok = 0;
        long error = 0;
        long denied = 0;
        long proxied = 0;
        Map<String, Long> callerCounts = new LinkedHashMap<>();
        for (TraceRecord trace : this.buffer.snapshot()) {
            if (trace.startEpochMs() < windowStart || trace.spans() == null) continue;
            for (SpanRecord span : trace.spans()) {
                if (!TOOL_SPAN.equals(span.name())) continue;
                Map<String, String> attrs = span.attributes();
                if (attrs == null) continue;
                String transport = attrs.get("network.transport");
                if (transport == null || IN_PROCESS.equalsIgnoreCase(transport)) continue;
                String server = attrs.getOrDefault("saip.mcp.server", UNKNOWN);
                if (this.mcpClientService.isSelfLoopback(server)) continue;
                if (isDenied(attrs)) {
                    denied++;
                    continue;
                }
                if (isProxied(attrs)) proxied++;
                callerCounts.merge(callerOf(attrs), 1L, Long::sum);
                toolCounts.merge(attrs.getOrDefault(McpToolObservationFilter.MCP_UPSTREAM_TOOL,
                        attrs.getOrDefault(TOOL_NAME, UNNAMED_TOOL)), 1L, Long::sum);
                serverCounts.merge(server, 1L, Long::sum);
                transportCounts.merge(transport, 1L, Long::sum);
                int bucket = bucketIndex(span.startEpochMs(), windowStart, window.bucketMs(), buckets);
                latencyPerBucket.get(bucket).add(span.durationMs());
                callsByTransportPerBucket.computeIfAbsent(transport, key -> new long[buckets])[bucket]++;
                if ("ERROR".equals(span.status())) error++;
                else ok++;
            }
        }
        return new McpBreakdown(toolCounts, serverCounts, transportCounts, callsByTransportPerBucket, ok, error,
                denied, proxied, callerCounts, latencyPerBucket);
    }

    private static boolean isDenied(Map<String, String> attrs) {
        return attrs != null && attrs.containsKey(DeniedToolCallRecorder.HITL_OUTCOME);
    }

    private static String callerOf(Map<String, String> attrs) {
        if (attrs == null || !McpRiskMdcKeys.VIA_MCP_SERVER.equals(
                attrs.get(ExternalClientToolCallObserver.CALLER_VIA))) {
            return CHAT_CALLER;
        }
        return attrs.getOrDefault(ExternalClientToolCallObserver.CALLER_CLIENT, UNKNOWN_CLIENT);
    }

    private static long externalClientCalls(Map<String, Long> callerCounts) {
        return callerCounts.entrySet().stream().filter(entry -> !CHAT_CALLER.equals(entry.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static boolean isProxied(Map<String, String> attrs) {
        return attrs != null && McpRiskMdcKeys.ORIGIN_WRAPPED_EXTERNAL.equals(
                attrs.get(McpToolObservationFilter.MCP_ORIGIN));
    }

    public ToolBreakdown toolBreakdown(Window window) {
        return toolBreakdown(window, System.currentTimeMillis());
    }

    public ToolBreakdown toolBreakdown(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        Map<String, Long> toolNameCounts = new LinkedHashMap<>();
        Map<String, Long> sandboxLevelCounts = new LinkedHashMap<>();
        Map<String, long[]> callsByToolPerBucket = new LinkedHashMap<>();
        List<List<Long>> latencyPerBucket = emptyBuckets(buckets);
        long ok = 0;
        long error = 0;
        long denied = 0;
        long proxied = 0;
        Map<String, Long> callerCounts = new LinkedHashMap<>();
        long distinctTraces = 0;
        for (TraceRecord trace : this.buffer.snapshot()) {
            if (trace.startEpochMs() < windowStart || trace.spans() == null) continue;
            boolean traceCounted = false;
            for (SpanRecord span : trace.spans()) {
                if (!TOOL_SPAN.equals(span.name())) continue;
                Map<String, String> attrs = span.attributes();
                String transport = attrs == null ? null : attrs.get("network.transport");
                String server = attrs == null ? null : attrs.get("saip.mcp.server");
                boolean directInProcess = transport == null || IN_PROCESS.equalsIgnoreCase(transport);
                boolean proxiedCall = isProxied(attrs);
                if (!directInProcess && !proxiedCall && !this.mcpClientService.isSelfLoopback(server)) continue;
                if (proxiedCall && !isDenied(attrs)) proxied++;
                callerCounts.merge(callerOf(attrs), 1L, Long::sum);
                String name = attrs == null ? UNNAMED_TOOL : attrs.getOrDefault(TOOL_NAME, UNNAMED_TOOL);
                toolNameCounts.merge(name, 1L, Long::sum);
                int bucket = bucketIndex(span.startEpochMs(), windowStart, window.bucketMs(), buckets);
                callsByToolPerBucket.computeIfAbsent(name, key -> new long[buckets])[bucket]++;
                if (!traceCounted) {
                    distinctTraces++;
                    traceCounted = true;
                }
                if (isDenied(attrs)) {
                    denied++;
                    continue;
                }
                latencyPerBucket.get(bucket).add(span.durationMs());
                if ("ERROR".equals(span.status())) error++;
                else ok++;
                String level = attrs == null ? null : attrs.get("sandbox.level");
                sandboxLevelCounts.merge(level == null ? "unknown" : level, 1L, Long::sum);
            }
        }
        return new ToolBreakdown(toolNameCounts, sandboxLevelCounts, callsByToolPerBucket, ok, error, denied,
                proxied, callerCounts, distinctTraces, latencyPerBucket);
    }

    public BigDecimal windowCost(Window window) {
        return windowCost(window, System.currentTimeMillis());
    }

    public BigDecimal windowCost(Window window, long now) {
        long windowStart = now - window.totalMs();
        BigDecimal total = BigDecimal.ZERO;
        for (TraceRecord trace : this.buffer.snapshot()) {
            if (trace.startEpochMs() < windowStart) continue;
            total = total.add(this.pricingService.cost(trace.model() == null ? UNKNOWN : trace.model(),
                    orZero(trace.inputTokens()), orZero(trace.outputTokens())));
        }
        return total;
    }

    public ToolMcpBreakdown toolMcpBreakdown(Window window) {
        return toolMcpBreakdown(window, System.currentTimeMillis());
    }

    public ToolMcpBreakdown toolMcpBreakdown(Window window, long now) {
        long windowStart = now - window.totalMs();
        Map<String, Long> toolCounts = new LinkedHashMap<>();
        Map<String, Long> serverCounts = new LinkedHashMap<>();
        Map<String, Long> transportCounts = new LinkedHashMap<>();
        for (TraceRecord trace : this.buffer.snapshot()) {
            if (trace.startEpochMs() < windowStart || trace.spans() == null) continue;
            for (SpanRecord span : trace.spans()) {
                if (!TOOL_SPAN.equals(span.name())) continue;
                Map<String, String> attrs = span.attributes() == null ? Map.of() : span.attributes();
                toolCounts.merge(attrs.getOrDefault(TOOL_NAME, UNNAMED_TOOL), 1L, Long::sum);
                if (isDenied(attrs)) continue;
                String transport = attrs.getOrDefault("network.transport", IN_PROCESS);
                transportCounts.merge(transport, 1L, Long::sum);
                if (!IN_PROCESS.equalsIgnoreCase(transport))
                    serverCounts.merge(attrs.getOrDefault("saip.mcp.server", UNKNOWN), 1L, Long::sum);
            }
        }
        return new ToolMcpBreakdown(toolCounts, serverCounts, transportCounts);
    }

    public List<RecentQuery> recentVectorQueries() {
        return this.vectorOperationLog.recentQueries();
    }

    public VectorOps vectorOps(Window window) {
        return vectorOps(window, System.currentTimeMillis());
    }

    public VectorOps vectorOps(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        long[] opsPerBucket = new long[buckets];
        Map<String, Long> topKHistogram = new LinkedHashMap<>();
        for (VectorOperation operation : this.vectorOperationLog.snapshot()) {
            if (operation.startEpochMs() < windowStart) continue;
            opsPerBucket[bucketIndex(operation.startEpochMs(), windowStart, window.bucketMs(), buckets)]++;
            if (operation.topK() != null) topKHistogram.merge("k=" + operation.topK(), 1L, Long::sum);
        }
        return new VectorOps(opsPerBucket, topKHistogram);
    }

    public VectorStats vectorStats(Window window) {
        return vectorStats(window, System.currentTimeMillis());
    }

    public VectorStats vectorStats(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        long[] queryPerBucket = new long[buckets];
        long[] addPerBucket = new long[buckets];
        long[] deletePerBucket = new long[buckets];
        List<List<Long>> latencyPerBucket = emptyBuckets(buckets);
        Map<String, Long> dbSystems = new LinkedHashMap<>();
        Map<Long, Long> topKDistribution = new LinkedHashMap<>();
        Map<String, Long> similarityBands = new LinkedHashMap<>();
        Map<String, Long> returnedDocsSum = new LinkedHashMap<>();
        Map<String, Long> returnedDocsCount = new LinkedHashMap<>();
        long totalQueries = 0;
        long totalTopK = 0;
        long topKSamples = 0;
        long totalLatencyMs = 0;
        long latencySamples = 0;
        for (VectorOperation operation : this.vectorOperationLog.snapshot()) {
            if (operation.startEpochMs() < windowStart) continue;
            int bucket = bucketIndex(operation.startEpochMs(), windowStart, window.bucketMs(), buckets);
            switch (operation.operation()) {
                case "query" -> {
                    queryPerBucket[bucket]++;
                    totalQueries++;
                    latencyPerBucket.get(bucket).add(operation.durationMs());
                    totalLatencyMs += operation.durationMs();
                    latencySamples++;
                }
                case "add" -> addPerBucket[bucket]++;
                case "delete" -> deletePerBucket[bucket]++;
                default -> { }
            }
            String system = operation.dbSystem() == null ? UNKNOWN : operation.dbSystem();
            dbSystems.merge(system, 1L, Long::sum);
            if (operation.topK() != null) {
                totalTopK += operation.topK();
                topKSamples++;
                topKDistribution.merge(operation.topK(), 1L, Long::sum);
            }
            if (operation.similarityThreshold() != null) {
                similarityBands.merge(similarityBand(operation.similarityThreshold()), 1L, Long::sum);
            }
            if (operation.returnedDocuments() != null) {
                returnedDocsSum.merge(system, (long) operation.returnedDocuments(), Long::sum);
                returnedDocsCount.merge(system, 1L, Long::sum);
            }
        }
        Map<String, Long> avgReturnedDocs = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : returnedDocsCount.entrySet()) {
            long count = entry.getValue();
            long sum = returnedDocsSum.getOrDefault(entry.getKey(), 0L);
            avgReturnedDocs.put(entry.getKey(), count == 0 ? 0 : Math.round((double) sum / count));
        }
        return new VectorStats(queryPerBucket, addPerBucket, deletePerBucket, latencyPerBucket, dbSystems,
                topKDistribution, similarityBands, avgReturnedDocs, totalQueries, totalTopK, topKSamples,
                totalLatencyMs, latencySamples);
    }

    public CostBreakdown costBreakdown(Window window) {
        return costBreakdown(window, System.currentTimeMillis());
    }

    public CostBreakdown costBreakdown(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        Map<String, long[]> tokenTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> modelCost = new LinkedHashMap<>();
        Map<String, BigDecimal> providerCost = new LinkedHashMap<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalInputCost = BigDecimal.ZERO;
        BigDecimal totalOutputCost = BigDecimal.ZERO;
        BigDecimal maxTraceCost = BigDecimal.ZERO;
        long paidCalls = 0;
        long totalCalls = 0;
        double[] costPerBucket = new double[buckets];
        List<ExpensiveTrace> traces = new ArrayList<>();
        for (TraceRecord trace : this.buffer.snapshot()) {
            if (trace.startEpochMs() < windowStart || trace.toolOnly()) continue;
            String model = trace.model() == null ? UNKNOWN : trace.model();
            String provider = trace.provider() == null ? UNKNOWN : trace.provider();
            long in = orZero(trace.inputTokens());
            long out = orZero(trace.outputTokens());
            BigDecimal traceCost = this.pricingService.cost(model, in, out);
            long[] totals = tokenTotals.computeIfAbsent(model, key -> new long[3]);
            totals[0]++;
            totals[1] += in;
            totals[2] += out;
            modelCost.merge(model, traceCost, BigDecimal::add);
            providerCost.merge(provider, traceCost, BigDecimal::add);
            totalCost = totalCost.add(traceCost);
            totalInputCost = totalInputCost.add(inputCost(model, in));
            totalOutputCost = totalOutputCost.add(outputCost(model, out));
            totalCalls++;
            if (traceCost.signum() > 0) paidCalls++;
            if (traceCost.compareTo(maxTraceCost) > 0) maxTraceCost = traceCost;
            costPerBucket[bucketIndex(trace.startEpochMs(), windowStart, window.bucketMs(), buckets)]
                    += traceCost.doubleValue();
            traces.add(new ExpensiveTrace(trace.traceId(), trace.model(), trace.provider(), trace.startEpochMs(),
                    in, out, traceCost));
        }
        List<ModelCost> models = new ArrayList<>();
        tokenTotals.forEach((model, totals) -> models.add(new ModelCost(model, totals[0], totals[1], totals[2],
                modelCost.getOrDefault(model, BigDecimal.ZERO))));
        models.sort((left, right) -> right.cost().compareTo(left.cost()));
        traces.sort(Comparator.comparing(ExpensiveTrace::cost).reversed());
        List<ExpensiveTrace> topExpensive = traces.size() > 10 ? List.copyOf(traces.subList(0, 10)) : traces;
        return new CostBreakdown(models, providerCost, totalCost, totalInputCost, totalOutputCost, maxTraceCost,
                paidCalls, totalCalls, costPerBucket, topExpensive);
    }

    public ConversationStats conversationStats(Window window) {
        return conversationStats(window, System.currentTimeMillis());
    }

    public ConversationStats conversationStats(Window window, long now) {
        long windowStart = now - window.totalMs();
        int buckets = window.buckets();
        long bucketMs = window.bucketMs();
        List<TraceRecord> traces = this.buffer.snapshot();
        List<ConversationSummary> conversations =
                ConversationAggregator.aggregate(traces, this.pricingService, windowStart);
        int count = conversations.size();
        double avgMessages = conversations.stream().mapToLong(ConversationSummary::messageCount).average().orElse(0);
        BigDecimal totalCost = BigDecimal.ZERO;
        for (ConversationSummary conversation : conversations) totalCost = totalCost.add(conversation.cost());
        BigDecimal avgCost = count == 0 ? BigDecimal.ZERO
                : totalCost.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        double avgDurationMs = conversations.stream().mapToLong(ConversationSummary::durationMs).average().orElse(0);
        long multiTurn = conversations.stream().filter(ConversationSummary::hasMultipleTurns).count();
        long toolUsing = conversations.stream().filter(ConversationSummary::usedTools).count();
        long ragUsing = conversations.stream().filter(ConversationSummary::usedRag).count();
        int[] depths = conversations.stream().mapToInt(ConversationSummary::maxLoopDepth).sorted().toArray();
        int p95LoopDepth = depths.length == 0 ? 0
                : depths[Math.min(depths.length - 1, (int) Math.ceil(0.95 * (depths.length - 1)))];

        double[] direct = new double[buckets];
        double[] lightAgentic = new double[buckets];
        double[] deepLoop = new double[buckets];
        double[] newConversationsPerBucket = new double[buckets];
        Map<String, Long> firstSeen = new LinkedHashMap<>();
        Map<String, Long> depthBins = new LinkedHashMap<>();
        for (String bin : List.of("0", "1", "2", "3-5", "6+")) depthBins.put(bin, 0L);
        for (TraceRecord trace : traces) {
            if (trace.startEpochMs() < windowStart) continue;
            int bucket = bucketIndex(trace.startEpochMs(), windowStart, bucketMs, buckets);
            int depth = trace.toolCallCount();
            if (depth == 0) direct[bucket]++;
            else if (depth <= 2) lightAgentic[bucket]++;
            else deepLoop[bucket]++;
            depthBins.merge(depth == 0 ? "0" : depth == 1 ? "1" : depth == 2 ? "2" : depth <= 5 ? "3-5" : "6+",
                    1L, Long::sum);
            if (trace.conversationId() != null && !trace.conversationId().isBlank()) {
                Long previous = firstSeen.get(trace.conversationId());
                if (previous == null || trace.startEpochMs() < previous)
                    firstSeen.put(trace.conversationId(), trace.startEpochMs());
            }
        }
        for (Long timestamp : firstSeen.values())
            newConversationsPerBucket[bucketIndex(timestamp, windowStart, bucketMs, buckets)]++;

        Map<String, Long> durationBins = new LinkedHashMap<>();
        for (String bin : List.of("< 5s", "5-30s", "30s-2m", "2-10m", "10m+")) durationBins.put(bin, 0L);
        Map<String, Long> toolCounts = new LinkedHashMap<>();
        Map<String, Long> modelCounts = new LinkedHashMap<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (ConversationSummary conversation : conversations) {
            long seconds = conversation.durationMs() / 1000;
            durationBins.merge(seconds < 5 ? "< 5s" : seconds < 30 ? "5-30s" : seconds < 120 ? "30s-2m"
                    : seconds < 600 ? "2-10m" : "10m+", 1L, Long::sum);
            for (String tool : conversation.toolsUsed()) toolCounts.merge(tool, 1L, Long::sum);
            for (String model : conversation.modelsUsed()) modelCounts.merge(model, 1L, Long::sum);
            statusCounts.merge(conversation.worstStatus(), 1L, Long::sum);
        }
        List<ConversationSummary> topByCost = new ArrayList<>(conversations);
        topByCost.sort(Comparator.comparing(ConversationSummary::cost).reversed());
        Map<String, Long> topCostMicros = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(10, topByCost.size()); i++) {
            ConversationSummary conversation = topByCost.get(i);
            topCostMicros.put(conversation.conversationId() == null ? UNKNOWN : conversation.conversationId(),
                    conversation.cost().setScale(6, RoundingMode.HALF_UP).movePointRight(6).longValueExact());
        }
        long totalMessages = conversations.stream().mapToLong(ConversationSummary::messageCount).sum();
        long totalToolCalls = conversations.stream().mapToInt(ConversationSummary::toolCalls).sum();
        long totalRagCalls = conversations.stream().mapToInt(ConversationSummary::ragCalls).sum();
        return new ConversationStats(conversations, avgMessages, avgCost, avgDurationMs, multiTurn, toolUsing,
                ragUsing, p95LoopDepth, direct, lightAgentic, deepLoop, newConversationsPerBucket, depthBins,
                durationBins, toolCounts, modelCounts, topCostMicros, statusCounts, totalMessages, totalToolCalls,
                totalRagCalls);
    }

    public ServerStatusSummary serverStatusSummary() {
        Map<String, StatusEntry> statuses = this.mcpClientService.snapshotStatuses();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        long up = 0;
        for (StatusEntry entry : statuses.values()) {
            statusCounts.merge(entry.status().name(), 1L, Long::sum);
            if (entry.status() == ServerStatus.OK) up++;
        }
        return new ServerStatusSummary(statusCounts, up, statuses.size());
    }

    public List<OAuthSnapshot> oauthSnapshots() {
        return this.mcpClientService.snapshotOAuthState();
    }

    public OAuthSummary oauthSummary(List<OAuthSnapshot> rows) {
        return oauthSummary(rows, System.currentTimeMillis());
    }

    public static OAuthSummary oauthSummary(List<OAuthSnapshot> rows, long now) {
        long authorized = rows.stream().filter(row -> "OK".equals(row.status()))
                .filter(row -> row.accessTokenExpiresAtMs() != null || row.hasRefreshToken()).count();
        long awaiting = rows.stream().filter(row -> "AWAITING_AUTHORIZATION".equals(row.status())).count();
        long errored = rows.stream()
                .filter(row -> "ERROR".equals(row.status()) || "OFFLINE".equals(row.status())).count();
        long expiringSoon = rows.stream().filter(row -> row.accessTokenExpiresAtMs() != null)
                .filter(row -> row.accessTokenExpiresAtMs() - now < Duration.ofMinutes(5).toMillis()
                        && row.accessTokenExpiresAtMs() > now)
                .count();
        return new OAuthSummary(authorized, awaiting, errored, expiringSoon);
    }

    public static Map<String, long[]> lifecycleByTransportClass(Map<String, Long> byServer,
            Map<String, Long> byOutcome) {
        Map<String, long[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : byServer.entrySet()) {
            String key = entry.getKey();
            int colonIdx = key.indexOf(':');
            int slashIdx = key.indexOf(" / ");
            if (colonIdx <= 0 || slashIdx <= colonIdx) continue;
            String transport = key.substring(0, colonIdx);
            String outcome = key.substring(slashIdx + 3);
            int classIdx = transport.toUpperCase(Locale.ROOT).startsWith("STDIO") ? 0 : 1;
            result.computeIfAbsent(outcome, ignored -> new long[2])[classIdx] += entry.getValue();
        }
        if (result.isEmpty()) {
            for (Map.Entry<String, Long> entry : byOutcome.entrySet())
                result.put(entry.getKey(), new long[] { entry.getValue(), 0L });
        }
        return result;
    }

    public static String similarityBand(double threshold) {
        if (threshold < 0.1) return "[0.0, 0.1)";
        if (threshold < 0.3) return "[0.1, 0.3)";
        if (threshold < 0.5) return "[0.3, 0.5)";
        if (threshold < 0.7) return "[0.5, 0.7)";
        if (threshold < 0.9) return "[0.7, 0.9)";
        return "[0.9, 1.0]";
    }

    static int bucketIndex(long timestamp, long windowStart, long bucketMs, int buckets) {
        return (int) Math.min(buckets - 1, Math.max(0, (timestamp - windowStart) / bucketMs));
    }

    private BigDecimal inputCost(String model, long inTokens) {
        return this.pricingService.find(model)
                .map(pricing -> nonNull(pricing.inputPerMillionTokens()).multiply(BigDecimal.valueOf(inTokens))
                        .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal outputCost(String model, long outTokens) {
        return this.pricingService.find(model)
                .map(pricing -> nonNull(pricing.outputPerMillionTokens()).multiply(BigDecimal.valueOf(outTokens))
                        .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    private static List<List<Long>> emptyBuckets(int buckets) {
        List<List<Long>> result = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) result.add(new ArrayList<>());
        return result;
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
