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
package org.springaicommunity.playground.service.mcp.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpCompositionMemberTest {

    @Test
    void defaultAliasGeneratedWhenNullOrBlank() {
        McpComposition.Member m = new McpComposition.Member("github", "list_repos", null, "h1");
        assertEquals("github__list_repos", m.exposedAlias());
        McpComposition.Member m2 = new McpComposition.Member("github", "list_repos", "", "h1");
        assertEquals("github__list_repos", m2.exposedAlias());
    }

    @Test
    void explicitAliasIsPreserved() {
        McpComposition.Member m = new McpComposition.Member("github", "list_repos", "gh_list", "h1");
        assertEquals("gh_list", m.exposedAlias());
    }

    @Test
    void serverIdSanitizedInDefaultAlias() {
        McpComposition.Member m = new McpComposition.Member("My-Server.123", "do_thing", null, "h1");
        assertEquals("my_server_123__do_thing", m.exposedAlias());
    }

    @Test
    void unknownFallbacksAreSafe() {
        McpComposition.Member m = new McpComposition.Member(null, null, null, null);
        assertEquals("unknown__unknown", m.exposedAlias());
    }

    @Test
    void defaultAliasStaticHelper() {
        assertEquals("server__tool", McpComposition.Member.defaultAlias("server", "tool"));
        assertEquals("special_chars___t", McpComposition.Member.defaultAlias("special.chars!", "t"),
                "'.' and '!' both sanitized to '_', so 'special.chars!' → 'special_chars_'");
    }
}
