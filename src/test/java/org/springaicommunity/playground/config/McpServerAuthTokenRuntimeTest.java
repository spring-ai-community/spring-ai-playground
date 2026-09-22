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
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

import java.net.URI;
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
