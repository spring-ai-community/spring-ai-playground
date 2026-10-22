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

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.service.management.ManagementTestSupport.EchoData;
import org.springaicommunity.playground.service.management.ManagementTestSupport.EchoInput;
import org.springaicommunity.playground.service.mcp.ApprovalWaitCountdown;
import org.springaicommunity.playground.service.mcp.McpServerHitlToolGate;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagementApprovalGateTest {

    private static final String LOOPBACK_NAME = "spring-ai-playground-built-in";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<McpClientService> clientServiceProvider = mock(ObjectProvider.class);
    private final McpClientService clientService = mock(McpClientService.class);
    private final McpRiskEventRingBuffer riskEvents = new McpRiskEventRingBuffer();
    private final AtomicInteger handlerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(clientServiceProvider.getIfAvailable()).thenReturn(clientService);
        when(clientService.selfLoopbackServerName()).thenReturn(LOOPBACK_NAME);
        when(clientService.isSelfLoopbackClient(any())).thenAnswer(invocation -> {
            Implementation info = invocation.getArgument(0);
            return info != null && info.name().endsWith(LOOPBACK_NAME);
        });
    }

    private ManagementApprovalGate gate(ApprovalMode mode, ManagementAuditLog[] auditHolder) {
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(mode);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        auditHolder[0] = auditLog;
        return new ManagementApprovalGate(options, clientServiceProvider, auditLog);
    }

    private ManagementTool readTool() {
        return ManagementTool.named("saip_test_read", "test", EchoInput.class).data(EchoData.class)
                .description("read").handle((input, caller) -> {
                    handlerCalls.incrementAndGet();
                    return ManagementResult.ok("read " + input.text(), new EchoData(input.text()));
                });
    }

    private ManagementTool writeTool() {
        return ManagementTool.named("saip_test_write", "test", EchoInput.class).data(EchoData.class)
                .description("write").write(input -> "write " + input.text()).handle((input, caller) -> {
                    handlerCalls.incrementAndGet();
                    return ManagementResult.persisted("wrote " + input.text(), new EchoData(input.text()));
                });
    }

    private ManagementTool destructiveTool() {
        return ManagementTool.named("saip_test_delete", "test", EchoInput.class).data(EchoData.class)
                .description("delete").destructive(input -> "delete " + input.text()).handle((input, caller) -> {
                    handlerCalls.incrementAndGet();
                    return ManagementResult.persisted("deleted " + input.text(), new EchoData(input.text()));
                });
    }

    private CallToolResult call(ApprovalMode mode, ManagementTool tool, McpSyncServerExchange exchange,
            Map<String, Object> arguments) {
        ManagementAuditLog[] holder = new ManagementAuditLog[1];
        SyncToolSpecification specification = gate(mode, holder).wrap(tool);
        return specification.callHandler().apply(exchange, new CallToolRequest(tool.name(), arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    void aProgressTokenInTheRequestMetaForwardsProgressToTheClient() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", false);
        ManagementTool tool = ManagementTool.named("saip_test_progress", "test", EchoInput.class)
                .description("progress").handle((input, caller) -> {
                    caller.progress().report(1, 3.0, "step one");
                    return ManagementResult.ok("done", null);
                });
        ManagementAuditLog[] holder = new ManagementAuditLog[1];
        SyncToolSpecification specification = gate(ApprovalMode.ALL, holder).wrap(tool);

        specification.callHandler().apply(exchange, new CallToolRequest(tool.name(), Map.of("text", "x"),
                Map.of("progressToken", "tok-1")));
        specification.callHandler().apply(exchange, new CallToolRequest(tool.name(), Map.of("text", "x")));

        ArgumentCaptor<ProgressNotification> notification = ArgumentCaptor.forClass(ProgressNotification.class);
        verify(exchange, times(1)).progressNotification(notification.capture());
        assertThat(notification.getValue().progressToken()).isEqualTo("tok-1");
        assertThat(notification.getValue().progress()).isEqualTo(1.0);
        assertThat(notification.getValue().total()).isEqualTo(3.0);
        assertThat(notification.getValue().message()).isEqualTo("step one");
    }

    @Test
    void readToolRunsWithoutApproval() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", false);

        CallToolResult result = call(ApprovalMode.ALL, readTool(), exchange, Map.of("text", "hi"));

        assertThat(result.isError()).isFalse();
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
        assertThat(structured.get("ok")).isEqualTo(true);
        assertThat(((Map<?, ?>) structured.get("data")).get("echoed")).isEqualTo("hi");
        verify(exchange, never()).createElicitation(any());
    }

    @Test
    void destructiveToolAsksAndRunsWhenAccepted() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenReturn(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of()));

        CallToolResult result = call(ApprovalMode.DESTRUCTIVE, destructiveTool(), exchange, Map.of("text", "x"));

        assertThat(result.isError()).isFalse();
        assertThat(handlerCalls.get()).isEqualTo(1);
        ArgumentCaptor<ElicitRequest> captor = ArgumentCaptor.forClass(ElicitRequest.class);
        verify(exchange).createElicitation(captor.capture());
        assertThat(captor.getValue().message()).contains("[DESTRUCTIVE]", "cursor 1.0.0", "delete x");
        assertThat(captor.getValue().message()).endsWith("delete x Answer within 180 s, or the call is refused.");
    }

    @Test
    void waitingForApprovalStreamsProgressWhenTheRequestCarriesAToken() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of());
        });
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider,
                ManagementTestSupport.auditLog(options, riskEvents), new ApprovalWaitCountdown(180, Duration.ofMillis(20)));

        CallToolResult result = gate.wrap(destructiveTool()).callHandler().apply(exchange,
                new CallToolRequest("saip_test_delete", Map.of("text", "x"), Map.of("progressToken", "tok-2")));

        assertThat(result.isError()).isFalse();
        ArgumentCaptor<ProgressNotification> captor = ArgumentCaptor.forClass(ProgressNotification.class);
        verify(exchange, atLeastOnce()).progressNotification(captor.capture());
        assertThat(captor.getValue().progressToken()).isEqualTo("tok-2");
        assertThat(captor.getValue().total()).isEqualTo(180.0);
        assertThat(captor.getValue().message()).endsWith(" s left");
    }

    @Test
    void anApprovalTheInspectorLetExpireIsAuditedAsATimeout() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenReturn(McpServerHitlToolGate.approvalExpired());
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider, auditLog);

        CallToolResult result = gate.wrap(destructiveTool()).callHandler()
                .apply(exchange, new CallToolRequest("saip_test_delete", Map.of("text", "x")));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("no approval within 180 s");
        assertThat(auditLog.snapshot()).singleElement()
                .extracting(ManagementAuditLog.Entry::outcome).isEqualTo(ManagementOutcome.TIMEOUT);
    }

    @Test
    void anAnswerThatArrivesAfterTheWaitIsAuditedAsATimeout() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of());
        });
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider, auditLog,
                new ApprovalWaitCountdown(1, Duration.ofMillis(200)));

        CallToolResult result = gate.wrap(destructiveTool()).callHandler()
                .apply(exchange, new CallToolRequest("saip_test_delete", Map.of("text", "x")));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("no approval within 1 s");
        assertThat(auditLog.snapshot()).singleElement()
                .extracting(ManagementAuditLog.Entry::outcome).isEqualTo(ManagementOutcome.TIMEOUT);
    }

    @Test
    void declinedApprovalDoesNotRunAndIsAudited() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenReturn(new ElicitResult(ElicitResult.Action.DECLINE, Map.of()));
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider, auditLog);

        CallToolResult result = gate.wrap(destructiveTool()).callHandler()
                .apply(exchange, new CallToolRequest("saip_test_delete", Map.of("text", "x")));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("declined");
        assertThat(auditLog.snapshot()).singleElement()
                .extracting(ManagementAuditLog.Entry::outcome).isEqualTo(ManagementOutcome.DECLINED);
        assertThat(riskEvents.snapshot()).singleElement()
                .extracting(McpRiskEventRingBuffer.RiskEvent::type).isEqualTo("MANAGEMENT");
    }

    @Test
    void clientWithoutElicitationIsDeniedForApprovalTools() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("script", false);

        CallToolResult result = call(ApprovalMode.DESTRUCTIVE, destructiveTool(), exchange, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("no elicitation support");
    }

    @Test
    void writeToolAsksOnlyWhenApprovalIsAll() {
        McpSyncServerExchange destructiveMode = ManagementTestSupport.exchange("cursor", true);
        call(ApprovalMode.DESTRUCTIVE, writeTool(), destructiveMode, Map.of("text", "x"));
        verify(destructiveMode, never()).createElicitation(any());

        McpSyncServerExchange allMode = ManagementTestSupport.exchange("cursor", true);
        when(allMode.createElicitation(any())).thenReturn(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of()));
        call(ApprovalMode.ALL, writeTool(), allMode, Map.of("text", "x"));
        verify(allMode).createElicitation(any());
    }

    @Test
    void noneModeRunsEverythingWithoutAsking() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("script", false);

        CallToolResult result = call(ApprovalMode.NONE, destructiveTool(), exchange, Map.of("text", "x"));

        assertThat(result.isError()).isFalse();
        assertThat(handlerCalls.get()).isEqualTo(1);
    }

    @Test
    void highRiskActionsAreRefusedWhenApprovalIsNone() {
        ManagementTool highRisk = ManagementTool.named("saip_test_risky", "test", EchoInput.class)
                .description("risky")
                .approval(ApprovalLevel.WRITE, input -> ManagementApproval.write("risky " + input.text()).markHighRisk())
                .handle((input, caller) -> {
                    handlerCalls.incrementAndGet();
                    return ManagementResult.ok("ran", null);
                });
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("script", true);

        CallToolResult result = call(ApprovalMode.NONE, highRisk, exchange, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("high-risk", "Requested: risky x");
    }

    @Test
    void highRiskWriteAsksEvenInDestructiveMode() {
        ManagementTool highRisk = ManagementTool.named("saip_test_risky", "test", EchoInput.class)
                .description("risky")
                .approval(ApprovalLevel.WRITE, input -> ManagementApproval.write("risky").markHighRisk())
                .handle((input, caller) -> ManagementResult.ok("ran", null));
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        when(exchange.createElicitation(any())).thenReturn(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of()));

        call(ApprovalMode.DESTRUCTIVE, highRisk, exchange, Map.of("text", "x"));

        verify(exchange).createElicitation(any());
    }

    @Test
    void chatLoopbackIsRefusedAndInspectorIsNot() {
        McpSyncServerExchange chat = ManagementTestSupport.exchange("Spring AI Playground - " + LOOPBACK_NAME, true);

        CallToolResult refused = call(ApprovalMode.DESTRUCTIVE, readTool(), chat, Map.of("text", "x"));

        assertThat(refused.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(refused)).contains("not available to this app's own chat");

        McpSyncServerExchange inspector = ManagementTestSupport.exchange("Spring AI Playground - " + LOOPBACK_NAME,
                true);
        ManagementAuditLog[] holder = new ManagementAuditLog[1];
        SyncToolSpecification specification = gate(ApprovalMode.DESTRUCTIVE, holder).wrap(readTool());
        CallToolRequest interactive = CallToolRequest.builder().name("saip_test_read")
                .arguments(Map.of("text", "x"))
                .meta(Map.of(McpServerHitlToolGate.INTERACTIVE_HITL_META_KEY, true)).build();

        CallToolResult allowed = specification.callHandler().apply(inspector, interactive);

        assertThat(allowed.isError()).isFalse();
        assertThat(handlerCalls.get()).isEqualTo(1);
    }

    @Test
    void unknownArgumentsAreRejectedWithoutRunning() {
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);

        CallToolResult result = call(ApprovalMode.ALL, readTool(), exchange, Map.of("text", "x", "typo", 1));

        assertThat(result.isError()).isTrue();
        assertThat(handlerCalls.get()).isZero();
        assertThat(text(result)).contains("Invalid arguments for 'saip_test_read'");
    }

    @Test
    void refusalsRaisedWhilePlanningTheApprovalBecomeErrorResultsWithoutPrompting() {
        ManagementTool refusing = ManagementTool.named("saip_test_plan", "test", EchoInput.class)
                .description("plans").approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    throw new IllegalStateException("already exists with name " + input.text());
                }).handle((input, caller) -> ManagementResult.ok("ran", null));
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.ALL);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider, auditLog);

        CallToolResult result = gate.wrap(refusing).callHandler()
                .apply(exchange, new CallToolRequest("saip_test_plan", Map.of("text", "wiki")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("already exists with name wiki");
        verify(exchange, never()).createElicitation(any());
        assertThat(auditLog.snapshot()).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(ManagementOutcome.ERROR);
            assertThat(entry.level()).isEqualTo(ApprovalLevel.DESTRUCTIVE);
        });
    }

    @Test
    void handlerExceptionsBecomeErrorResultsWithMaskedSecrets() {
        ManagementTool failing = ManagementTool.named("saip_test_fail", "test", EchoInput.class)
                .description("fails").handle((input, caller) -> {
                    throw new IllegalStateException("upstream said Authorization: Bearer abcdefghijklmnop");
                });
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);

        CallToolResult result = call(ApprovalMode.ALL, failing, exchange, Map.of("text", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Bearer ***").doesNotContain("abcdefghijklmnop");
    }

    @Test
    void auditSummaryMasksSensitiveArguments() {
        SpringAiPlaygroundOptions options = ManagementTestSupport.options(ApprovalMode.ALL);
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(options, riskEvents);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, clientServiceProvider, auditLog);
        ManagementTool tool = ManagementTool.named("saip_test_secret", "test", SecretInput.class)
                .description("secret").handle((input, caller) -> ManagementResult.ok("ok", null));
        McpSyncServerExchange exchange = ManagementTestSupport.exchange("cursor", true);

        gate.wrap(tool).callHandler().apply(exchange,
                new CallToolRequest("saip_test_secret", Map.of("apiToken", "super-secret-value")));

        assertThat(auditLog.snapshot()).singleElement()
                .satisfies(entry -> assertThat(entry.summary()).contains("***").doesNotContain("super-secret-value"));
    }

    record SecretInput(String apiToken) {
    }

}
