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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer.RiskEvent;
import org.springaicommunity.playground.observability.Window;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService;
import org.springaicommunity.playground.observability.query.ObservabilityQueryService.McpBreakdown;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger;
import org.springaicommunity.playground.service.mcp.risk.McpToolHashLedger.Fingerprint;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorManagementContributionTest {

    private final ObservabilityQueryService queryService = mock(ObservabilityQueryService.class);
    private final McpRiskEventRingBuffer riskEvents = new McpRiskEventRingBuffer();
    private final McpToolHashLedger hashLedger = mock(McpToolHashLedger.class);
    private final SystemMetricsSnapshot metricsSnapshot = mock(SystemMetricsSnapshot.class);
    private final ManagementAuditLog auditLog = ManagementTestSupport.auditLog(
            ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), riskEvents);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MonitorManagementContribution contribution = new MonitorManagementContribution(queryService,
            riskEvents, hashLedger, metricsSnapshot, auditLog, meterRegistry);

    private ManagementTool tool(String name) {
        return contribution.tools().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void windowsAreParsedAndInvalidOnesExplainTheChoices() {
        when(queryService.mcpBreakdown(Window.LAST_5M)).thenReturn(new McpBreakdown(Map.of("t", 2L),
                Map.of("s", 2L), Map.of("http", 2L), Map.of(), 1, 1, 3, 1, Map.of("curl", 1L), List.of()));

        ManagementResult result = tool("saip_monitor_tool_calls").handle(Map.of("window", "5m"),
                ManagementCaller.ANONYMOUS);

        MonitorManagementContribution.ToolCallsData data = (MonitorManagementContribution.ToolCallsData) result.data();
        assertThat(data.window()).isEqualTo("5m");
        assertThat(data.total()).isEqualTo(2);
        assertThat(data.error()).isEqualTo(1);
        assertThat(data.proxied()).isEqualTo(1);
        assertThat(data.denied()).isEqualTo(3);
        assertThat(data.byCaller()).containsExactly(Map.entry("curl", 1L));
        assertThatThrownBy(() -> tool("saip_monitor_tool_calls").handle(Map.of("window", "2d"),
                ManagementCaller.ANONYMOUS)).hasMessageContaining("1m, 5m");
        verify(queryService).mcpBreakdown(Window.LAST_5M);
    }

    @Test
    void riskEventsAreFilteredAndLimitedNewestLast() {
        for (int index = 0; index < 5; index++) {
            riskEvents.record(new RiskEvent(1000L + index, index % 2 == 0 ? "POISONING_HIT" : "MANAGEMENT",
                    index % 2 == 0 ? "warn" : "info", "event " + index));
        }

        ManagementResult all = tool("saip_monitor_risk_events").handle(Map.of("limit", 2),
                ManagementCaller.ANONYMOUS);
        ManagementResult filtered = tool("saip_monitor_risk_events").handle(Map.of("type", "management",
                "sinceEpochMs", 1002L), ManagementCaller.ANONYMOUS);

        MonitorManagementContribution.EventsData allData = (MonitorManagementContribution.EventsData) all.data();
        assertThat(allData.events()).extracting(RiskEvent::summary).containsExactly("event 3", "event 4");
        assertThat(allData.truncated()).isTrue();
        MonitorManagementContribution.EventsData filteredData =
                (MonitorManagementContribution.EventsData) filtered.data();
        assertThat(filteredData.events()).extracting(RiskEvent::summary).containsExactly("event 3");
    }

    @Test
    void mcpStatusListsToolsWhoseDefinitionAwaitsReview() {
        when(queryService.serverStatusSummary()).thenReturn(new ObservabilityQueryService.ServerStatusSummary(
                Map.of("OK", 1L), 1, 1));
        when(queryService.oauthSnapshots()).thenReturn(List.of());
        when(hashLedger.snapshot()).thenReturn(List.of(
                new Fingerprint("wiki", "a", "h", null, 0L, null, null, Fingerprint.LifecycleStatus.ACTIVE),
                new Fingerprint("wiki", "b", "h", null, 0L, null, null,
                        Fingerprint.LifecycleStatus.AWAITING_REREVIEW)));

        ManagementResult result = tool("saip_monitor_mcp_status").handle(Map.of(), ManagementCaller.ANONYMOUS);

        MonitorManagementContribution.McpStatusData data = (MonitorManagementContribution.McpStatusData) result.data();
        assertThat(data.definitionReviewPending()).singleElement().satisfies(pending -> {
            assertThat(pending.tool()).isEqualTo("b");
            assertThat(pending.status()).isEqualTo("AWAITING_REREVIEW");
        });
        assertThat(result.message()).contains("1 of 1");
    }

    @Test
    void managementLogReturnsTheAuditTrailWithALimit() {
        for (int index = 0; index < 3; index++) {
            auditLog.record(new ManagementAuditLog.Entry(2000L + index, "cursor", "saip_x", ApprovalLevel.READ,
                    ManagementOutcome.OK, "call " + index, 1L));
        }

        ManagementResult result = tool("saip_monitor_management_log").handle(Map.of("limit", 2),
                ManagementCaller.ANONYMOUS);

        MonitorManagementContribution.LogData data = (MonitorManagementContribution.LogData) result.data();
        assertThat(data.entries()).extracting(ManagementAuditLog.Entry::summary).containsExactly("call 1", "call 2");
        assertThat(data.total()).isEqualTo(3);
        assertThat(data.truncated()).isTrue();
    }

    @Test
    void proxiedCallsAreSummedPerExposedToolFromTheMeters() {
        meterRegistry.timer("mcp.proxied.call", "alias", "wiki_read", "server", "DeepWiki", "tool",
                "read_wiki_contents", "outcome", "ok").record(200, TimeUnit.MILLISECONDS);
        meterRegistry.timer("mcp.proxied.call", "alias", "wiki_read", "server", "DeepWiki", "tool",
                "read_wiki_contents", "outcome", "ok").record(400, TimeUnit.MILLISECONDS);
        meterRegistry.timer("mcp.proxied.call", "alias", "wiki_read", "server", "DeepWiki", "tool",
                "read_wiki_contents", "outcome", "error").record(100, TimeUnit.MILLISECONDS);
        meterRegistry.timer("mcp.proxied.call", "alias", "other", "server", "S", "tool", "t", "outcome", "ok")
                .record(10, TimeUnit.MILLISECONDS);

        ManagementResult result = tool("saip_monitor_proxied_calls").handle(Map.of(), ManagementCaller.ANONYMOUS);

        MonitorManagementContribution.ProxiedCallsData data =
                (MonitorManagementContribution.ProxiedCallsData) result.data();
        assertThat(data.total()).isEqualTo(4);
        assertThat(data.failed()).isEqualTo(1);
        assertThat(data.calls()).extracting(MonitorManagementContribution.ProxiedCall::exposedAs)
                .containsExactly("wiki_read", "other");
        MonitorManagementContribution.ProxiedCall wiki = data.calls().getFirst();
        assertThat(wiki.server()).isEqualTo("DeepWiki");
        assertThat(wiki.tool()).isEqualTo("read_wiki_contents");
        assertThat(wiki.ok()).isEqualTo(2);
        assertThat(wiki.error()).isEqualTo(1);
        assertThat(wiki.totalMs()).isEqualTo(700);
        assertThat(wiki.averageMs()).isEqualTo(233);
    }

    @Test
    void noProxiedCallsGivesAnEmptyList() {
        ManagementResult result = tool("saip_monitor_proxied_calls").handle(Map.of(), ManagementCaller.ANONYMOUS);

        assertThat(((MonitorManagementContribution.ProxiedCallsData) result.data()).calls()).isEmpty();
        assertThat(result.message()).startsWith("0 proxied call(s)");
    }

}
