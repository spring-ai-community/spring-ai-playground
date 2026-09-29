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
package org.springaicommunity.playground.service.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.McpServerInfoService.ExternalServerSummary;
import org.springaicommunity.playground.service.mcp.McpServerInfoService.McpServerRegistration;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientPropertiesService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.oauth.McpClientRegistrationRepository;
import org.springaicommunity.playground.service.oauth.OAuthTokenEncryptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class McpServerInfoServiceTest {

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private McpClientService mcpClientService;

    @Test
    public void testMcpServerInfoService() {
        long seedTimestamp = System.currentTimeMillis() - 1_000L;
        McpServerInfo stdioSeed = new McpServerInfo(McpTransportType.STDIO, "stdio-sample-server",
                "Seeded stdio server.", seedTimestamp, seedTimestamp, null);
        mcpServerInfoService.updateMcpServerInfo(stdioSeed.mcpTransportType(), stdioSeed.serverName(), stdioSeed);

        Map<McpTransportType, List<McpServerInfo>> serverInfos = mcpServerInfoService.getMcpServerInfos();
        assertTrue(serverInfos.containsKey(McpTransportType.STDIO));
        assertTrue(serverInfos.containsKey(McpTransportType.SSE));
        assertTrue(serverInfos.containsKey(McpTransportType.STREAMABLE_HTTP));
        assertEquals(1, serverInfos.get(McpTransportType.STDIO).size());
        assertEquals(0, serverInfos.get(McpTransportType.SSE).size());
        assertEquals(0, serverInfos.get(McpTransportType.STREAMABLE_HTTP).size());

        String newServer = "New MCP Server";
        McpServerInfo mcpServerInfo = mcpServerInfoService.createBlankMcpServerInfo();
        assertNotNull(mcpServerInfo);
        assertEquals(McpTransportType.STREAMABLE_HTTP, mcpServerInfo.mcpTransportType());
        assertEquals(newServer, mcpServerInfo.serverName());
        assertEquals("Please edit the description of the MCP Server.", mcpServerInfo.description());
        assertTrue(mcpServerInfo.createTimestamp() == mcpServerInfo.updateTimestamp());
        assertEquals(null, mcpServerInfo.connectionAsJson());

        serverInfos = mcpServerInfoService.getMcpServerInfos();
        assertTrue(serverInfos.containsKey(McpTransportType.STDIO));
        assertTrue(serverInfos.containsKey(McpTransportType.SSE));
        assertTrue(serverInfos.containsKey(McpTransportType.STREAMABLE_HTTP));
        assertEquals(1, serverInfos.get(McpTransportType.STDIO).size());
        assertEquals(0, serverInfos.get(McpTransportType.SSE).size());
        assertEquals(0, serverInfos.get(McpTransportType.STREAMABLE_HTTP).size());

        mcpServerInfo = serverInfos.get(McpTransportType.STDIO).get(0);

        McpTransportType type = McpTransportType.STREAMABLE_HTTP;
        String serverName = "server1";
        String newConnectionAsJson = "{\"url\":\"newhost\",\"endpoint\":\"\"}";
        long now = System.currentTimeMillis();
        String description = "description";
        McpServerInfo updateMcpServerInfo =
                mcpServerInfo.mutate(type, serverName, description, now, newConnectionAsJson);
        McpServerInfo updatedMcpServerInfo =
                mcpServerInfoService.updateMcpServerInfo(mcpServerInfo.mcpTransportType(), mcpServerInfo.serverName(),
                        updateMcpServerInfo);
        assertNotNull(updatedMcpServerInfo);
        assertEquals(McpTransportType.STREAMABLE_HTTP, updateMcpServerInfo.mcpTransportType());
        assertEquals(serverName, updateMcpServerInfo.serverName());
        assertEquals(description, updateMcpServerInfo.description());
        assertFalse(updateMcpServerInfo.createTimestamp() == updateMcpServerInfo.updateTimestamp());
        assertEquals(newConnectionAsJson, updateMcpServerInfo.connectionAsJson());

        serverInfos = mcpServerInfoService.getMcpServerInfos();
        assertEquals(serverName, serverInfos.get(McpTransportType.STREAMABLE_HTTP).get(0).serverName());
        assertTrue(serverInfos.containsKey(McpTransportType.STDIO));
        assertTrue(serverInfos.containsKey(McpTransportType.SSE));
        assertTrue(serverInfos.containsKey(McpTransportType.STREAMABLE_HTTP));
        assertEquals(1, serverInfos.get(McpTransportType.STDIO).size());
        assertEquals(0, serverInfos.get(McpTransportType.SSE).size());
        assertEquals(1, serverInfos.get(McpTransportType.STREAMABLE_HTTP).size());

        McpServerInfo newMcpServerInfo = new McpServerInfo(McpTransportType.SSE, "new", "new description", now, now,
                "{}");
        mcpServerInfoService.updateMcpServerInfo(newMcpServerInfo.mcpTransportType(), newMcpServerInfo.serverName(),
                newMcpServerInfo);
        serverInfos = mcpServerInfoService.getMcpServerInfos();
        assertEquals(newMcpServerInfo.serverName(), serverInfos.get(McpTransportType.SSE).get(0).serverName());
        assertTrue(serverInfos.containsKey(McpTransportType.STDIO));
        assertTrue(serverInfos.containsKey(McpTransportType.SSE));
        assertTrue(serverInfos.containsKey(McpTransportType.STREAMABLE_HTTP));
        assertEquals(1, serverInfos.get(McpTransportType.STDIO).size());
        assertEquals(1, serverInfos.get(McpTransportType.SSE).size());
        assertEquals(1, serverInfos.get(McpTransportType.STREAMABLE_HTTP).size());

        mcpServerInfoService.deleteMcpServerInfo(newMcpServerInfo.mcpTransportType(),
                newMcpServerInfo.serverName());

        serverInfos = mcpServerInfoService.getMcpServerInfos();
        assertTrue(serverInfos.containsKey(McpTransportType.STDIO));
        assertTrue(serverInfos.containsKey(McpTransportType.SSE));
        assertTrue(serverInfos.containsKey(McpTransportType.STREAMABLE_HTTP));
        assertEquals(1, serverInfos.get(McpTransportType.STDIO).size());
        assertEquals(0, serverInfos.get(McpTransportType.SSE).size());
        assertEquals(1, serverInfos.get(McpTransportType.STREAMABLE_HTTP).size());

    }

    @Test
    void updateRejectsServerNamesThatCouldEscapeTheSaveDirectory() {
        long now = System.currentTimeMillis();
        for (String unsafeName : List.of("has space", "../escape", "slash/name", "")) {
            McpServerInfo unsafe = new McpServerInfo(McpTransportType.SSE, unsafeName, "d", now, now, "{}");
            assertThatThrownBy(() -> mcpServerInfoService.updateMcpServerInfo(McpTransportType.SSE, unsafeName, unsafe))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(McpServerInfoService.serverNameError("has space")).contains("Server name cannot contain spaces");
        assertThat(McpServerInfoService.serverNameError("../escape"))
                .contains("Server name may only contain letters, digits, '.', '_', '-'");
        assertThat(McpServerInfoService.serverNameError("")).contains("Server name cannot be empty");
        assertThat(McpServerInfoService.serverNameError(null)).contains("Server name cannot be empty");
        assertThat(McpServerInfoService.serverNameError("ok.name_1-a")).isEmpty();
        assertThat(mcpServerInfoService.getMcpServerInfos().get(McpTransportType.SSE))
                .extracting(McpServerInfo::serverName).doesNotContain("has space", "../escape", "slash/name", "");
    }

    @Test
    void deleteGuardsTheBuiltInServerCatalogGhostsAndUnknownNames() {
        String builtin = mcpServerInfoService.getDefaultMcpServerInfo().serverName();

        assertThat(mcpServerInfoService.deleteBlocker(builtin)).contains("Built-in MCP server cannot be deleted.");
        assertThat(mcpServerInfoService.deleteBlocker("GitHub"))
                .contains("Inactive (catalog) MCP servers cannot be deleted.");
        assertThat(mcpServerInfoService.deleteBlocker("no-such-server"))
                .contains("MCP server not found: no-such-server");
        assertThatThrownBy(() -> mcpServerInfoService.delete(builtin))
                .isInstanceOf(IllegalStateException.class).hasMessage("Built-in MCP server cannot be deleted.");
        assertThatThrownBy(() -> mcpServerInfoService.delete("GitHub")).isInstanceOf(IllegalStateException.class);
        assertThat(mcpServerInfoService.getDefaultMcpServerInfo().serverName()).isEqualTo(builtin);
    }

    @Test
    void deleteRemovesARegisteredServer() {
        long now = System.currentTimeMillis();
        McpServerInfo info = new McpServerInfo(McpTransportType.SSE, "delete-me", "d", now, now,
                "{\"url\":\"https://delete-me.example.com\"}");
        mcpServerInfoService.updateMcpServerInfo(McpTransportType.SSE, "delete-me", info);
        assertThat(mcpServerInfoService.deleteBlocker("delete-me")).isEmpty();

        McpServerInfo deleted = mcpServerInfoService.delete("delete-me");

        assertThat(deleted.serverName()).isEqualTo("delete-me");
        assertThat(mcpServerInfoService.findByServerName("delete-me")).isEmpty();
        assertThat(mcpServerInfoService.deleteBlocker("delete-me")).contains("MCP server not found: delete-me");
    }

    @Test
    void uninstalledCatalogServersSkipRegisteredNamesAndCatalogStatusReflectsMissingEnv() {
        assertThat(mcpServerInfoService.uninstalledCatalogServers()).extracting(McpServerInfo::serverName)
                .contains("GitHub", "Notion");
        long now = System.currentTimeMillis();
        McpServerInfo installed = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "GitHub", "d", now, now,
                "{\"url\":\"https://api.githubcopilot.com/mcp/\"}");
        mcpServerInfoService.updateMcpServerInfo(installed.mcpTransportType(), installed.serverName(), installed);
        try {
            assertThat(mcpServerInfoService.uninstalledCatalogServers()).extracting(McpServerInfo::serverName)
                    .doesNotContain("GitHub").contains("Notion");
        } finally {
            mcpServerInfoService.deleteMcpServerInfo(installed.mcpTransportType(), installed.serverName());
        }

        McpServerInfo needsEnv = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "ghost-env", "d", now, now,
                "{\"url\":\"https://x.example.com/${QA_MISSING_ENV_20260928}\"}");
        assertThat(mcpClientService.catalogStatus(needsEnv).status()).isEqualTo(ServerStatus.MISSING_CONFIG);
        McpServerInfo plain = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "ghost-plain", "d", now, now,
                "{\"url\":\"https://x.example.com\"}");
        assertThat(mcpClientService.catalogStatus(plain).status()).isEqualTo(ServerStatus.OFFLINE);
    }

    @Test
    void saveAndConnectRejectsDuplicatesUrlAndOAuthBeforeConnecting() throws IOException {
        McpClientService clientService = mock(McpClientService.class);
        McpServerInfoService service = newServiceForSave(clientService, mock(McpServerInfoPersistenceService.class));
        McpServerInfo taken = new McpServerInfo(McpTransportType.STDIO, "taken", "d", 1L, 1L, "{\"command\":\"x\"}");
        service.loadAll(() -> service.updateMcpServerInfo(taken.mcpTransportType(), taken.serverName(), taken));

        assertThatThrownBy(() -> service.saveAndConnect(new McpServerRegistration(null, "New MCP Server",
                McpTransportType.STDIO, "taken", "d", null, null, "{\"command\":\"y\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to save : MCP connection already exists with name taken");
        assertThatThrownBy(() -> service.saveAndConnect(new McpServerRegistration(null, null,
                McpTransportType.STREAMABLE_HTTP, "no-url", "d", null, null, "{\"endpoint\":\"/mcp\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("URL is required for HTTP/SSE transport");
        assertThatThrownBy(() -> service.saveAndConnect(new McpServerRegistration(null, null,
                McpTransportType.STREAMABLE_HTTP, "bad-oauth", "d", null, null,
                "{\"url\":\"https://u\",\"oauth\":{\"issuer-uri\":\"http://plain\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Client ID is required for OAuth.")
                .hasMessageContaining("Issuer URI must use HTTPS.");
        assertThatThrownBy(() -> service.saveAndConnect(new McpServerRegistration(null, null,
                McpTransportType.STDIO, "bad name", "d", null, null, "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Server name cannot contain spaces");
        verify(clientService, never()).startMcpClient(any());
    }

    @Test
    void saveAndConnectStartsTheClientThenPersistsAndKeepsCreateTimestampOnEdit() throws IOException {
        McpClientService clientService = mock(McpClientService.class);
        McpServerInfoPersistenceService persistence = mock(McpServerInfoPersistenceService.class);
        McpServerInfoService service = newServiceForSave(clientService, persistence);

        McpServerInfo saved = service.saveAndConnect(new McpServerRegistration(McpTransportType.STREAMABLE_HTTP,
                "New MCP Server", McpTransportType.STDIO, "fresh", "desc", " ", Set.of(" tag ", "", "x"),
                "{\"command\":\"npx\"}"));

        assertThat(saved.serverName()).isEqualTo("fresh");
        assertThat(saved.category()).isEqualTo(McpServerInfo.DEFAULT_CATEGORY);
        assertThat(saved.tags()).containsExactlyInAnyOrder("tag", "x");
        assertThat(saved.createTimestamp()).isEqualTo(saved.updateTimestamp());
        verify(clientService).startMcpClient(saved);
        verify(persistence).saveAsync(saved);
        assertThat(service.findByServerName("fresh")).contains(saved);

        McpServerInfo edited = service.saveAndConnect(new McpServerRegistration(McpTransportType.STDIO, "fresh",
                McpTransportType.STDIO, "fresh", "new desc", "CUSTOM", Set.of(), "{\"command\":\"uvx\"}"));
        assertThat(edited.createTimestamp()).isEqualTo(saved.createTimestamp());
        assertThat(edited.description()).isEqualTo("new desc");
        assertThat(service.findByServerName("fresh")).contains(edited);

        McpServerInfo renamed = service.saveAndConnect(new McpServerRegistration(McpTransportType.STDIO, "fresh",
                McpTransportType.STDIO, "renamed", "new desc", "CUSTOM", Set.of(), "{\"command\":\"uvx\"}"));
        assertThat(renamed.createTimestamp()).isEqualTo(saved.createTimestamp());
        assertThat(service.findByServerName("fresh")).isPresent();
        assertThat(service.findByServerName("renamed")).contains(renamed);
    }

    @Test
    void saveAndConnectWrapsConnectionFailuresAndSavesNothing() throws IOException {
        McpClientService clientService = mock(McpClientService.class);
        McpServerInfoPersistenceService persistence = mock(McpServerInfoPersistenceService.class);
        McpServerInfoService service = newServiceForSave(clientService, persistence);
        doThrow(new RuntimeException("connect refused")).when(clientService).startMcpClient(any());

        assertThatThrownBy(() -> service.saveAndConnect(new McpServerRegistration(null, null,
                McpTransportType.STDIO, "broken", "d", null, null, "{\"command\":\"nope\"}")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to connect : connect refused");
        assertThat(service.findByServerName("broken")).isEmpty();
        verify(persistence, never()).saveAsync(any());
    }

    private static McpServerInfoService newServiceForSave(McpClientService clientService,
            McpServerInfoPersistenceService persistence) throws IOException {
        McpClientPropertiesService<?> stdio = mock(McpClientPropertiesService.class);
        when(stdio.getTransportType()).thenReturn(McpTransportType.STDIO);
        doReturn(Map.of()).when(stdio).getDefaultConnections();
        McpClientPropertiesService<?> streamable = mock(McpClientPropertiesService.class);
        when(streamable.getTransportType()).thenReturn(McpTransportType.STREAMABLE_HTTP);
        doReturn(Map.of()).when(streamable).getDefaultConnections();
        Path homeDir = Files.createTempDirectory("saip-mcp-save-test");
        SpringAiPlaygroundOptions playgroundOptions = new SpringAiPlaygroundOptions(null, false, null, null, null,
                new SpringAiPlaygroundOptions.McpServer(null, null, null));
        ObjectProvider<McpServerInfoPersistenceService> provider = mock();
        when(provider.getObject()).thenReturn(persistence);
        return new McpServerInfoService(new ObjectMapper(),
                new McpClientPropertiesService<?>[] {stdio, streamable}, clientService,
                mock(McpCatalogService.class), provider, mock(McpClientRegistrationRepository.class), 8282,
                new McpServerProperties(), playgroundOptions, new McpServerAuthTokenService(playgroundOptions,
                        homeDir, new OAuthTokenEncryptor(homeDir), event -> { }));
    }

    @Test
    void warnsAtStartupWhenTheBuiltInServerHasNoAuthToken() throws IOException {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(McpServerInfoService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            newService(null, false);
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("auth-token")));
            appender.list.clear();
            newService("secret", false);
            newService(null, true);
            assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN));
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void newService(String authToken, boolean stdio) throws IOException {
        Path homeDir = Files.createTempDirectory("saip-mcp-info-test");
        SpringAiPlaygroundOptions playgroundOptions = new SpringAiPlaygroundOptions(null, false, null, null, null,
                new SpringAiPlaygroundOptions.McpServer(null, null, authToken));
        McpServerProperties serverProperties = new McpServerProperties();
        serverProperties.setStdio(stdio);
        ObjectProvider<McpServerInfoPersistenceService> persistence = mock();
        new McpServerInfoService(new ObjectMapper(), new McpClientPropertiesService<?>[0],
                mock(McpClientService.class), mock(McpCatalogService.class), persistence,
                mock(McpClientRegistrationRepository.class), 8282, serverProperties,
                playgroundOptions, new McpServerAuthTokenService(playgroundOptions, homeDir,
                        new OAuthTokenEncryptor(homeDir), event -> { }));
    }

    @Test
    void externalServerSummaryCountsAwaitingAuthorizationSeparately() throws IOException {
        McpClientService clientService = mock(McpClientService.class);
        McpServerInfoService service = newServiceForSave(clientService, mock(McpServerInfoPersistenceService.class));
        McpServerInfo ready = new McpServerInfo(McpTransportType.STDIO, "ready", "d", 1L, 1L, "{\"command\":\"x\"}");
        McpServerInfo waiting = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "waiting", "d", 1L, 1L,
                "{\"url\":\"https://u\"}");
        service.loadAll(() -> {
            service.updateMcpServerInfo(ready.mcpTransportType(), ready.serverName(), ready);
            service.updateMcpServerInfo(waiting.mcpTransportType(), waiting.serverName(), waiting);
        });
        when(clientService.getStatus(ready)).thenReturn(McpClientService.StatusEntry.ok());
        when(clientService.getStatus(waiting))
                .thenReturn(McpClientService.StatusEntry.awaitingAuthorization("https://auth"));

        ExternalServerSummary summary = service.externalServerSummary();

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.awaitingAuthorization()).isEqualTo(1);
        assertThat(summary.connected()).isEqualTo(1);
    }
}
