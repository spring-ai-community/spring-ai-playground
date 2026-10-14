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
package org.springaicommunity.playground.webui.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpLoopbackClientTestSupport;
import org.springaicommunity.playground.service.mcp.client.McpNotificationStore;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskComposer;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator;
import org.springaicommunity.playground.service.mcp.risk.WrappedExternalToolCallback;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.ResourcePrimitive;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.ToolPrimitive;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "vaadin.productionMode=true")
@Import(McpLoopbackClientTestSupport.LoopbackServerFixtures.class)
class McpServerInspectorViewTest extends SpringBrowserlessTest {

    private static final String EVAL_EXPRESSION_TOOL_ID = "6dea1efa-8a21-5522-ac08-4ede8fd9ff84";
    private static final String APPROVAL_TOOL = "inspectorApprovalEcho";

    record EchoRequest(String text) {}

    @LocalServerPort
    private int port;

    @Autowired
    private McpClientService mcpClientService;

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private McpToolRiskEvaluator riskEvaluator;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    private McpServerInfo serverInfo;
    private ToolSpecService.ToolMcpServerSetting previousSetting;

    @BeforeEach
    void exposeToolAndConnectLoopback() throws JacksonException {
        this.previousSetting = this.toolSpecService.getToolMcpServerSetting();
        this.toolSpecService.setToolMcpServerSetting(
                new ToolSpecService.ToolMcpServerSetting(false, Set.of(EVAL_EXPRESSION_TOOL_ID)));
        this.toolSpecService.reconcileNativeExposure();

        long now = System.currentTimeMillis();
        this.serverInfo = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "inspector-loopback", "", now, now,
                new ObjectMapper().writeValueAsString(new McpStreamableHttpClientProperties.ConnectionParameters(
                        "http://127.0.0.1:" + this.port, null)));
        this.mcpClientService.startMcpClient(this.serverInfo);
    }

    @AfterEach
    void tearDown() {
        if (this.serverInfo != null) {
            this.mcpClientService.stopMcpClient(this.serverInfo);
        }
        this.toolSpecService.setToolMcpServerSetting(this.previousSetting);
        this.toolSpecService.reconcileNativeExposure();
    }

    @Test
    void inspectorDrivesPrimitivesAgainstConnectedServer() throws Exception {
        List<McpSchema.Tool> tools = this.mcpClientService.getToolListAsOpt(this.serverInfo).orElseThrow();
        assertThat(tools).extracting(McpSchema.Tool::name).contains("evalExpression");

        McpServerInspectorView inspector = new McpServerInspectorView(this.serverInfo, this.mcpClientService,
                tools, this.riskEvaluator);
        UI.getCurrent().add(inspector);
        roundTrip();

        TabSheet tabSheet = $(TabSheet.class, inspector).first();
        for (int i = 0; i < tabSheet.getTabCount(); i++) {
            tabSheet.setSelectedIndex(i);
            roundTrip();
        }

        selectTab(tabSheet, "Tools");
        TextField expression = $(TextField.class, inspector)
                .withCondition(field -> "expression".equals(field.getLabel()))
                .first();
        test(expression).setValue("6000 * 7");
        Button runTool = $(Button.class, inspector)
                .withCondition(button -> "Run tool".equals(button.getTooltip().getText()))
                .first();
        test(runTool).click();
        String toolOutput = pollUntil(() -> {
            VaadinSession session = VaadinSession.getCurrent();
            session.getService().runPendingAccessTasks(session);
            return inspector.getElement().getTextRecursively();
        }, text -> text.contains("42000"));
        assertThat(toolOutput).contains("42000");
        assertThat($(Span.class, inspector).withCondition(span -> span.getText().startsWith("Running..."))
                .all()).isEmpty();

        selectTab(tabSheet, "Resources");
        ResourcePrimitive greeting = $(ResourcePrimitive.class, inspector)
                .withCondition(primitive -> primitive.getElement().getTextRecursively().contains("loopback-greeting"))
                .first();
        test($(Button.class, greeting)
                .withCondition(button -> "Read resource".equals(button.getTooltip().getText()))
                .first()).click();
        roundTrip();
        assertThat(inspector.getElement().getTextRecursively()).contains("hello from loopback");

        selectTab(tabSheet, "Prompts");
        test($(Button.class, inspector)
                .withCondition(button -> "Get prompt".equals(button.getTooltip().getText()))
                .first()).click();
        roundTrip();

        CompletableFuture<Optional<McpSchema.ReadResourceResult>> pendingElicitRead =
                CompletableFuture.supplyAsync(() ->
                        this.mcpClientService.readResourceAsOpt(this.serverInfo, "test://loopback/elicit"));
        pollUntil(() -> this.mcpClientService.snapshotPendingElicitations(this.serverInfo),
                (List<McpNotificationStore.PendingElicitation> pending) -> !pending.isEmpty());

        selectTab(tabSheet, "Elicitation");
        Button accept = pollUntil(() -> {
            roundTrip();
            return $(Button.class, inspector)
                    .withCondition(button -> "Accept".equals(button.getText()))
                    .all().stream().findFirst().orElse(null);
        }, Objects::nonNull);
        test(accept).click();

        McpSchema.ReadResourceResult elicitResult = pendingElicitRead.get(15, TimeUnit.SECONDS).orElseThrow();
        assertThat(((McpSchema.TextResourceContents) elicitResult.contents().getFirst()).text())
                .isEqualToIgnoringCase("elicit:accept");
    }

    @Test
    void hitlToolCallFromInspectorOutlivesTheClientRequestTimeoutUntilApproved() throws Exception {
        ToolCallback echo = FunctionToolCallback
                .builder(APPROVAL_TOOL, (EchoRequest request) -> "approved echo: " + request.text())
                .description("Echoes the text once a human approves.")
                .inputType(EchoRequest.class)
                .build();
        this.toolSpecService.addExternalMcpTool(echo, true);
        McpServerInfo builtIn = this.mcpServerInfoService.getDefaultMcpServerInfo();
        this.mcpClientService.startMcpClient(builtIn);
        try {
            List<McpSchema.Tool> tools = pollUntil(
                    () -> this.mcpClientService.getToolListAsOpt(builtIn).orElseGet(List::of),
                    (List<McpSchema.Tool> listed) -> listed.stream().anyMatch(tool -> APPROVAL_TOOL.equals(tool.name())));
            McpServerInspectorView inspector = new McpServerInspectorView(builtIn, this.mcpClientService,
                    tools, this.riskEvaluator);
            UI.getCurrent().add(inspector);
            roundTrip();

            ToolPrimitive card = $(ToolPrimitive.class, inspector)
                    .withCondition(primitive -> primitive.getElement().getTextRecursively().contains(APPROVAL_TOOL))
                    .first();
            test($(TextField.class, card).first()).setValue("hello");
            test($(Button.class, card)
                    .withCondition(button -> "Run tool".equals(button.getTooltip().getText()))
                    .first()).click();

            pollUntil(() -> this.mcpClientService.snapshotPendingElicitations(builtIn),
                    (List<McpNotificationStore.PendingElicitation> pending) -> !pending.isEmpty());
            drainUi();
            assertThat(card.getElement().getTextRecursively()).contains("Running...").contains("left to approve");

            Thread.sleep(25_000L);
            drainUi();
            assertThat(this.mcpClientService.snapshotPendingElicitations(builtIn)).hasSize(1);
            assertThat(card.getElement().getTextRecursively()).contains("Running...").doesNotContain("ERROR");

            Button accept = pollUntil(() -> {
                drainUi();
                return $(Button.class, card).withCondition(button -> "Accept".equals(button.getText()))
                        .all().stream().findFirst().orElse(null);
            }, Objects::nonNull);
            assertThat(card.getElement().getTextRecursively())
                    .contains("Approval needed:").contains("left to answer");
            assertThat($(Notification.class).all()).isEmpty();
            test(accept).click();

            String output = pollUntil(() -> {
                drainUi();
                return card.getElement().getTextRecursively();
            }, text -> text.contains("approved echo: hello"));
            assertThat(output).contains("approved echo: hello").doesNotContain("Running...");
            assertThat(this.mcpClientService.snapshotPendingElicitations(builtIn)).isEmpty();
        } finally {
            this.toolSpecService.removeExternalMcpTool(APPROVAL_TOOL);
        }
    }

    @Test
    void builtInInspectorShowsTheComposedLevelOfProxiedTools() throws Exception {
        McpToolRiskComposer.Composed lowRisk =
                new McpToolRiskComposer.Composed(RiskLevel.L2, RiskLevel.L1, RiskLevel.L2, null);
        WrappedExternalToolCallback plain = new WrappedExternalToolCallback(echoCallback("upstreamEcho"),
                "composition", "Exposed Tools", "proxied_echo", "everything", "STDIO", lowRisk, Set.of());
        WrappedExternalToolCallback gated = new WrappedExternalToolCallback(echoCallback("upstreamSum"),
                "composition", "Exposed Tools", "proxied_sum", "everything", "STDIO", lowRisk, Set.of());
        this.toolSpecService.addExternalMcpTool(plain, false);
        this.toolSpecService.addExternalMcpTool(gated, true);
        McpServerInfo builtIn = this.mcpServerInfoService.getDefaultMcpServerInfo();
        this.mcpClientService.startMcpClient(builtIn);
        try {
            List<McpSchema.Tool> tools = pollUntil(
                    () -> this.mcpClientService.getToolListAsOpt(builtIn).orElseGet(List::of),
                    (List<McpSchema.Tool> listed) -> listed.stream().map(McpSchema.Tool::name).toList()
                            .containsAll(List.of("proxied_echo", "proxied_sum")));
            McpServerInspectorView inspector = new McpServerInspectorView(builtIn, this.mcpClientService,
                    tools, this.riskEvaluator);
            UI.getCurrent().add(inspector);
            roundTrip();

            assertThat(cardText(inspector, "proxied_echo")).contains("L2 - Low").doesNotContain("L0");
            assertThat(cardText(inspector, "proxied_sum")).contains("L2 → L1 - Safe");
        } finally {
            this.toolSpecService.removeExternalMcpTool("proxied_echo");
            this.toolSpecService.removeExternalMcpTool("proxied_sum");
        }
    }

    private static ToolCallback echoCallback(String name) {
        return FunctionToolCallback.builder(name, (EchoRequest request) -> request.text())
                .description("Echoes the text.")
                .inputType(EchoRequest.class)
                .build();
    }

    private String cardText(McpServerInspectorView inspector, String toolName) {
        return $(ToolPrimitive.class, inspector)
                .withCondition(primitive -> primitive.getElement().getTextRecursively().contains(toolName))
                .first().getElement().getTextRecursively();
    }

    private void drainUi() {
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
        roundTrip();
    }

    private void selectTab(TabSheet tabSheet, String caption) {
        for (int i = 0; i < tabSheet.getTabCount(); i++) {
            Tab tab = tabSheet.getTabAt(i);
            if (tab.getElement().getTextRecursively().contains(caption)) {
                tabSheet.setSelectedIndex(i);
                roundTrip();
                return;
            }
        }
        throw new IllegalStateException("tab not found: " + caption);
    }

    private static <T> T pollUntil(Supplier<T> supplier, Predicate<T> ready) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        T value = supplier.get();
        while (!ready.test(value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            value = supplier.get();
        }
        return value;
    }

}
