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
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.Management;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagementToolRegistrarTest {

    @TempDir
    Path homeDir;

    private final ToolSpecService toolSpecService = mock(ToolSpecService.class);
    private final McpServerAuthTokenService authTokenService = mock(McpServerAuthTokenService.class);
    private final ObjectProvider<McpSyncServer> syncServerProvider = mock(ObjectProvider.class);
    private final ObjectProvider<ManagementToolContribution> contributions = mock(ObjectProvider.class);
    private final MockEnvironment environment = new MockEnvironment();

    @BeforeEach
    void setUp() {
        when(syncServerProvider.getIfAvailable()).thenReturn(mock(McpSyncServer.class));
        when(authTokenService.accessMode()).thenReturn(AccessMode.BEARER_TOKEN);
        when(contributions.orderedStream()).thenAnswer(invocation -> Stream.of(
                contribution("mcp", "saip_mcp_b", "saip_mcp_a"),
                contribution("mcp-register", "saip_mcp_add"),
                contribution("expose", "saip_expose_get"),
                contribution("monitor", "saip_monitor_x")));
    }

    private static ManagementToolContribution contribution(String category, String... names) {
        return new ManagementToolContribution() {
            @Override
            public String category() {
                return category;
            }

            @Override
            public List<ManagementTool> tools() {
                return Stream.of(names).map(name -> ManagementTool.named(name, category,
                        ManagementTestSupport.EchoInput.class).description(name)
                        .handle((input, caller) -> ManagementResult.ok("ok", null))).toList();
            }
        };
    }

    private ManagementToolRegistrar registrar(Management management) {
        SpringAiPlaygroundOptions options = new SpringAiPlaygroundOptions(null, false, null, null, null, null, null,
                null, management);
        ManagementApprovalGate gate = new ManagementApprovalGate(options, mock(ObjectProvider.class),
                ManagementTestSupport.auditLog(options, new McpRiskEventRingBuffer()));
        return new ManagementToolRegistrar(options, syncServerProvider, toolSpecService, authTokenService,
                contributions, gate, homeDir, environment);
    }

    @SuppressWarnings("unchecked")
    private List<String> registeredNames() {
        ArgumentCaptor<List<SyncToolSpecification>> captor = ArgumentCaptor.forClass(List.class);
        verify(toolSpecService).addServedTools(captor.capture());
        return captor.getValue().stream().map(specification -> specification.tool().name()).toList();
    }

    @Test
    void registersTheDefaultCategoriesSortedAndLeavesRegistrationToolsOut() {
        ManagementToolRegistrar registrar = registrar(new Management(null, null, null, null, null));

        registrar.refresh();

        assertThat(registeredNames()).containsExactly("saip_expose_get", "saip_mcp_a", "saip_mcp_b",
                "saip_monitor_x");
        assertThat(registrar.status().active()).isTrue();
        assertThat(registrar.status().toolCount()).isEqualTo(4);
        assertThat(registrar.status().categories()).containsExactlyInAnyOrder("mcp", "expose", "monitor");
    }

    @Test
    void registrationToolsCanBeSwitchedOnExplicitly() {
        ManagementToolRegistrar registrar = registrar(new Management(null, Set.of("mcp-register", "expose"), null,
                null, null));

        registrar.refresh();

        assertThat(registeredNames()).containsExactly("saip_expose_get", "saip_mcp_add");
    }

    @Test
    void individualToolsCanBeExcluded() {
        ManagementToolRegistrar registrar = registrar(new Management(null, null, Set.of("saip_mcp_b"), null, null));

        registrar.refresh();

        assertThat(registeredNames()).doesNotContain("saip_mcp_b").contains("saip_mcp_a");
    }

    @Test
    void nothingIsRegisteredWithoutABearerToken() {
        when(authTokenService.accessMode()).thenReturn(AccessMode.NONE);
        ManagementToolRegistrar registrar = registrar(new Management(null, null, null, null, null));

        registrar.refresh();

        verify(toolSpecService, never()).addServedTools(anyList());
        assertThat(registrar.status().active()).isFalse();
        assertThat(registrar.status().reason()).contains("no bearer token");
    }

    @Test
    void nothingIsRegisteredWhenDisabledInStdioModeOrInABundle() throws IOException {
        ManagementToolRegistrar disabled = registrar(new Management(false, null, null, null, null));
        disabled.refresh();
        assertThat(disabled.status().reason()).contains("disabled");

        environment.setProperty("spring.ai.mcp.server.stdio", "true");
        ManagementToolRegistrar stdio = registrar(new Management(null, null, null, null, null));
        stdio.refresh();
        assertThat(stdio.status().reason()).contains("stdio");

        environment.setProperty("spring.ai.mcp.server.stdio", "false");
        Files.writeString(homeDir.resolve(BundleManifest.FILE_NAME), "{}");
        ManagementToolRegistrar bundle = registrar(new Management(null, null, null, null, null));
        bundle.refresh();
        assertThat(bundle.status().reason()).contains("bundle");

        verify(toolSpecService, never()).addServedTools(anyList());
    }

    @Test
    void togglingTheTokenRegistersAndUnregistersTheTools() {
        ManagementToolRegistrar registrar = registrar(new Management(null, null, null, ApprovalMode.ALL, null));
        registrar.refresh();
        registrar.refresh();
        verify(toolSpecService).addServedTools(anyList());

        when(authTokenService.accessMode()).thenReturn(AccessMode.NONE);
        registrar.onAuthTokenChanged(new McpServerAuthTokenService.AuthTokenChangedEvent(AccessMode.NONE));

        ArgumentCaptor<Set<String>> removed = ArgumentCaptor.forClass(Set.class);
        verify(toolSpecService).removeServedTools(removed.capture());
        assertThat(removed.getValue()).containsExactlyInAnyOrder("saip_expose_get", "saip_mcp_a", "saip_mcp_b",
                "saip_monitor_x");
        assertThat(registrar.status().active()).isFalse();

        when(authTokenService.accessMode()).thenReturn(AccessMode.BEARER_TOKEN);
        registrar.onAuthTokenChanged(new McpServerAuthTokenService.AuthTokenChangedEvent(AccessMode.BEARER_TOKEN));

        assertThat(registrar.status().active()).isTrue();
        assertThat(registrar.status().approval()).isEqualTo(ApprovalMode.ALL);
        verify(toolSpecService, times(2)).addServedTools(anyList());
    }

}
