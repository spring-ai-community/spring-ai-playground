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

import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ManagementSchemas {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String SCHEMA_KEYWORD = "$schema";
    private static final String DEFINITIONS_KEYWORD = "$defs";

    private ManagementSchemas() {
    }

    public static Map<String, Object> inputSchema(Class<?> inputType) {
        return schemaOf(inputType);
    }

    public static Map<String, Object> outputSchema(Class<?> dataType) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ok", Map.of("type", "boolean"));
        properties.put("message", Map.of("type", "string"));
        properties.put("persisted", Map.of("type", "boolean"));
        properties.put("restartRequired", Map.of("type", "boolean"));
        properties.put("warnings", Map.of("type", "array", "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ok", "message"));
        if (dataType != null) {
            Map<String, Object> dataSchema = schemaOf(dataType);
            dropRequiredLists(dataSchema);
            Object definitions = dataSchema.remove(DEFINITIONS_KEYWORD);
            if (definitions != null) {
                schema.put(DEFINITIONS_KEYWORD, definitions);
            }
            properties.put("data", dataSchema);
        }
        return schema;
    }

    private static void dropRequiredLists(Object node) {
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(ManagementSchemas::dropRequiredLists);
            map.entrySet().removeIf(entry -> "required".equals(entry.getKey()) && entry.getValue() instanceof List);
        } else if (node instanceof List<?> list) {
            list.forEach(ManagementSchemas::dropRequiredLists);
        }
    }

    private static Map<String, Object> schemaOf(Class<?> type) {
        Map<String, Object> schema = new LinkedHashMap<>(
                MAPPER.readValue(JsonSchemaGenerator.generateForType(type), MAP_TYPE));
        schema.remove(SCHEMA_KEYWORD);
        return schema;
    }

}
