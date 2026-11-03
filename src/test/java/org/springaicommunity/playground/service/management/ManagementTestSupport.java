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
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.Management;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ManagementTestSupport {

    record EchoInput(String text) {
    }

    record EchoData(String echoed) {
    }

    private ManagementTestSupport() {
    }

    static SpringAiPlaygroundOptions options(ApprovalMode approval) {
        return new SpringAiPlaygroundOptions(null, false, null, null, null, null, null,
                null, new Management(true, null, null, approval, null), null);
    }

    static ManagementAuditLog auditLog(SpringAiPlaygroundOptions options, McpRiskEventRingBuffer riskEvents) {
        return new ManagementAuditLog(options, riskEvents, new SimpleMeterRegistry());
    }

    static McpSyncServerExchange exchange(String clientName, boolean supportsElicitation) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(new Implementation(clientName, "1.0.0"));
        when(exchange.getClientCapabilities()).thenReturn(supportsElicitation
                ? ClientCapabilities.builder().elicitation().build()
                : ClientCapabilities.builder().build());
        return exchange;
    }

}
