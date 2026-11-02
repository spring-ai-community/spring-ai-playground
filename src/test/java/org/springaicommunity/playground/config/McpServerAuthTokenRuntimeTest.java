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
package org.springaicommunity.playground.config;

import org.junit.jupiter.api.AfterEach;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.ApprovalConfirmation;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpNotificationStore;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "vaadin.productionMode=true",
                "spring.ai.playground.user-home=${java.io.tmpdir}/saip-mcp-token-runtime-test" })
class McpServerAuthTokenRuntimeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private McpServerAuthTokenService authTokenService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private McpClientService mcpClientService;

    @Autowired
    private ToolSpecService toolSpecService;

    record EchoRequest(String text) {}

    @AfterEach
    void reopen() {
        if (this.authTokenService.accessMode() != AccessMode.NONE) this.authTokenService.open();
    }

    private int mcpStatus(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"));
        if (authorization != null) builder.header(HttpHeaders.AUTHORIZATION, authorization);
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private boolean loopbackConnects() {
        return this.mcpClientService.testConnection(this.mcpServerInfoService.getDefaultMcpServerInfo()).ok();
    }

    private boolean inspectorRunsBuiltInTool(McpServerInfo serverInfo) {
        return this.mcpClientService.callTool(serverInfo, "getCurrentTime", Map.of(),
                        Map.of(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY, true))
                .map(result -> !Boolean.TRUE.equals(result.isError()))
                .orElse(false);
    }

    @Test
    void inspectorKeepsRunningBuiltInToolsAfterTheTokenTurnsOnAndOff() {
        McpServerInfo beforeToken = this.mcpServerInfoService.getDefaultMcpServerInfo();
        assertThat(inspectorRunsBuiltInTool(beforeToken)).isTrue();

        this.authTokenService.requireToken("inspector-runtime-token");
        assertThat(inspectorRunsBuiltInTool(beforeToken)).isTrue();
        assertThat(inspectorRunsBuiltInTool(this.mcpServerInfoService.getDefaultMcpServerInfo())).isTrue();

        this.authTokenService.open();
        assertThat(inspectorRunsBuiltInTool(this.mcpServerInfoService.getDefaultMcpServerInfo())).isTrue();
    }

    @Test
    void inspectorApprovalCallUsesTheTokenEvenWhenItHoldsStaleServerInfo() throws Exception {
        McpServerInfo beforeToken = this.mcpServerInfoService.getDefaultMcpServerInfo();
        ToolCallback echo = FunctionToolCallback
                .builder("tokenApprovalEcho", (EchoRequest request) -> "approved: " + request.text())
                .description("Echoes after approval.")
                .inputType(EchoRequest.class)
                .build();
        this.toolSpecService.addExternalMcpTool(echo, true);
        this.authTokenService.requireToken("inspector-approval-token");
        try {
            CompletableFuture<Optional<McpSchema.CallToolResult>> pendingCall = CompletableFuture.supplyAsync(
                    () -> this.mcpClientService.callTool(beforeToken, "tokenApprovalEcho", Map.of("text", "hi"),
                            Map.of(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY, true)));
            List<McpNotificationStore.PendingElicitation> pending = pollUntil(
                    () -> this.mcpClientService.snapshotPendingElicitations(beforeToken), list -> !list.isEmpty());
            assertThat(pending).hasSize(1);
            this.mcpClientService.completeElicitation(beforeToken, pending.getFirst().id(),
                    ApprovalConfirmation.accepted());

            McpSchema.CallToolResult result = pendingCall.get(20, TimeUnit.SECONDS).orElseThrow();
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("approved: hi");
        } finally {
            this.toolSpecService.removeExternalMcpTool("tokenApprovalEcho");
        }
    }

    private static <T> T pollUntil(Supplier<T> supplier, Predicate<T> ready) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        T value = supplier.get();
        while (!ready.test(value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            value = supplier.get();
        }
        return value;
    }

    @Test
    void tokenTurnsOnRotatesAndTurnsOffWithoutARestart() throws Exception {
        assertThat(mcpStatus(null)).isNotEqualTo(401);
        assertThat(this.mcpServerInfoService.builtInServerTokenRequired()).isFalse();

        this.authTokenService.requireToken("first-runtime-token");
        assertThat(mcpStatus(null)).isEqualTo(401);
        assertThat(mcpStatus("Bearer first-runtime-token")).isNotEqualTo(401);
        assertThat(this.mcpServerInfoService.builtInServerTokenRequired()).isTrue();
        assertThat(loopbackConnects()).isTrue();

        this.authTokenService.requireToken("second-runtime-token");
        assertThat(mcpStatus("Bearer first-runtime-token")).isEqualTo(401);
        assertThat(mcpStatus("Bearer second-runtime-token")).isNotEqualTo(401);
        assertThat(loopbackConnects()).isTrue();

        this.authTokenService.open();
        assertThat(mcpStatus(null)).isNotEqualTo(401);
        assertThat(loopbackConnects()).isTrue();
    }
}
