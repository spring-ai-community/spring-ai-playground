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

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.observability.BoundedRingBuffer;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer.RiskEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ManagementAuditLog extends BoundedRingBuffer<ManagementAuditLog.Entry> {

    public static final String RISK_EVENT_TYPE = "MANAGEMENT";

    private static final Logger logger = LoggerFactory.getLogger(ManagementAuditLog.class);
    private static final int MIN_CAPACITY = 10;

    public record Entry(long epochMs, String client, String tool, ApprovalLevel level, ManagementOutcome outcome,
                        String summary, long durationMs) {
    }

    private final McpRiskEventRingBuffer riskEvents;
    private final MeterRegistry meterRegistry;

    public ManagementAuditLog(SpringAiPlaygroundOptions options, McpRiskEventRingBuffer riskEvents,
            MeterRegistry meterRegistry) {
        super(options.management().auditCapacity(), MIN_CAPACITY);
        this.riskEvents = riskEvents;
        this.meterRegistry = meterRegistry;
    }

    public void record(Entry entry) {
        addWithEviction(entry);
        logger.info("mgmt.call client={} tool={} level={} outcome={} durationMs={} summary={}", entry.client(),
                entry.tool(), entry.level(), entry.outcome(), entry.durationMs(), entry.summary());
        this.meterRegistry.counter("mgmt.call", "tool", entry.tool(), "outcome", entry.outcome().name())
                .increment();
        if (entry.level() != ApprovalLevel.READ || entry.outcome() != ManagementOutcome.OK) {
            this.riskEvents.record(new RiskEvent(entry.epochMs(), RISK_EVENT_TYPE, severityOf(entry),
                    entry.client() + " " + entry.tool() + " " + entry.outcome()));
        }
    }

    public List<Entry> latest(long sinceEpochMs, int limit) {
        List<Entry> entries = snapshot().stream().filter(entry -> entry.epochMs() >= sinceEpochMs).toList();
        int from = Math.max(0, entries.size() - Math.max(1, limit));
        return List.copyOf(entries.subList(from, entries.size()));
    }

    private static String severityOf(Entry entry) {
        boolean risky = entry.level() == ApprovalLevel.DESTRUCTIVE || entry.outcome() != ManagementOutcome.OK;
        return risky ? "warn" : "info";
    }

}
