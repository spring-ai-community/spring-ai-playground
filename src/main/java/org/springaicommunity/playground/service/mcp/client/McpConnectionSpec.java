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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record McpConnectionSpec(
        McpTransportType transport,
        String url,
        String endpoint,
        Map<String, String> headers,
        HttpConnectionParametersWithExtras.OAuth oauth,
        String command,
        List<String> args,
        Map<String, String> env) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL_KEY = "url";
    private static final String ENDPOINT_KEY = "endpoint";
    private static final String SSE_ENDPOINT_KEY = "sse-endpoint";
    private static final String HEADERS_KEY = "headers";
    private static final String REQUIRED_ENV_KEY = "requiredEnv";
    private static final String OAUTH_KEY = "oauth";
    private static final String COMMAND_KEY = "command";
    private static final String ARGS_KEY = "args";
    private static final String ENV_KEY = "env";

    public McpConnectionSpec {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    }

    public static McpConnectionSpec empty(McpTransportType transport) {
        return new McpConnectionSpec(transport, "", "", Map.of(), null, "", List.of(), Map.of());
    }

    public static McpConnectionSpec http(McpTransportType transport, String url, String endpoint,
            Map<String, String> headers, HttpConnectionParametersWithExtras.OAuth oauth) {
        return new McpConnectionSpec(transport, url, endpoint, headers, oauth, "", List.of(), Map.of());
    }

    public static McpConnectionSpec stdio(String command, List<String> args, Map<String, String> env) {
        return new McpConnectionSpec(McpTransportType.STDIO, "", "", Map.of(), null, command, args, env);
    }

    public static McpConnectionSpec from(McpTransportType transport, String json) {
        if (json == null || json.isBlank()) return empty(transport);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            return empty(transport);
        }
        if (!root.isObject()) return empty(transport);
        ObjectNode obj = (ObjectNode) root;
        if (transport == McpTransportType.STDIO) {
            return stdio(text(obj, COMMAND_KEY), readArgs(obj.get(ARGS_KEY)), readStringMap(obj.get(ENV_KEY), ""));
        }
        String endpointKey = transport == McpTransportType.SSE ? SSE_ENDPOINT_KEY : ENDPOINT_KEY;
        return http(transport, text(obj, URL_KEY), text(obj, endpointKey), readStringMap(obj.get(HEADERS_KEY), null),
                readOAuth(obj.get(OAUTH_KEY)));
    }

    public String toJson() {
        try {
            ObjectNode obj = MAPPER.createObjectNode();
            if (this.transport == McpTransportType.STDIO) {
                if (hasText(this.command)) obj.put(COMMAND_KEY, this.command.trim());
                ArrayNode argsNode = MAPPER.createArrayNode();
                this.args.stream().filter(McpConnectionSpec::hasText).map(String::trim).forEach(argsNode::add);
                if (!argsNode.isEmpty()) obj.set(ARGS_KEY, argsNode);
                ObjectNode envNode = MAPPER.createObjectNode();
                this.env.forEach((key, value) -> {
                    if (hasText(key)) envNode.put(key, value == null ? "" : value);
                });
                if (!envNode.isEmpty()) obj.set(ENV_KEY, envNode);
                return MAPPER.writeValueAsString(obj);
            }
            if (hasText(this.url)) obj.put(URL_KEY, this.url.trim());
            String endpointKey = this.transport == McpTransportType.SSE ? SSE_ENDPOINT_KEY : ENDPOINT_KEY;
            if (hasText(this.endpoint)) obj.put(endpointKey, this.endpoint.trim());
            obj.remove(REQUIRED_ENV_KEY);
            ObjectNode headersNode = MAPPER.createObjectNode();
            this.headers.forEach((key, value) -> {
                if (hasText(key)) headersNode.put(key, value == null ? "" : value);
            });
            if (!headersNode.isEmpty()) obj.set(HEADERS_KEY, headersNode);
            if (this.oauth != null && hasText(this.oauth.issuerUri())) obj.set(OAUTH_KEY, MAPPER.valueToTree(this.oauth));
            return MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    public boolean isHttp() {
        return this.transport == McpTransportType.SSE || this.transport == McpTransportType.STREAMABLE_HTTP;
    }

    public boolean hasUrl() {
        return hasText(this.url);
    }

    public String headerValue(String name) {
        return this.headers.get(name);
    }

    public McpConnectionSpec withoutHeader(String name) {
        Map<String, String> remaining = new LinkedHashMap<>(this.headers);
        remaining.remove(name);
        return new McpConnectionSpec(transport, url, endpoint, remaining, oauth, command, args, env);
    }

    public McpConnectionSpec withLeadingHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put(name, value);
        this.headers.forEach((key, existing) -> {
            if (!name.equals(key)) merged.put(key, existing);
        });
        return new McpConnectionSpec(transport, url, endpoint, merged, oauth, command, args, env);
    }

    private static String text(ObjectNode obj, String key) {
        JsonNode node = obj.get(key);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static List<String> readArgs(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode element : node) out.add(element.isTextual() ? element.asText() : element.toString());
        return out;
    }

    private static Map<String, String> readStringMap(JsonNode node, String nullValue) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            out.put(entry.getKey(), entry.getValue().isNull() ? nullValue : entry.getValue().asText(nullValue));
        }
        return out;
    }

    private static HttpConnectionParametersWithExtras.OAuth readOAuth(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return null;
        try {
            return MAPPER.treeToValue(node, HttpConnectionParametersWithExtras.OAuth.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
