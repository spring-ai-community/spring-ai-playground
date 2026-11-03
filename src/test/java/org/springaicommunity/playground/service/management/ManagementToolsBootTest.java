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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.mcp.ApprovalConfirmation;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService.McpServerRegistration;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.client.TestStdioMcpServer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vaadin.productionMode=true",
        "spring.ai.playground.mcp-server.auth-token=it-secret-token"})
class ManagementToolsBootTest {

    private static final String UPSTREAM = "it-upstream";
    private static final String EXPOSED_ALIAS = "it_upstream_echo";

    @TempDir
    static Path homeDir;

    @DynamicPropertySource
    static void isolatedHome(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.playground.user-home", () -> homeDir.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private McpServerInfoService serverInfoService;

    @Autowired
    private McpClientService clientService;

    @Autowired
    private ManagementToolRegistrar registrar;

    private final AtomicReference<ElicitResult.Action> decision = new AtomicReference<>(ElicitResult.Action.ACCEPT);
    private final List<String> approvalMessages = new ArrayList<>();
    private McpSyncClient client;

    @BeforeEach
    void connect() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String connection = new ObjectMapper().writeValueAsString(Map.of("command", javaBin,
                "args", List.of("-cp", System.getProperty("java.class.path"), TestStdioMcpServer.class.getName())));
        this.serverInfoService.saveAndConnect(new McpServerRegistration(null, null, McpTransportType.STDIO, UPSTREAM,
                "test upstream", null, null, connection));
        this.client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
                        .endpoint("/mcp")
                        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                                builder.header("Authorization", "Bearer it-secret-token"))
                        .build())
                .clientInfo(new Implementation("it-client", "9.9"))
                .capabilities(ClientCapabilities.builder().elicitation().build())
                .elicitation(request -> {
                    this.approvalMessages.add(request.message());
                    return this.decision.get() == ElicitResult.Action.ACCEPT ? ApprovalConfirmation.accepted()
                            : new ElicitResult(this.decision.get(), Map.of());
                })
                .build();
        this.client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (this.client != null) {
            this.client.close();
        }
        this.serverInfoService.findByServerName(UPSTREAM).ifPresent(info -> this.serverInfoService.delete(UPSTREAM));
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return this.client.callTool(new CallToolRequest(tool, arguments));
    }

    private static void assertOk(CallToolResult result) {
        assertThat(result.isError()).as(result.toString()).isFalse();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private List<String> toolNames() {
        return this.client.listTools().tools().stream().map(Tool::name).toList();
    }

    @Test
    void managementToolsAreOfferedOnlyToClientsWithTheTokenAndWithoutRegistrationTools() {
        assertThat(this.registrar.status().active()).isTrue();
        assertThat(toolNames()).contains("saip_mcp_list_servers", "saip_mcp_server_tools", "saip_expose_get",
                "saip_expose_add_tools", "saip_monitor_management_log")
                .doesNotContain("saip_mcp_add_custom", "saip_mcp_add_from_catalog", "saip_mcp_remove_server");
    }

    @Test
    void theStatusResourcesAndTheChatToolsAreOfferedWithTheOthers() {
        assertThat(toolNames()).contains("saip_chat_ask", "saip_chat_list_conversations");
        assertThat(this.client.listResources().resources()).extracting(Resource::uri)
                .contains("saip://status/servers", "saip://status/exposure", "saip://status/shipyard");
        TextResourceContents servers = (TextResourceContents) this.client
                .readResource(new ReadResourceRequest("saip://status/servers")).contents().getFirst();
        assertThat(servers.mimeType()).isEqualTo("application/json");
        assertThat(servers.text()).contains("\"name\":\"" + UPSTREAM + "\"");
    }

    @Test
    void theProxyGuideIsOfferedAsResourcesAndAPrompt() {
        assertThat(this.client.listResources().resources()).extracting(Resource::uri)
                .contains("saip://guide/proxy", "saip://guide/risk-levels");
        String guide = ((TextResourceContents) this.client.readResource(new ReadResourceRequest("saip://guide/proxy"))
                .contents().getFirst()).text();
        assertThat(guide).contains("saip_expose_add_tools");
        assertThat(this.client.listPrompts().prompts()).extracting(Prompt::name).contains("saip_proxy_server");
        GetPromptResult prompt = this.client.getPrompt(new GetPromptRequest("saip_proxy_server",
                Map.of("goal", "expose the wiki tools")));
        assertThat(((TextContent) prompt.messages().getFirst().content()).text())
                .contains("expose the wiki tools").contains("saip://guide/proxy");
    }

    @Test
    void anAgentCanCombineExposeAndRemoveUpstreamToolsWithHumanApprovalAndAudit() {
        CallToolResult servers = call("saip_mcp_list_servers", Map.of());
        assertOk(servers);
        assertThat(text(servers)).contains(UPSTREAM);

        CallToolResult upstreamTools = call("saip_mcp_server_tools", Map.of("server", UPSTREAM));
        assertOk(upstreamTools);
        assertThat(text(upstreamTools)).contains("\"echo\"");

        Map<String, Object> change = Map.of("selections", List.of(Map.of("server", UPSTREAM, "tools", "all")));
        CallToolResult preview = call("saip_expose_preview", Map.of("operation", "ADD", "selections",
                List.of(Map.of("server", UPSTREAM, "tools", "all"))));
        assertOk(preview);
        assertThat(this.approvalMessages).isEmpty();
        assertThat(toolNames()).doesNotContain(EXPOSED_ALIAS);

        this.decision.set(ElicitResult.Action.DECLINE);
        CallToolResult declined = call("saip_expose_add_tools", change);
        assertThat(declined.isError()).isTrue();
        assertThat(text(declined)).contains("declined");
        assertThat(toolNames()).doesNotContain(EXPOSED_ALIAS);

        this.decision.set(ElicitResult.Action.ACCEPT);
        CallToolResult added = call("saip_expose_add_tools", change);
        assertOk(added);
        assertThat(this.approvalMessages).last().asString()
                .contains("[DESTRUCTIVE]", "it-client 9.9", EXPOSED_ALIAS, "HITL");
        assertThat(toolNames()).contains(EXPOSED_ALIAS);

        CallToolResult exposed = call("saip_expose_get", Map.of());
        assertThat(text(exposed)).contains(EXPOSED_ALIAS).contains("\"hitl\":true");

        int approvalsBefore = this.approvalMessages.size();
        CallToolResult echoed = call(EXPOSED_ALIAS, Map.of("message", "hi"));
        assertOk(echoed);
        assertThat(text(echoed)).contains("Echo: hi");
        assertThat(this.approvalMessages).hasSize(approvalsBefore + 1);

        this.decision.set(ElicitResult.Action.DECLINE);
        CallToolResult blocked = call(EXPOSED_ALIAS, Map.of("message", "no"));
        assertThat(blocked.isError()).isTrue();

        this.decision.set(ElicitResult.Action.ACCEPT);
        CallToolResult withoutHitl = call("saip_expose_set_hitl", Map.of("selections", List.of(Map.of("server",
                UPSTREAM, "tools", List.of("echo"), "hitl", Map.of("default", "NONE")))));
        assertOk(withoutHitl);
        assertThat(this.approvalMessages).last().asString().contains("WITHOUT human approval");
        int approvalsAfterChange = this.approvalMessages.size();
        assertThat(text(call(EXPOSED_ALIAS, Map.of("message", "free")))).contains("Echo: free");
        assertThat(this.approvalMessages).hasSize(approvalsAfterChange);

        CallToolResult removed = call("saip_expose_remove_tools", Map.of("selections",
                List.of(Map.of("server", UPSTREAM, "tools", "all"))));
        assertOk(removed);
        assertThat(toolNames()).doesNotContain(EXPOSED_ALIAS);

        String log = text(call("saip_monitor_management_log", Map.of()));
        assertThat(log).contains("saip_expose_add_tools", "DECLINED", "OK", "it-client 9.9");
        assertThat(log).doesNotContain("it-secret-token");
    }

    @Test
    void badRequestsReturnHelpfulErrorsAndReadToolsNeverAsk() {
        int approvals = this.approvalMessages.size();

        CallToolResult unknownServer = call("saip_expose_add_tools", Map.of("selections",
                List.of(Map.of("server", "ghost", "tools", "all"))));
        CallToolResult unknownField = call("saip_mcp_server_tools", Map.of("server", UPSTREAM, "typo", true));
        CallToolResult missingServer = call("saip_mcp_server_tools", Map.of("server", "ghost"));

        assertThat(unknownServer.isError()).isTrue();
        assertThat(text(unknownServer)).contains("not registered or not connected");
        assertThat(unknownField.isError()).isTrue();
        assertThat(text(unknownField)).contains("typo");
        assertThat(missingServer.isError()).isTrue();
        assertThat(text(missingServer)).contains("saip_mcp_list_servers");
        assertThat(this.approvalMessages).hasSize(approvals);
    }

    @Test
    void chatNeverSeesManagementTools() {
        McpServerInfo builtIn = this.serverInfoService.getDefaultMcpServerInfo();

        List<String> chatVisible = this.clientService.buildToolCallbackProviders(builtIn).stream()
                .map(ToolCallbackProvider::getToolCallbacks).flatMap(Arrays::stream)
                .map(ToolCallback::getToolDefinition).map(definition -> definition.name()).toList();

        assertThat(chatVisible).noneMatch(name -> name.startsWith("saip_"));
    }

}
