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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.service.management.ManagementTestSupport.EchoData;
import org.springaicommunity.playground.service.management.ManagementTestSupport.EchoInput;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementSchemasTest {

    @Test
    void inputSchemaListsTheRecordComponents() {
        Map<String, Object> schema = ManagementSchemas.inputSchema(EchoInput.class);

        assertThat(schema).containsEntry("type", "object");
        assertThat(properties(schema).containsKey("text")).isTrue();
        assertThat(schema).doesNotContainKey("$schema");
    }

    @Test
    void outputSchemaWrapsTheDataTypeInTheEnvelope() {
        Map<String, Object> schema = ManagementSchemas.outputSchema(EchoData.class);

        assertThat(properties(schema).keySet())
                .contains("ok", "message", "persisted", "restartRequired", "warnings", "data");
        assertThat(schema.get("required")).isEqualTo(List.of("ok", "message"));
    }

    @Test
    void outputSchemaWithoutDataHasNoDataProperty() {
        Map<String, Object> properties = properties(ManagementSchemas.outputSchema(null));

        assertThat(properties.containsKey("data")).isFalse();
    }

    @Test
    void wrappedToolsAreAcceptedByARealMcpServer() {
        ManagementTool tool = ManagementTool.named("saip_test_echo", "test", EchoInput.class).data(EchoData.class)
                .description("echo").handle((input, caller) -> ManagementResult.ok("ok", new EchoData(input.text())));
        ManagementApprovalGate gate = new ManagementApprovalGate(ManagementTestSupport.options(ApprovalMode.ALL),
                mock(ObjectProvider.class),
                ManagementTestSupport.auditLog(ManagementTestSupport.options(ApprovalMode.ALL),
                        new McpRiskEventRingBuffer()),
                new SimpleMeterRegistry());
        SyncToolSpecification specification = gate.wrap(tool);
        McpServerTransportProvider transportProvider = mock(McpServerTransportProvider.class);
        when(transportProvider.notifyClients(any(), any())).thenReturn(Mono.empty());
        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(ServerCapabilities.builder().tools(true).build()).build();

        assertThatCode(() -> server.addTool(specification)).doesNotThrowAnyException();
        assertThat(server.listTools()).extracting(McpSchema.Tool::name).contains("saip_test_echo");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

}
