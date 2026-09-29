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
package org.springaicommunity.playground.service.mcp.risk;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.catalog.McpRiskFactors;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRegistrationRiskPreviewTest {

    private McpRegistrationRiskPreview preview;

    @BeforeEach
    void setUp() {
        McpCatalogService catalogService = new McpCatalogService(new ObjectMapper());
        McpServerRiskCalculator calc = new McpServerRiskCalculator(McpRiskSignalSink.NOOP);
        SpringAiPlaygroundOptions options = mock(SpringAiPlaygroundOptions.class);
        when(options.builtInMcpServer()).thenReturn(new SpringAiPlaygroundOptions.BuiltInMcpServer("builtin", null, null));
        this.preview = new McpRegistrationRiskPreview(catalogService, calc, options);
    }

    @Test
    void catalogMatchingUrlReturnsCatalogMatched() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "GitHub Test", McpTransportType.STREAMABLE_HTTP, "https://api.githubcopilot.com/mcp/",
                McpAuthMode.OAUTH_STANDARD, "tester", "data note", "credential note",
                true, true, McpRiskFactors.SAFE_READ_ONLY, List.of("read:repo"));
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertTrue(result.catalogMatched());
        assertEquals("GitHub", result.catalogId());
        assertNotNull(result.serverResult());
    }

    @Test
    void userTypedUnknownHostResolvesNoCatalogMatch() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "Random MCP", McpTransportType.STREAMABLE_HTTP, "https://random-host-not-in-catalog.example.com/mcp",
                McpAuthMode.NONE, null, null, null,
                false, false, McpRiskFactors.SAFE_READ_ONLY, List.of());
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertFalse(result.catalogMatched());
        assertEquals(RiskLevel.L5, result.serverResult().level(),
                "user-typed unknown + no auth should trigger L5 floor");
    }

    @Test
    void rationaleIncludesAllAxes() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "Custom MCP", McpTransportType.STREAMABLE_HTTP, "https://my-server.example.com/mcp",
                McpAuthMode.API_KEY, "Alice", "data policy text", "credential policy text",
                true, true, McpRiskFactors.SAFE_READ_ONLY, List.of("read"));
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertTrue(result.rationale().containsKey("transport"));
        assertTrue(result.rationale().containsKey("auth"));
        assertTrue(result.rationale().containsKey("trust"));
        assertTrue(result.rationale().containsKey("doc"));
    }

    @Test
    void floorTriggerAppearsInRationale() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "Risky", McpTransportType.STREAMABLE_HTTP, "https://unknown-host.example.com/mcp",
                McpAuthMode.NONE, null, null, null,
                false, false, new McpRiskFactors(true, false, false, false), List.of());
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertEquals(RiskLevel.L5, result.serverResult().level());
        assertNotNull(result.serverResult().floorTrigger());
        assertTrue(result.rationale().containsKey("floor"));
    }

    @Test
    void stdioCatalogServerMatchesByServerNameAndIsNotCritical() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "MCP-Everything", McpTransportType.STDIO, null,
                McpAuthMode.NONE, null, null, null,
                true, true, McpRiskFactors.SAFE_READ_ONLY, List.of());
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertTrue(result.catalogMatched(),
                "STDIO catalog server must match by server name (no host to match on)");
        assertTrue(result.catalogId().startsWith("MCP-Everything"));
        assertEquals(RiskLevel.L1, result.serverResult().level(),
                "vendor-official STDIO catalog server with adequate docs: transport=0, auth=0, "
                        + "trust=0 (vendor-official), doc=0 → L1");
        assertEquals("catalog-matched (0)", result.rationale().get("trust"));
    }

    @Test
    void stdioUnknownServerStaysUserTyped() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "totally-custom-local-script", McpTransportType.STDIO, null,
                McpAuthMode.NONE, null, null, null,
                true, true, McpRiskFactors.SAFE_READ_ONLY, List.of());
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertFalse(result.catalogMatched(),
                "a STDIO server not matching any catalog id stays user-typed");
    }

    @Test
    void loopbackUrlYieldsCriticalLevelWhenUserTypedUnknown() {
        McpRegistrationRiskPreview.WizardInput input = new McpRegistrationRiskPreview.WizardInput(
                "Local Dev", McpTransportType.STREAMABLE_HTTP, "http://127.0.0.1:8080/mcp",
                McpAuthMode.NONE, "dev", "data ok", "credential ok",
                true, true, McpRiskFactors.SAFE_READ_ONLY, List.of());
        McpRegistrationRiskPreview.PreviewResult result = preview.preview(input);
        assertEquals(RiskLevel.L5, result.serverResult().level(),
                "loopback no-auth + user-typed unknown: transport=0, auth=0, trust=+2 (unknown), "
                        + "doc=+2 (cap, no catalog → docsUrl absent + docsAdequate false) → score 4 → L5. "
                        + "To get lower, register via catalog (vendor-official → trust 0, docsAdequate set).");
    }

    @Test
    void registrationPreviewBypassesTheRiskModelForTheBuiltInServer() {
        McpRegistrationRiskPreview.RegistrationPreview result = preview.preview(
                new McpRegistrationRiskPreview.RegistrationInput("BUILTIN", McpTransportType.STREAMABLE_HTTP,
                        "http://127.0.0.1:8282", Map.of(), false));

        assertTrue(result.builtIn());
        assertEquals(RiskLevel.L0, result.level());
        assertFalse(result.requiresConfirmation());
        assertTrue(result.rationale().isEmpty());
    }

    @Test
    void registrationPreviewClassifiesAuthFromHeadersAndFlagsHighRisk() {
        McpRegistrationRiskPreview.RegistrationPreview unauthenticated = preview.preview(
                new McpRegistrationRiskPreview.RegistrationInput("Random MCP", McpTransportType.STREAMABLE_HTTP,
                        "https://random-host-not-in-catalog.example.com/mcp", Map.of(), false));
        assertFalse(unauthenticated.builtIn());
        assertEquals(RiskLevel.L5, unauthenticated.level());
        assertTrue(unauthenticated.requiresConfirmation());
        assertTrue(unauthenticated.rationale().get("auth").startsWith("none"));

        McpRegistrationRiskPreview.RegistrationPreview bearer = preview.preview(
                new McpRegistrationRiskPreview.RegistrationInput("Random MCP", McpTransportType.STREAMABLE_HTTP,
                        "https://random-host-not-in-catalog.example.com/mcp",
                        Map.of("Authorization", "Bearer x"), false));
        assertTrue(bearer.rationale().get("auth").startsWith("bearer"));

        McpRegistrationRiskPreview.RegistrationPreview stdio = preview.preview(
                new McpRegistrationRiskPreview.RegistrationInput("local-tool", McpTransportType.STDIO,
                        "ignored-for-stdio", Map.of(), false));
        assertTrue(stdio.rationale().containsKey("transport"));
        assertNotNull(stdio.level());
    }
}
