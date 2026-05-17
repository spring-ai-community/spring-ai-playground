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
package org.springaicommunity.playground.service.mcp.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.HttpConnectionParametersWithExtras;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class McpCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(McpCatalogService.class);
    private static final String DEFAULT_RESOURCE = "mcp/default-mcp-specs.json";

    private final ObjectMapper objectMapper;
    private final Map<String, McpCatalogEntry> entriesById;
    private final List<McpCatalogEntry> entriesOrdered;

    public McpCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
        Map<String, McpCatalogEntry> loaded = new LinkedHashMap<>();
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                List<McpCatalogEntry> raw = objectMapper.readValue(in, new TypeReference<List<McpCatalogEntry>>() {});
                for (McpCatalogEntry e : raw) {
                    if (e.id() == null || e.id().isBlank()) continue;
                    loaded.put(e.id(), e);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load MCP catalog from " + DEFAULT_RESOURCE, e);
            }
        } else {
            logger.warn("MCP catalog resource missing at {}", DEFAULT_RESOURCE);
        }
        this.entriesById = Collections.unmodifiableMap(loaded);
        this.entriesOrdered = List.copyOf(loaded.values());
        logger.info("Loaded {} MCP catalog entries from {}", this.entriesOrdered.size(), DEFAULT_RESOURCE);
    }

    public List<McpCatalogEntry> getCatalog() {
        return this.entriesOrdered;
    }

    public Optional<McpCatalogEntry> findById(String id) {
        return Optional.ofNullable(this.entriesById.get(id));
    }

    public List<McpCatalogEntry> getByTier(int tier) {
        return this.entriesOrdered.stream().filter(e -> e.tier() == tier).toList();
    }

    public McpServerInfo instantiate(McpCatalogEntry entry, McpCatalogEntry.TransportSpec transport,
            Map<String, String> userValues) {
        if (entry == null) throw new IllegalArgumentException("entry must not be null");
        if (transport == null) {
            if (entry.transports().isEmpty()) {
                throw new IllegalArgumentException("Catalog entry has no transports: " + entry.id());
            }
            transport = entry.transports().get(0);
        }
        Map<String, String> values = userValues == null ? Map.of() : userValues;
        String url = substitutePlaceholders(transport.urlTemplate(), values);
        long now = System.currentTimeMillis();

        String connectionJson;
        try {
            connectionJson = buildConnectionJson(transport, url, values);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build connection JSON for catalog entry " + entry.id(), e);
        }

        return new McpServerInfo(transport.type(), entry.id(), entry.description(),
                now, now, connectionJson, entry.category(), entry.tags());
    }

    private String buildConnectionJson(McpCatalogEntry.TransportSpec transport, String url,
            Map<String, String> values) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (transport.requiredHeaders() != null) {
            for (McpCatalogEntry.HeaderSpec h : transport.requiredHeaders()) {
                if (h.key() != null && !h.key().isBlank()) {
                    headers.put(h.key(), h.value() == null ? "" : h.value());
                }
            }
        }
        HttpConnectionParametersWithExtras.OAuth oauth = null;
        if (transport.oauthDefaults() != null
                && transport.oauthDefaults().issuerUri() != null
                && !transport.oauthDefaults().issuerUri().isBlank()) {
            String issuer = substitutePlaceholders(transport.oauthDefaults().issuerUri(), values);
            oauth = new HttpConnectionParametersWithExtras.OAuth(
                    issuer, null, null,
                    null, null, transport.oauthDefaults().scopes(), null);
        }
        Object record = switch (transport.type()) {
            case STREAMABLE_HTTP -> new HttpConnectionParametersWithExtras.StreamableHttp(
                    url, transport.endpoint(), headers, transport.requiredEnv(), oauth);
            case SSE -> new HttpConnectionParametersWithExtras.Sse(
                    url, transport.endpoint(), headers, transport.requiredEnv(), oauth);
            case STDIO -> {
                ObjectNode obj = objectMapper.createObjectNode();
                obj.put("command", url == null ? "" : url);
                yield obj;
            }
        };
        return objectMapper.writeValueAsString(record);
    }

    public static String substitutePlaceholders(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || values == null || values.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null) continue;
            String token = "{" + e.getKey() + "}";
            String value = e.getValue() == null ? "" : e.getValue();
            int idx = 0;
            StringBuilder sb = new StringBuilder();
            while (true) {
                int next = result.indexOf(token, idx);
                if (next < 0) {
                    sb.append(result, idx, result.length());
                    break;
                }
                sb.append(result, idx, next).append(value);
                idx = next + token.length();
            }
            result = sb.toString();
        }
        return result;
    }
}
