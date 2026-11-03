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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalConfirmationTest {

    @Test
    void theSchemaAsksOnePreTickedRunChoice() {
        assertThat(ApprovalConfirmation.SCHEMA).containsEntry("required", List.of("approve"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ApprovalConfirmation.SCHEMA.get("properties");
        assertThat(properties).containsOnlyKeys("approve");
        @SuppressWarnings("unchecked")
        Map<String, Object> approve = (Map<String, Object>) properties.get("approve");
        assertThat(approve).containsEntry("type", "array").containsEntry("default", List.of("run"));
        assertThat(approve.get("items")).isEqualTo(Map.of("anyOf", List.of(Map.of("const", "run", "title", "Run this call"))));
    }

    @Test
    void onlyAnAcceptThatKeepsRunTickedCountsAsApproval() {
        assertThat(ApprovalConfirmation.isApproved(ApprovalConfirmation.accepted())).isTrue();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("approve", List.of("run"))))).isTrue();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("approve", List.of())))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of()))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(new ElicitResult(ElicitResult.Action.ACCEPT, null))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("approve", true)))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("approve", "run")))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.DECLINE, Map.of("approve", List.of("run"))))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(
                new ElicitResult(ElicitResult.Action.CANCEL, Map.of("approve", List.of("run"))))).isFalse();
        assertThat(ApprovalConfirmation.isApproved(null)).isFalse();
    }

    @Test
    void matchesRecognisesOnlyTheApprovalSchema() {
        assertThat(ApprovalConfirmation.matches(ApprovalConfirmation.SCHEMA)).isTrue();
        assertThat(ApprovalConfirmation.matches(null)).isFalse();
        assertThat(ApprovalConfirmation.matches(Map.of())).isFalse();
        assertThat(ApprovalConfirmation.matches(Map.of("type", "object", "properties", Map.of()))).isFalse();
        assertThat(ApprovalConfirmation.matches(Map.of("type", "object",
                "properties", Map.of("approve", Map.of("type", "boolean"))))).isFalse();
        assertThat(ApprovalConfirmation.matches(Map.of("type", "object",
                "properties", Map.of("approve", Map.of("type", "array"), "reason", Map.of("type", "string")))))
                .isFalse();
    }

}
