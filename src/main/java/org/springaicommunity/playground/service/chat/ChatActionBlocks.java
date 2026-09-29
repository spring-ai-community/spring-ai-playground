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
package org.springaicommunity.playground.service.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class ChatActionBlocks {

    public static final String ACTION_BLOCK_FENCE = "```saip-action";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatActionBlocks() {
    }

    public static List<String> extract(Object responseData) {
        String text = responseData == null ? "" : String.valueOf(responseData);
        if (!text.contains(ACTION_BLOCK_FENCE)) return List.of();
        text = unwrapContent(text);
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = text.indexOf(ACTION_BLOCK_FENCE, from);
            if (start < 0) break;
            int end = text.indexOf("\n```", start + ACTION_BLOCK_FENCE.length());
            if (end < 0) break;
            blocks.add(text.substring(start, end + 4));
            from = end + 4;
        }
        return blocks;
    }

    public static List<String> missingFrom(String existing, Collection<String> blocks) {
        if (Objects.isNull(blocks) || blocks.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String block : blocks)
            if (Objects.isNull(existing) || !existing.contains(block)) missing.add(block);
        return missing;
    }

    private static String unwrapContent(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{") && !trimmed.startsWith("\"")) return text;
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node.isTextual()) return node.asText();
            if (node.isObject() && node.has("text")) return node.get("text").asText();
            if (node.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode el : node) {
                    sb.append(el.isObject() && el.has("text") ? el.get("text").asText() : el.asText());
                }
                if (!sb.isEmpty()) return sb.toString();
            }
        } catch (RuntimeException ignore) {
        }
        return text;
    }
}
