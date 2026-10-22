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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class ManagementJson {

    public static final String MIME_TYPE = "application/json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private ManagementJson() {
    }

    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, MAP_TYPE);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static SyncResourceSpecification statusResource(String uri, String name, String title,
            String description, Supplier<Object> snapshot) {
        Resource resource = Resource.builder().uri(uri).name(name).title(title).description(description)
                .mimeType(MIME_TYPE).build();
        return new SyncResourceSpecification(resource, (exchange, request) -> new ReadResourceResult(
                List.of(new TextResourceContents(uri, MIME_TYPE, write(snapshot.get())))));
    }

}
