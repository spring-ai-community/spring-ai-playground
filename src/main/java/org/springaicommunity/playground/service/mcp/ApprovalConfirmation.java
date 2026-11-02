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

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;

import java.util.List;
import java.util.Map;

public final class ApprovalConfirmation {

    public static final String APPROVE_FIELD = "approve";

    public static final String RUN_CHOICE = "run";

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(APPROVE_FIELD, Map.of(
                    "type", "array",
                    "title", "Approval",
                    "description", "Accept runs it. Untick it, or deny, to refuse.",
                    "items", Map.of("anyOf", List.of(Map.of("const", RUN_CHOICE, "title", "Run this call"))),
                    "default", List.of(RUN_CHOICE))),
            "required", List.of(APPROVE_FIELD));

    private ApprovalConfirmation() {
    }

    public static ElicitResult accepted() {
        return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of(APPROVE_FIELD, List.of(RUN_CHOICE)));
    }

    public static boolean isApproved(ElicitResult result) {
        return result != null && result.action() == ElicitResult.Action.ACCEPT && result.content() != null
                && result.content().get(APPROVE_FIELD) instanceof List<?> choices && choices.contains(RUN_CHOICE);
    }

    public static boolean matches(Map<String, Object> requestedSchema) {
        if (requestedSchema == null || !(requestedSchema.get("properties") instanceof Map<?, ?> properties)) {
            return false;
        }
        if (properties.size() != 1 || !(properties.get(APPROVE_FIELD) instanceof Map<?, ?> approve)) {
            return false;
        }
        return "array".equals(approve.get("type"));
    }

}
