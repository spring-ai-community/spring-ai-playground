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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "vaadin.productionMode=true",
                "spring.ai.playground.mcp-server.auth-token=test-token-123" })
class McpServerAuthTokenFilterTest {

    @LocalServerPort
    private int port;

    @Autowired
    private McpClientService mcpClientService;

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Autowired
    private McpServerAuthTokenService authTokenService;

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path));
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mcpEndpointRejectsMissingAndWrongToken() throws Exception {
        assertThat(get("/mcp", null).statusCode()).isEqualTo(401);
        assertThat(get("/mcp", "Bearer wrong-token").statusCode()).isEqualTo(401);
        assertThat(get("/mcp", "Basic dXNlcjpwdw==").statusCode()).isEqualTo(401);
        assertThat(get("/mcp/message", null).statusCode()).isEqualTo(401);
        assertThat(get("/sse", null).statusCode()).isEqualTo(401);
    }

    @Test
    void mcpEndpointAcceptsCorrectToken() throws Exception {
        assertThat(get("/mcp", "Bearer test-token-123").statusCode()).isNotEqualTo(401);
    }

    @Test
    void authSchemeMatchIsCaseInsensitiveButTheTokenIsNot() throws Exception {
        assertThat(get("/mcp", "bearer test-token-123").statusCode()).isNotEqualTo(401);
        assertThat(get("/mcp", "BEARER test-token-123").statusCode()).isNotEqualTo(401);
        assertThat(get("/mcp", "Bearer TEST-TOKEN-123").statusCode()).isEqualTo(401);
    }

    @Test
    void nonMcpPathsStayOpen() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isNotEqualTo(401);
    }

    @Test
    void guardHoldsUnderAContextPath() throws Exception {
        McpServerAuthTokenFilter filter = new McpServerAuthTokenFilter(this.authTokenService);

        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request("/app", "/app/mcp", null), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);
        MockHttpServletResponse deniedSse = new MockHttpServletResponse();
        filter.doFilter(request("/app", "/app/sse", null), deniedSse, new MockFilterChain());
        assertThat(deniedSse.getStatus()).isEqualTo(401);

        MockFilterChain allowed = new MockFilterChain();
        filter.doFilter(request("/app", "/app/mcp", "Bearer test-token-123"), new MockHttpServletResponse(), allowed);
        assertThat(allowed.getRequest()).isNotNull();
        MockFilterChain open = new MockFilterChain();
        filter.doFilter(request("/app", "/app/actuator/health", null), new MockHttpServletResponse(), open);
        assertThat(open.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String contextPath, String uri, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contextPath);
        if (authorization != null) request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        return request;
    }

    @Test
    void appOwnLoopbackClientCarriesTheToken() {
        McpClientService.TestConnectionResult result =
                this.mcpClientService.testConnection(this.mcpServerInfoService.getDefaultMcpServerInfo());
        assertThat(result.ok()).isTrue();
    }

}
