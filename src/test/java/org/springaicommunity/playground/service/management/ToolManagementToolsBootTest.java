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
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.management.ToolAuthoringGuide.Topic;
import org.springaicommunity.playground.service.management.ToolsManagementContribution.DraftInput;
import org.springaicommunity.playground.service.management.ToolsManagementContribution.ParamSpec;
import org.springaicommunity.playground.service.management.ToolsManagementContribution.SandboxSpec;
import org.springaicommunity.playground.service.management.ToolsManagementContribution.VariableSpec;
import org.springaicommunity.playground.service.tool.ToolManifest.HumanInTheLoop;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.JsonSchemaType;
import org.springaicommunity.playground.service.tool.ToolSpec.ToolParamSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolDraftInput;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolMcpServerSetting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vaadin.productionMode=true",
        "spring.ai.playground.mcp-server.auth-token=it-secret-token"})
class ToolManagementToolsBootTest {

    private static final String TOOL = "it_convert";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RUNNABLE_BLOCK = Pattern.compile("```js\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern TOOL_NAME = Pattern.compile("saip_[a-z]+_[a-z_]+");
    private static final Pattern GUIDE_URI = Pattern.compile("saip://guide/[a-z/-]+");

    @TempDir
    static Path homeDir;

    @DynamicPropertySource
    static void isolatedHome(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.playground.user-home", () -> homeDir.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private ToolAuthoringGuide guide;

    private final AtomicReference<ElicitResult.Action> decision = new AtomicReference<>(ElicitResult.Action.ACCEPT);
    private final List<String> approvalMessages = new ArrayList<>();
    private ToolMcpServerSetting originalExposure;
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        this.originalExposure = this.toolSpecService.getToolMcpServerSetting();
        this.client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
                        .endpoint("/mcp")
                        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                                builder.header("Authorization", "Bearer it-secret-token"))
                        .build())
                .clientInfo(new Implementation("it-client", "9.9"))
                .capabilities(ClientCapabilities.builder().elicitation().build())
                .elicitation(request -> {
                    this.approvalMessages.add(request.message());
                    return new ElicitResult(this.decision.get(), Map.of());
                })
                .build();
        this.client.initialize();
    }

    @AfterEach
    void disconnect() {
        this.client.close();
        this.toolSpecService.getToolSpecAsOpt(TOOL)
                .ifPresent(spec -> this.toolSpecService.deleteToolSpec(spec.toolId()));
        this.toolSpecService.updateToolMcpServerSetting(this.originalExposure);
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return this.client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Map<String, Object> data(CallToolResult result) throws Exception {
        Map<String, Object> envelope = MAPPER.readValue(text(result), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        return data;
    }

    private String resource(String uri) {
        return ((TextResourceContents) this.client.readResource(new ReadResourceRequest(uri)).contents().getFirst())
                .text();
    }

    private List<String> toolNames() {
        return this.client.listTools().tools().stream().map(Tool::name).toList();
    }

    private static Map<String, Object> converter() {
        return Map.of("name", TOOL, "description", "Multiplies an amount by a rate to convert currencies.",
                "params", List.of(
                        Map.of("name", "amount", "type", "NUMBER", "description", "Amount, e.g. 10",
                                "testValue", "10"),
                        Map.of("name", "rate", "type", "NUMBER", "description", "Rate, e.g. 1.5",
                                "testValue", "1.5")),
                "code", "return { result: Number(amount) * Number(rate) };", "category", "MATH");
    }

    @Test
    void toolStudioToolsMonitorToolsGuidesAndPromptAreOffered() {
        assertThat(toolNames()).contains("saip_tools_list", "saip_tools_get", "saip_tools_save_draft",
                "saip_tools_test", "saip_tools_publish", "saip_tools_delete", "saip_tools_get_exposure",
                "saip_tools_set_exposure", "saip_tools_check_risk", "saip_monitor_overview", "saip_monitor_traces",
                "saip_monitor_trace_get", "saip_monitor_system", "saip_monitor_logs_tail");
        assertThat(this.client.listResources().resources()).extracting(Resource::uri)
                .contains("saip://guide/tool-authoring", "saip://guide/tool-authoring/runtime",
                        "saip://guide/tool-authoring/sandbox", "saip://guide/tool-authoring/testing",
                        "saip://guide/tool-authoring/mistakes", "saip://guide/tool-authoring/examples");
        assertThat(this.client.listResourceTemplates().resourceTemplates()).extracting(ResourceTemplate::uriTemplate)
                .contains("saip://examples/{name}");
        assertThat(this.client.listPrompts().prompts()).extracting(Prompt::name).contains("saip_create_tool");
        GetPromptResult prompt = this.client.getPrompt(new GetPromptRequest("saip_create_tool",
                Map.of("goal", "make a currency conversion tool")));
        assertThat(((TextContent) prompt.messages().getFirst().content()).text())
                .contains("make a currency conversion tool").contains("saip_tools_publish");
        assertThat(((TextResourceContents) ((EmbeddedResource) prompt.messages().get(1).content()).resource())
                .text()).contains("Making a tool for this app");
    }

    @Test
    void anAgentAuthorsTestsPublishesExposesAndDeletesAToolWithApprovalsAtTheRightSteps() throws Exception {
        CallToolResult saved = call("saip_tools_save_draft", converter());
        assertThat(saved.isError()).as(text(saved)).isFalse();
        assertThat(text(saved)).contains("Saved draft").contains("not visible to MCP clients");
        assertThat(data(saved)).containsEntry("riskLevel", "L0").containsEntry("humanApproval", false);
        assertThat(this.approvalMessages).isEmpty();
        assertThat(toolNames()).doesNotContain(TOOL);

        CallToolResult listed = call("saip_tools_list", Map.of("status", "DRAFT"));
        assertThat(text(listed)).contains(TOOL).contains("\"draft\":true");
        CallToolResult detail = call("saip_tools_get", Map.of("name", TOOL));
        assertThat(text(detail)).contains("Number(amount) * Number(rate)").contains("\"testValue\":\"1.5\"");

        CallToolResult tested = call("saip_tools_test", Map.of("name", TOOL));
        assertThat(tested.isError()).as(text(tested)).isFalse();
        Map<String, Object> testData = data(tested);
        assertThat(testData).containsEntry("localPass", true).containsEntry("riskLevel", "L0");
        assertThat(String.valueOf(testData.get("result"))).contains("15");
        assertThat(testData.get("testedParams")).isEqualTo(List.of("amount", "rate"));
        assertThat(this.approvalMessages).isEmpty();

        this.decision.set(ElicitResult.Action.DECLINE);
        CallToolResult declined = call("saip_tools_publish", Map.of("name", TOOL));
        assertThat(declined.isError()).isTrue();
        assertThat(text(declined)).contains("declined");
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).get().extracting(ToolSpec::draft).isEqualTo(true);
        assertThat(toolNames()).doesNotContain(TOOL);

        this.decision.set(ElicitResult.Action.ACCEPT);
        CallToolResult published = call("saip_tools_publish", Map.of("name", TOOL));
        assertThat(published.isError()).as(text(published)).isFalse();
        assertThat(this.approvalMessages).last().asString().contains("[DESTRUCTIVE]", "it-client 9.9", TOOL,
                "visible to MCP clients");
        assertThat(data(published)).containsEntry("published", true).containsEntry("exposed", true);
        assertThat(toolNames()).contains(TOOL);
        CallToolResult called = call(TOOL, Map.of("amount", 10, "rate", 1.5));
        assertThat(text(called)).contains("15");

        CallToolResult overwrite = call("saip_tools_save_draft", converter());
        assertThat(overwrite.isError()).isTrue();
        assertThat(text(overwrite)).contains("already published");

        CallToolResult unexposed = call("saip_tools_set_exposure",
                Map.of("autoAdd", false, "stopExposing", List.of(TOOL)));
        assertThat(unexposed.isError()).as(text(unexposed)).isFalse();
        assertThat(this.approvalMessages).last().asString().contains("stop exposing", TOOL,
                "automatic exposure of new tools: off");
        assertThat(toolNames()).doesNotContain(TOOL);
        Map<String, Object> exposure = data(call("saip_tools_get_exposure", Map.of()));
        assertThat(exposure).containsEntry("autoAdd", false);
        assertThat(exposure.get("customNotExposed")).isEqualTo(List.of(TOOL));

        CallToolResult exposedAgain = call("saip_tools_set_exposure", Map.of("expose", List.of(TOOL)));
        assertThat(exposedAgain.isError()).as(text(exposedAgain)).isFalse();
        assertThat(toolNames()).contains(TOOL);

        CallToolResult deleted = call("saip_tools_delete", Map.of("name", TOOL));
        assertThat(deleted.isError()).as(text(deleted)).isFalse();
        assertThat(this.approvalMessages).last().asString().contains("[DESTRUCTIVE]", "Delete the tool");
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).isEmpty();
        assertThat(toolNames()).doesNotContain(TOOL);

        String log = text(call("saip_monitor_management_log", Map.of()));
        assertThat(log).contains("saip_tools_save_draft", "saip_tools_publish", "DECLINED", "it-client 9.9");
        assertThat(log).doesNotContain("it-secret-token");
    }

    @Test
    void testValuesMayBeNumbersBooleansOrJsonAsWellAsText() throws Exception {
        Map<String, Object> draft = with(converter(), "params", List.of(
                Map.of("name", "amount", "type", "NUMBER", "description", "Amount", "testValue", 10),
                Map.of("name", "rate", "type", "NUMBER", "description", "Rate", "testValue", 1.5),
                Map.of("name", "flags", "type", "ARRAY", "description", "Flags", "testValue", List.of(1, 2))));

        CallToolResult saved = call("saip_tools_save_draft", draft);
        CallToolResult tested = call("saip_tools_test", Map.of("name", TOOL));

        assertThat(saved.isError()).as(text(saved)).isFalse();
        assertThat(tested.isError()).as(text(tested)).isFalse();
        assertThat(data(tested).get("testedParams")).isEqualTo(List.of("amount", "rate", "flags"));
        assertThat(text(call("saip_tools_get", Map.of("name", TOOL)))).contains("\"testValue\":\"10\"",
                "\"testValue\":\"[1,2]\"");
    }

    @Test
    void publishingWithAutomaticExposureOffLeavesTheToolPrivateAndSaysSo() throws Exception {
        this.toolSpecService.updateExposure(new ToolSpecService.ExposureSelection(false, Set.of(), Set.of()));
        call("saip_tools_save_draft", converter());

        CallToolResult published = call("saip_tools_publish", Map.of("name", TOOL));

        assertThat(published.isError()).as(text(published)).isFalse();
        assertThat(this.approvalMessages).last().asString().contains("will not be visible to MCP clients");
        Map<String, Object> data = data(published);
        assertThat(data).containsEntry("published", true).containsEntry("exposed", false);
        assertThat(String.valueOf(data.get("note"))).contains("saip_tools_set_exposure");
        assertThat(text(published)).contains("Not exposed on the built-in MCP server yet");
        assertThat(toolNames()).doesNotContain(TOOL);
    }

    @Test
    void aFailingTestExplainsHowToFixItAndNothingIsPublished() throws Exception {
        Map<String, Object> draft = new HashMap<>(converter());
        draft.put("code", "return typoName + Number(amount);");
        draft.put("params", List.of(Map.of("name", "amount", "type", "NUMBER", "description", "Amount")));
        call("saip_tools_save_draft", draft);

        CallToolResult tested = call("saip_tools_test", Map.of("name", TOOL));

        assertThat(tested.isError()).isTrue();
        Map<String, Object> data = data(tested);
        assertThat(data).containsEntry("localPass", false);
        assertThat(String.valueOf(data.get("error"))).contains("typoName");
        assertThat(String.valueOf(data.get("hints"))).contains("'typoName' is not defined")
                .contains("No testValue for [amount]");
        CallToolResult published = call("saip_tools_publish", Map.of("name", TOOL));
        assertThat(published.isError()).isTrue();
        assertThat(text(published)).contains("Not published");
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).get().extracting(ToolSpec::draft).isEqualTo(true);
    }

    @Test
    void aTestOfCodeWithMoreThanTheBaselineAsksEvenWhenApprovalIsOnlyForDestructiveActions() throws Exception {
        Map<String, Object> request = Map.of("draft", Map.of("name", TOOL, "description", "Network open probe",
                "code", "return 'ran';", "sandbox", Map.of("networkMode", "OPEN")));

        this.decision.set(ElicitResult.Action.DECLINE);
        CallToolResult declined = call("saip_tools_test", request);
        assertThat(declined.isError()).isTrue();
        assertThat(text(declined)).contains("declined");
        assertThat(this.approvalMessages).last().asString().contains("[WRITE]", "networkMode=open", "risk L4");

        this.decision.set(ElicitResult.Action.ACCEPT);
        CallToolResult accepted = call("saip_tools_test", request);
        assertThat(accepted.isError()).as(text(accepted)).isFalse();
        assertThat(data(accepted)).containsEntry("riskLevel", "L4").containsEntry("elevated", true);
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).isEmpty();

        int approvals = this.approvalMessages.size();
        Map<String, Object> locked = Map.of("draft", Map.of("name", TOOL, "description", "Locked probe",
                "code", "return 1;"));
        assertThat(call("saip_tools_test", locked).isError()).isFalse();
        assertThat(this.approvalMessages).hasSize(approvals);
    }

    @Test
    void aDraftSentAsAStringHoldingJsonIsRefusedBySchemaValidationAndTheGuidesSayWhy() throws Exception {
        String draftJson = MAPPER.writeValueAsString(converter());

        CallToolResult result = call("saip_tools_test", Map.of("draft", draftJson));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("saip_tools_test", "input validation failed", "/draft");
        assertThat(this.approvalMessages).isEmpty();
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).isEmpty();
        assertThat(resource("saip://guide/tool-authoring/testing")).contains("never a string holding JSON");
        assertThat(resource("saip://guide/tool-authoring/mistakes")).contains("/draft: string found, object expected");
        assertThat(this.client.listTools().tools().stream().filter(tool -> tool.name().equals("saip_tools_test"))
                .findFirst().orElseThrow().inputSchema().toString()).contains("never a string holding JSON");
    }

    @Test
    void aToolAboveL0AsksAPersonOnEveryCallByDefault() throws Exception {
        CallToolResult saved = call("saip_tools_save_draft", allowlisted());
        assertThat(saved.isError()).as(text(saved)).isFalse();
        assertThat(data(saved)).containsEntry("riskLevel", "L3").containsEntry("humanApproval", true);
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).get()
                .extracting(spec -> spec.humanInTheLoop().mode()).isEqualTo(HumanInTheLoop.Mode.REQUIRED);

        CallToolResult published = call("saip_tools_publish", Map.of("name", TOOL));
        assertThat(published.isError()).as(text(published)).isFalse();
        assertThat(this.approvalMessages).last().asString().contains("risk L3", "A person approves every call");
        assertThat(data(published)).containsEntry("humanApproval", true);

        CallToolResult approved = call(TOOL, Map.of("amount", 10, "rate", 1.5));
        assertThat(approved.isError()).as(text(approved)).isNotEqualTo(true);
        assertThat(text(approved)).contains("15");
        assertThat(this.approvalMessages).last().asString().contains("Approve running tool", TOOL);

        this.decision.set(ElicitResult.Action.DECLINE);
        CallToolResult declined = call(TOOL, Map.of("amount", 10, "rate", 1.5));
        assertThat(declined.isError()).isTrue();
        assertThat(text(declined)).contains("approval was not granted");

        try (McpSyncClient unattended = clientWithoutElicitation()) {
            CallToolResult denied = unattended.callTool(new CallToolRequest(TOOL, Map.of("amount", 10, "rate", 1.5)));
            assertThat(denied.isError()).isTrue();
            assertThat(text(denied)).contains("approval was not granted");
        }
    }

    @Test
    void turningCallApprovalOffAboveL0IsStressedWhenAPersonApprovesPublishing() throws Exception {
        CallToolResult saved = call("saip_tools_save_draft", with(allowlisted(), "humanApproval", false));
        assertThat(data(saved)).containsEntry("riskLevel", "L3").containsEntry("humanApproval", false);
        assertThat(text(saved)).contains("WITHOUT human approval although the risk is L3");

        CallToolResult published = call("saip_tools_publish", Map.of("name", TOOL));
        assertThat(published.isError()).as(text(published)).isFalse();
        assertThat(this.approvalMessages).last().asString()
                .contains("Calls run WITHOUT human approval although the risk is L3");

        int approvals = this.approvalMessages.size();
        CallToolResult called = call(TOOL, Map.of("amount", 10, "rate", 1.5));
        assertThat(text(called)).contains("15");
        assertThat(this.approvalMessages).hasSize(approvals);
    }

    private static Map<String, Object> allowlisted() {
        return with(converter(), "sandbox", Map.of("networkMode", "ALLOWLIST", "hostsAllow", List.of("example.com")));
    }

    private McpSyncClient clientWithoutElicitation() {
        McpSyncClient unattended = McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://127.0.0.1:" + this.port)
                        .endpoint("/mcp")
                        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                                builder.header("Authorization", "Bearer it-secret-token"))
                        .build())
                .clientInfo(new Implementation("it-unattended", "1.0"))
                .build();
        unattended.initialize();
        return unattended;
    }

    @Test
    void invalidDraftsAreRefusedBeforeAnyApprovalWithAHelpfulMessage() {
        int approvals = this.approvalMessages.size();
        Map<String, Object> base = converter();

        assertRefused(with(base, "name", "saip_hack"), "reserved");
        assertRefused(with(base, "name", "1bad"), "letters");
        assertRefused(with(base, "staticVariables", List.of(Map.of("name", "apiKey", "value", "sk-live-1234"))),
                "${ENV_NAME}");
        assertRefused(with(base, "params", List.of(Map.of("name", "fetch", "type", "STRING"))), "runtime global");
        assertRefused(with(base, "params", List.of(Map.of("name", "a b", "type", "STRING"))), "identifier");
        assertRefused(with(base, "category", "NOPE"), "Unknown category");
        assertRefused(with(base, "sandbox", Map.of("networkMode", "ALLOWLIST")), "hostsAllow");
        assertRefused(with(base, "sandbox", Map.of("hostsAllow", List.of("a.example.com"))), "ALLOWLIST");
        assertRefused(with(base, "code", ""), "code is required");

        assertThat(this.approvalMessages).hasSize(approvals);
        assertThat(this.toolSpecService.getToolSpecAsOpt(TOOL)).isEmpty();
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private void assertRefused(Map<String, Object> draft, String expectedText) {
        CallToolResult result = call("saip_tools_save_draft", draft);
        assertThat(result.isError()).as(draft.toString()).isTrue();
        assertThat(text(result)).contains(expectedText);
    }

    @Test
    void bundledToolsCanBeReadAndTestedByNameButNeverPublishedDeletedOrOverwritten() {
        assertThat(text(call("saip_tools_get", Map.of("name", "convertCurrency")))).contains("open.er-api.com");
        assertThat(text(call("saip_tools_list", Map.of("scope", "BUILTIN", "query", "currency"))))
                .contains("convertCurrency");

        CallToolResult deleted = call("saip_tools_delete", Map.of("name", "convertCurrency"));
        CallToolResult overwritten = call("saip_tools_save_draft", with(converter(), "name", "convertCurrency"));

        assertThat(deleted.isError()).isTrue();
        assertThat(text(deleted)).contains("bundled");
        assertThat(overwritten.isError()).isTrue();
        assertThat(text(overwritten)).contains("bundled");
        assertThat(this.approvalMessages).isEmpty();
    }

    @Test
    void exposureChangesRefuseUnknownAndMisplacedToolNamesBeforeAsking() throws Exception {
        assertThat(data(call("saip_tools_get_exposure", Map.of()))).containsKey("builtinAvailable");

        CallToolResult unknown = call("saip_tools_set_exposure", Map.of("expose", List.of("ghost")));
        call("saip_tools_save_draft", converter());
        CallToolResult draft = call("saip_tools_set_exposure", Map.of("expose", List.of(TOOL)));
        CallToolResult both = call("saip_tools_set_exposure", Map.of("expose", List.of("stats"),
                "stopExposing", List.of("stats")));

        assertThat(unknown.isError()).isTrue();
        assertThat(text(unknown)).contains("No tool named 'ghost'");
        assertThat(draft.isError()).isTrue();
        assertThat(text(draft)).contains("is a draft");
        assertThat(both.isError()).isTrue();
        assertThat(text(both)).contains("both expose and stopExposing");
        assertThat(this.approvalMessages).isEmpty();
    }

    @Test
    void theRiskPreviewExplainsTheLevelAndWhatApprovalToExpect() throws Exception {
        CallToolResult risky = call("saip_tools_check_risk", Map.of("sandbox",
                Map.of("networkMode", "OPEN", "fileWrite", true), "code", "return await fetch(url);"));
        Map<String, Object> data = data(risky);

        assertThat(data).containsEntry("riskLevel", "L4").containsEntry("elevated", true)
                .containsEntry("humanApprovalByDefault", true);
        assertThat(String.valueOf(data.get("reasons"))).contains("networkMode=open", "fileWrite=true");
        assertThat(String.valueOf(data.get("suggestions"))).contains("allowlist");
        assertThat(String.valueOf(data.get("testApproval"))).contains("Asks");
        assertThat(String.valueOf(data.get("publishApproval"))).contains("Asks");

        Map<String, Object> locked = data(call("saip_tools_check_risk", Map.of("code", "return await fetch(url);")));
        assertThat(locked).containsEntry("riskLevel", "L0").containsEntry("elevated", false)
                .containsEntry("humanApprovalByDefault", false);
        assertThat(String.valueOf(locked.get("warnings"))).contains("blocks the network");
        assertThat(String.valueOf(locked.get("testApproval"))).contains("Runs without asking");
        assertThat(this.approvalMessages).isEmpty();
    }

    @Test
    void guideTopicsAreCurrentCompactAndConsistentWithTheToolContract() {
        Set<String> toolNames = new HashSet<>(toolNames());
        Set<String> resourceUris = new HashSet<>();
        this.client.listResources().resources().forEach(resource -> resourceUris.add(resource.uri()));
        StringBuilder all = new StringBuilder();

        for (Topic topic : ToolAuthoringGuide.TOPICS) {
            String text = resource(topic.uri());
            all.append(text);
            if (topic.file() != null) {
                assertThat(text).as(topic.uri()).hasSizeLessThan(7_000).doesNotContain("{{");
            }
            Matcher tools = TOOL_NAME.matcher(text);
            while (tools.find()) {
                assertThat(toolNames).as(topic.uri() + " mentions " + tools.group()).contains(tools.group());
            }
            Matcher uris = GUIDE_URI.matcher(text);
            while (uris.find()) {
                assertThat(resourceUris).as(topic.uri() + " links " + uris.group()).contains(uris.group());
            }
        }
        assertThat(resource("saip://guide/tool-authoring")).hasSizeLessThan(4_500);
        for (Class<?> record : List.of(DraftInput.class, ParamSpec.class, VariableSpec.class, SandboxSpec.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(all.toString()).as(record.getSimpleName() + "." + component.getName())
                        .contains("`" + component.getName() + "`");
            }
        }
    }

    @Test
    void theSandboxTopicShowsTheLiveBaselineAndARiskTableThatMatchesTheCalculator() {
        String sandbox = resource("saip://guide/tool-authoring/sandbox");

        assertThat(sandbox).contains("- Network: ", "`java.lang.System`", "Time limit: ", "statement budget");
        assertThat(sandbox).contains(
                "| Locked default, nothing requested | L0 | no |",
                "| networkMode ALLOWLIST with exact hosts | L3 | no |",
                "| networkMode STRICT (host known only at run time) | L3 | no |",
                "| fileRead | L3 | no |",
                "| networkMode OPEN | L4 | yes |",
                "| hostsAllow contains * | L4 | yes |",
                "| fileWrite | L4 | yes |",
                "| destructive | L5 | yes |",
                "| addAllowClasses with an ordinary class | L3 | yes |",
                "| addAllowClasses with a network class | L4 | yes |",
                "| addAllowClasses with java.lang.Runtime | L5 | yes |");
    }

    @Test
    void theGuidesPreferAnAllowlistOverStrictForHostsTheCodeNames() {
        assertThat(resource("saip://guide/tool-authoring/sandbox")).contains("## Choosing the network mode",
                "do not choose STRICT for a host you already know");
        assertThat(resource("saip://guide/tool-authoring/mistakes")).contains(
                "| STRICT for a host the code already names |",
                "| A parameter for a key that is already a `${ENV_NAME}` static variable |");
        assertThat(resource("saip://guide/tool-authoring")).contains("STRICT only when the host comes from the caller");
    }

    @Test
    void examplesAreListedAndReadableByNameWithACopyableDefinition() {
        String index = resource("saip://guide/tool-authoring/examples");
        assertThat(index).contains("Good starting points", "`convertCurrency`", "`stats`", "| L3 |");

        String example = resource("saip://examples/convertCurrency");
        assertThat(example).contains("# Example: convertCurrency", "hostsAllow=[open.er-api.com]",
                "networkMode=ALLOWLIST", "| from | STRING | true | USD |", "```js", "fetch(");
        assertThat(resource("saip://examples/CONVERTCURRENCY")).contains("# Example: convertCurrency");

        assertThatThrownBy(() -> resource("saip://examples/nope"))
                .hasMessageContaining("No example named 'nope'");
        assertThat(this.guide.exampleNames()).contains("convertCurrency", "stats", "parseCsv", "writeTextFile");
    }

    @Test
    void everyRunnableExampleInTheGuideReallyPassesInTheSandbox() throws Exception {
        int runnable = 0;
        for (Topic topic : ToolAuthoringGuide.TOPICS) {
            Matcher block = RUNNABLE_BLOCK.matcher(this.guide.render(topic));
            while (block.find()) {
                String code = block.group(1);
                String firstLine = code.lines().findFirst().orElseThrow();
                assertThat(firstLine).as(topic.uri()).startsWith("// params: ");
                Map<String, Object> values = MAPPER.readValue(firstLine.substring("// params: ".length()),
                        new TypeReference<>() { });
                List<ToolParamSpec> params = values.entrySet().stream().map(entry -> new ToolParamSpec(entry.getKey(),
                        "", true, entry.getValue() instanceof Number ? JsonSchemaType.NUMBER : JsonSchemaType.STRING,
                        String.valueOf(entry.getValue()))).toList();
                ToolSpecService.ToolTestResult result = this.toolSpecService.testRun(new ToolDraftInput(null,
                        "guideExample", "d", List.of(), params, code, null, Set.of(), null, null));
                assertThat(result.ok()).as(topic.uri() + ": " + result.result().error()).isTrue();
                assertThat(String.valueOf(result.result().result())).isNotBlank();
                runnable++;
            }
        }
        assertThat(runnable).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theRemainingMonitorToolsAnswerWithBoundedData() throws Exception {
        Map<String, Object> overview = data(call("saip_monitor_overview", Map.of("window", "1h")));
        assertThat(overview).containsKeys("window", "mcpCalls", "toolCalls", "llm", "conversations", "servers");
        assertThat(overview).containsEntry("window", "1h");

        Map<String, Object> traces = data(call("saip_monitor_traces", Map.of("limit", 5)));
        assertThat(traces).containsKey("total");

        CallToolResult missing = call("saip_monitor_trace_get", Map.of("traceId", "nope"));
        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).contains("saip_monitor_traces");

        Map<String, Object> system = data(call("saip_monitor_system", Map.of()));
        assertThat(system).containsKeys("heapUsedMegabytes", "threadsLive", "ollama");

        CallToolResult logs = call("saip_monitor_logs_tail", Map.of("level", "info", "lines", 5, "text", "mgmt"));
        assertThat(logs.isError()).as(text(logs)).isFalse();
        assertThat(text(logs)).doesNotContain("it-secret-token");
        assertThat(this.approvalMessages).isEmpty();
    }

}
