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

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService.ExposureRequest;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ExposureMode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExposedToolServiceTest {

    private McpExposedToolService newService(McpCompositionService compositionService,
            McpCompositionToolCallbackProvider provider, ToolSpecService toolSpecService) {
        return new McpExposedToolService(compositionService, provider, toolSpecService,
                mock(McpToolHashLedger.class), mock(McpServerInfoService.class), mock(McpClientService.class),
                mock(McpToolRiskEvaluator.class));
    }

    private ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    @Test
    void syncAddsMissingAndRemovesStaleExternalTools() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);

        McpComposition exposed = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools",
                "", List.of(), true, RiskLevel.L5, 0L, 0L, 0L);
        ToolCallback freshTool = callback("x");
        ToolCallback alreadyExposed = callback("y");
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BOTH);
        when(compositionService.getExposed()).thenReturn(Optional.of(exposed));
        when(provider.getToolCallbacksFor(exposed)).thenReturn(new ToolCallback[]{freshTool, alreadyExposed});
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("y", "z"));

        newService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("z");
        verify(toolSpecService, never()).removeExternalMcpTool("y");
        verify(toolSpecService).addExternalMcpTool(freshTool, false);
        verify(toolSpecService, never()).addExternalMcpTool(alreadyExposed, false);
    }

    @Test
    void syncPropagatesHitlFlagFromMember() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BOTH);

        McpComposition.Member member = new McpComposition.Member("srv", "tool", "x", null, true, "");
        McpComposition exposed = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools",
                "", List.of(member), true, RiskLevel.L5, 0L, 0L, 0L);
        ToolCallback hitlTool = callback("x");
        when(compositionService.getExposed()).thenReturn(Optional.of(exposed));
        when(provider.getToolCallbacksFor(exposed)).thenReturn(new ToolCallback[]{hitlTool});
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of());

        newService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).addExternalMcpTool(hitlTool, true);
    }

    @Test
    void syncReregistersExternalToolWhenHitlToggled() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BOTH);

        McpComposition.Member member = new McpComposition.Member("srv", "tool", "x", null, true, "");
        McpComposition exposed = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools",
                "", List.of(member), true, RiskLevel.L5, 0L, 0L, 0L);
        ToolCallback tool = callback("x");
        when(compositionService.getExposed()).thenReturn(Optional.of(exposed));
        when(provider.getToolCallbacksFor(exposed)).thenReturn(new ToolCallback[]{tool});
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("x"), Set.of());
        when(toolSpecService.humanInTheLoopFor("x")).thenReturn(null);

        newService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("x");
        verify(toolSpecService).addExternalMcpTool(tool, true);
    }

    @Test
    void syncWithNoExposedSetRemovesAllExternalTools() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BOTH);
        when(compositionService.getExposed()).thenReturn(Optional.empty());
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("a", "b"));

        newService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("a");
        verify(toolSpecService).removeExternalMcpTool("b");
    }

    @Test
    void syncWithComposedDisabledRemovesAllExternalToolsAndSkipsProvider() {
        McpCompositionService compositionService = mock(McpCompositionService.class);
        McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
        ToolSpecService toolSpecService = mock(ToolSpecService.class);
        when(toolSpecService.exposureMode()).thenReturn(ExposureMode.BUILTIN_ONLY);
        when(toolSpecService.getExternalMcpToolNames()).thenReturn(Set.of("p", "q"));

        newService(compositionService, provider, toolSpecService).sync();

        verify(toolSpecService).removeExternalMcpTool("p");
        verify(toolSpecService).removeExternalMcpTool("q");
        verify(provider, never()).getToolCallbacksFor(any());
        verify(toolSpecService, never()).addExternalMcpTool(any(), anyBoolean());
    }

    private record Rig(McpCompositionService compositionService, McpCompositionToolCallbackProvider provider,
            ToolSpecService toolSpecService, McpToolHashLedger hashLedger, McpServerInfoService serverInfoService,
            McpClientService clientService, McpToolRiskEvaluator riskEvaluator, McpExposedToolService service) {

        static Rig create() {
            McpCompositionService compositionService = mock(McpCompositionService.class);
            McpCompositionToolCallbackProvider provider = mock(McpCompositionToolCallbackProvider.class);
            ToolSpecService toolSpecService = mock(ToolSpecService.class);
            McpToolHashLedger hashLedger = mock(McpToolHashLedger.class);
            McpServerInfoService serverInfoService = mock(McpServerInfoService.class);
            McpClientService clientService = mock(McpClientService.class);
            McpToolRiskEvaluator riskEvaluator = mock(McpToolRiskEvaluator.class);
            McpExposedToolService service = new McpExposedToolService(compositionService, provider, toolSpecService,
                    hashLedger, serverInfoService, clientService, riskEvaluator);
            return new Rig(compositionService, provider, toolSpecService, hashLedger, serverInfoService,
                    clientService, riskEvaluator, service);
        }

        McpServerInfo connectedServer(String serverName, McpSchema.Tool... tools) {
            McpServerInfo server = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, serverName, "d", 0L, 0L,
                    "{}");
            when(this.clientService.isConnecting(server)).thenReturn(true);
            when(this.clientService.getToolListAsOpt(server)).thenReturn(Optional.of(List.of(tools)));
            return server;
        }

        void inherentRisk(McpServerInfo server, McpSchema.Tool tool, RiskLevel inherent) {
            when(this.riskEvaluator.evaluateTool(server, tool)).thenReturn(
                    new McpToolRiskEvaluator.ToolRiskView(inherent, inherent, inherent, null, null));
        }
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description(name + " tool").build();
    }

    @Test
    void exposureRequestRejectsAMemberAboveTheCapEvenWhenHitlIsOn() {
        Rig rig = Rig.create();
        McpSchema.Tool remove = tool("remove");
        McpServerInfo server = rig.connectedServer("files", remove);
        when(rig.serverInfoService().read()).thenReturn(List.of(server));
        rig.inherentRisk(server, remove, RiskLevel.L4);
        McpComposition.Member hitlMember = new McpComposition.Member("files", "remove", "files_remove", null, true,
                "");

        assertThatThrownBy(() -> rig.service().apply(
                new ExposureRequest(ExposureMode.BOTH, List.of(hitlMember), RiskLevel.L3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remove").hasMessageContaining("L4").hasMessageContaining("L3");
        verify(rig.toolSpecService(), never()).setExposureMode(any());
        verify(rig.compositionService(), never()).upsertExposed(any(), any());
    }

    @Test
    void exposureRequestSetsTheModeAndStoresMembersWithinTheCap() {
        Rig rig = Rig.create();
        McpSchema.Tool read = tool("read");
        McpServerInfo server = rig.connectedServer("files", read);
        McpServerInfo offline = new McpServerInfo(McpTransportType.SSE, "offline", "d", 0L, 0L, "{}");
        when(rig.serverInfoService().read()).thenReturn(List.of(server, offline));
        rig.inherentRisk(server, read, RiskLevel.L2);
        List<McpComposition.Member> members = List.of(
                new McpComposition.Member("files", "read", "files_read", null, false, ""),
                new McpComposition.Member("offline", "anything", "offline_anything", null, false, ""),
                new McpComposition.Member("files", "vanished", "files_vanished", null, false, ""));
        McpComposition exposed = new McpComposition(McpCompositionService.EXPOSED_ID, "Exposed Tools", "",
                members, true, RiskLevel.L3, 0L, 0L, 0L);
        when(rig.compositionService().upsertExposed(members, RiskLevel.L3)).thenReturn(exposed);
        when(rig.compositionService().getExposed()).thenReturn(Optional.of(exposed));
        when(rig.toolSpecService().exposureMode()).thenReturn(ExposureMode.COMPOSED_ONLY);
        when(rig.provider().getToolCallbacksFor(exposed)).thenReturn(new ToolCallback[0]);
        when(rig.toolSpecService().getExternalMcpToolNames()).thenReturn(Set.of());

        McpComposition result = rig.service().apply(
                new ExposureRequest(ExposureMode.COMPOSED_ONLY, members, RiskLevel.L3));

        assertThat(result).isSameAs(exposed);
        verify(rig.toolSpecService()).setExposureMode(ExposureMode.COMPOSED_ONLY);
        verify(rig.compositionService()).upsertExposed(members, RiskLevel.L3);
        verify(rig.hashLedger()).approveRereview("files", "read");
        verify(rig.riskEvaluator()).evaluateTool(server, read);
        verify(rig.riskEvaluator(), times(1)).evaluateTool(any(), any(McpSchema.Tool.class));
    }

    @Test
    void exposureRequestDefaultsANullCapToL5AndRejectsANullMode() {
        ExposureRequest request = new ExposureRequest(ExposureMode.BOTH, null, null);

        assertThat(request.members()).isEmpty();
        assertThat(request.maxRisk()).isEqualTo(RiskLevel.L5);
        assertThatThrownBy(() -> new ExposureRequest(null, List.of(), RiskLevel.L3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composableServersAreConnectedAndNotTheBuiltIn() {
        Rig rig = Rig.create();
        McpServerInfo builtin = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "builtin", "d", 0L, 0L, "{}");
        McpServerInfo connected = new McpServerInfo(McpTransportType.STDIO, "files", "d", 0L, 0L, "{}");
        McpServerInfo offline = new McpServerInfo(McpTransportType.SSE, "offline", "d", 0L, 0L, "{}");
        when(rig.serverInfoService().getDefaultMcpServerInfo()).thenReturn(builtin);
        when(rig.serverInfoService().read()).thenReturn(List.of(builtin, connected, offline));
        when(rig.clientService().isConnecting(builtin)).thenReturn(true);
        when(rig.clientService().isConnecting(connected)).thenReturn(true);

        assertThat(rig.service().composableServers()).containsExactly(connected);
    }
}
