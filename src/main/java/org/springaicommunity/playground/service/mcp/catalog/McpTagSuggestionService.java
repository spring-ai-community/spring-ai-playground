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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class McpTagSuggestionService {

    private static final Logger logger = LoggerFactory.getLogger(McpTagSuggestionService.class);
    private static final String DEFAULT_RESOURCE = "mcp/default-mcp-tags.json";

    private final List<String> regions;
    private final List<String> attributes;

    public McpTagSuggestionService(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
        List<String> r = List.of();
        List<String> a = List.of();
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                r = readStringArray(root, "regions");
                a = readStringArray(root, "attributes");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load built-in MCP tags from " + DEFAULT_RESOURCE, e);
            }
        } else {
            logger.warn("Built-in MCP tags resource missing at {}", DEFAULT_RESOURCE);
        }
        this.regions = r;
        this.attributes = a;
        logger.info("Loaded MCP tag suggestions: regions={}, attributes={}", r, a);
    }

    private static List<String> readStringArray(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(n -> {
            if (n.isTextual()) out.add(n.asText());
        });
        return List.copyOf(out);
    }

    public List<String> getSuggestedRegions() { return this.regions; }
    public List<String> getSuggestedAttributes() { return this.attributes; }

    public List<String> getSuggestedTags() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(this.regions);
        all.addAll(this.attributes);
        return List.copyOf(all);
    }

    public List<String> getAllTags(Collection<String> observed) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(this.regions);
        all.addAll(this.attributes);
        if (observed != null) {
            for (String t : observed) {
                if (t != null && !t.isBlank()) all.add(t);
            }
        }
        return List.copyOf(all);
    }
}
