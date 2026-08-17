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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.PersistenceServiceInterface;

import static org.assertj.core.api.Assertions.assertThat;

class ChatToolPreferencesTest {

    private static ChatToolPreferences read(String json) {
        return PersistenceServiceInterface.OBJECT_MAPPER.readValue(json, ChatToolPreferences.class);
    }

    @Test
    void legacyRagDocInfoIdsBecomeTheDocumentSourceId() {
        ChatToolPreferences preferences = read("""
                {"useBuiltinMcp": false, "exposedToolIds": [], "ragDocInfoIds": ["docInfoId-4a20deac"],
                 "mcpServerNames": {}, "reasoningEffort": "DEFAULT", "dynamicTools": true}
                """);

        assertThat(preferences.ragSourceId()).isEqualTo("doc:docInfoId-4a20deac");
        assertThat(preferences.dynamicTools()).isTrue();
    }

    @Test
    void legacyEmptyRagDocInfoIdsLeaveNoSource() {
        ChatToolPreferences preferences = read("""
                {"useBuiltinMcp": true, "exposedToolIds": ["a1"], "ragDocInfoIds": [], "mcpServerNames": {},
                 "reasoningEffort": "LOW", "dynamicTools": false}
                """);

        assertThat(preferences.ragSourceId()).isNull();
        assertThat(preferences.useBuiltinMcp()).isTrue();
        assertThat(preferences.exposedToolIds()).containsExactly("a1");
        assertThat(preferences.reasoningEffort()).isEqualTo(ReasoningEffort.LOW);
    }

    @Test
    void currentRagSourceIdRoundTripsAndWinsOverTheLegacyField() {
        ChatToolPreferences preferences = read("""
                {"ragSourceId": "pipeline-7", "ragDocInfoIds": ["docInfoId-old"], "dynamicTools": true}
                """);
        assertThat(preferences.ragSourceId()).isEqualTo("pipeline-7");

        String json = PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsString(preferences);
        assertThat(json).contains("\"ragSourceId\":\"pipeline-7\"").doesNotContain("ragDocInfoIds");
        assertThat(read(json)).isEqualTo(preferences);
    }
}
