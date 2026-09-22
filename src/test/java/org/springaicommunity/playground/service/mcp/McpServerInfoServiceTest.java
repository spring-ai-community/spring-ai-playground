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
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientPropertiesService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@SpringBootTest
class McpServerInfoServiceTest {

    @Autowired
    private McpServerInfoService mcpServerInfoService;

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

}
