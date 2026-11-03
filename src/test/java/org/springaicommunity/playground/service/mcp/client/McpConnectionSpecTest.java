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
package org.springaicommunity.playground.service.mcp.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConnectionSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    void streamableHttpRoundTripKeepsUrlEndpointHeadersAndOAuth() {
        String json = "{\"url\":\"https://mcp.example.com\",\"endpoint\":\"/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer ${TOKEN}\",\"X-Empty\":null},"
                + "\"requiredEnv\":[\"TOKEN\"],"
                + "\"oauth\":{\"issuer-uri\":\"https://issuer.example.com\",\"client-id\":\"cid\",\"scopes\":[\"read\"]}}";

        McpConnectionSpec spec = McpConnectionSpec.from(McpTransportType.STREAMABLE_HTTP, json);

        assertThat(spec.isHttp()).isTrue();
        assertThat(spec.url()).isEqualTo("https://mcp.example.com");
        assertThat(spec.endpoint()).isEqualTo("/mcp");
        assertThat(spec.headers()).containsEntry("Authorization", "Bearer ${TOKEN}").containsEntry("X-Empty", null);
        assertThat(spec.oauth().issuerUri()).isEqualTo("https://issuer.example.com");
        assertThat(spec.oauth().scopes()).containsExactly("read");

        JsonNode out = tree(spec.toJson());
        assertThat(out.get("url").asText()).isEqualTo("https://mcp.example.com");
        assertThat(out.get("endpoint").asText()).isEqualTo("/mcp");
        assertThat(out.has("sse-endpoint")).isFalse();
        assertThat(out.has("requiredEnv")).isFalse();
        assertThat(out.get("headers").get("Authorization").asText()).isEqualTo("Bearer ${TOKEN}");
        assertThat(out.get("headers").get("X-Empty").asText()).isEmpty();
        assertThat(out.get("oauth").get("client-id").asText()).isEqualTo("cid");
    }

    @Test
    void sseUsesTheSseEndpointKeyAndDropsOAuthWithoutIssuer() {
        McpConnectionSpec spec = McpConnectionSpec.from(McpTransportType.SSE,
                "{\"url\":\" https://sse.example.com \",\"sse-endpoint\":\"/sse\",\"endpoint\":\"/ignored\","
                        + "\"oauth\":{\"authorization-uri\":\"https://a\",\"token-uri\":\"https://t\",\"client-id\":\"c\"}}");

        assertThat(spec.endpoint()).isEqualTo("/sse");
        assertThat(spec.oauth().authorizationUri()).isEqualTo("https://a");
        JsonNode out = tree(spec.toJson());
        assertThat(out.get("url").asText()).isEqualTo("https://sse.example.com");
        assertThat(out.get("sse-endpoint").asText()).isEqualTo("/sse");
        assertThat(out.has("endpoint")).isFalse();
        assertThat(out.has("headers")).isFalse();
        assertThat(out.has("oauth")).isFalse();
    }

    @Test
    void stdioRoundTripTrimsArgsAndKeepsEnvValues() {
        McpConnectionSpec spec = McpConnectionSpec.from(McpTransportType.STDIO,
                "{\"command\":\"npx\",\"args\":[\"-y\",\"pkg\",3],\"env\":{\"A\":\"1\",\"B\":null}}");

        assertThat(spec.command()).isEqualTo("npx");
        assertThat(spec.args()).containsExactly("-y", "pkg", "3");
        assertThat(spec.env()).containsEntry("A", "1").containsEntry("B", "");

        JsonNode out = tree(McpConnectionSpec.stdio(" npx ", List.of(" -y ", "", "pkg"), Map.of("", "x", "K", "v"))
                .toJson());
        assertThat(out.get("command").asText()).isEqualTo("npx");
        assertThat(out.get("args")).hasSize(2);
        assertThat(out.get("args").get(0).asText()).isEqualTo("-y");
        assertThat(out.get("env").size()).isEqualTo(1);
        assertThat(out.get("env").get("K").asText()).isEqualTo("v");
        assertThat(tree(McpConnectionSpec.stdio("", List.of(), Map.of()).toJson()).isEmpty()).isTrue();
    }

    @Test
    void unreadableOrNonObjectJsonYieldsAnEmptySpec() {
        for (String broken : new String[] {null, "", "not json", "[1,2]"}) {
            McpConnectionSpec spec = McpConnectionSpec.from(McpTransportType.STREAMABLE_HTTP, broken);
            assertThat(spec.url()).isEmpty();
            assertThat(spec.endpoint()).isEmpty();
            assertThat(spec.headers()).isEmpty();
            assertThat(spec.oauth()).isNull();
            assertThat(spec.hasUrl()).isFalse();
        }
        assertThat(McpConnectionSpec.from(McpTransportType.STDIO, "{\"command\":null}").command()).isEmpty();
        assertThatThrownBy(() -> new McpConnectionSpec(null, "", "", null, null, "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headerHelpersMoveTheManagedAuthorizationHeaderInAndOut() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-First", "1");
        headers.put("Authorization", "Bearer secret");
        McpConnectionSpec spec = McpConnectionSpec.http(McpTransportType.STREAMABLE_HTTP, "https://u", "/mcp",
                headers, null);

        assertThat(spec.headerValue("Authorization")).isEqualTo("Bearer secret");
        McpConnectionSpec stripped = spec.withoutHeader("Authorization");
        assertThat(stripped.headers()).containsOnlyKeys("X-First");
        assertThat(stripped.headerValue("Authorization")).isNull();

        McpConnectionSpec restored = stripped.withLeadingHeader("Authorization", "Bearer secret");
        assertThat(restored.headers().keySet()).containsExactly("Authorization", "X-First");
        assertThat(tree(restored.toJson()).get("headers").get("Authorization").asText()).isEqualTo("Bearer secret");
    }
}
