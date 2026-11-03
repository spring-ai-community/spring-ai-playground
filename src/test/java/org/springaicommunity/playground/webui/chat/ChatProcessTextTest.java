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
package org.springaicommunity.playground.webui.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatProcessTextTest {

    private static final String BLOCK = "```saip-action\n{\"type\":\"pointmap\"}\n```";

    @Test
    void durationsAndTokensMatchTheChatSummaries() {
        assertThat(ChatProcessText.formatDuration(999)).isEqualTo("999ms");
        assertThat(ChatProcessText.formatDuration(1_550)).isEqualTo("1.6s");
        assertThat(ChatProcessText.formatTokens(1_200, 34)).isEqualTo("1,234 tokens (in 1,200 \u00b7 out 34)");
        assertThat(ChatProcessText.tokensExtraOf(Map.of("p", 1, "c", 2), "p", "c"))
                .isEqualTo("3 tokens (in 1 \u00b7 out 2)");
        assertThat(ChatProcessText.tokensExtraOf(Map.of("p", 1), "p", "c")).isNull();
    }

    @Test
    void extrasJoinOnlyThePartsThatExist() {
        assertThat(ChatProcessText.joinExtra(null, "b")).isEqualTo("b");
        assertThat(ChatProcessText.joinExtra("a", "")).isEqualTo("a");
        assertThat(ChatProcessText.joinExtra("a", "b")).isEqualTo("a \u00b7 b");
        assertThat(ChatProcessText.ragExtra(1, List.of())).isEqualTo("1 doc");
        assertThat(ChatProcessText.ragExtra(2, List.of("a.txt", "b.txt"))).isEqualTo("2 docs \u00b7 a.txt, b.txt");
        assertThat(ChatProcessText.ragExtra(-1, List.of())).isNull();
        assertThat(ChatProcessText.mcpExtra(0, List.of("x"))).isNull();
        assertThat(ChatProcessText.mcpExtra(1, List.of("x"))).isEqualTo("1 call \u00b7 x");
    }

    @Test
    void actionBlocksAreAppendedOnlyWhenMissing() {
        assertThat(ChatProcessText.appendActionBlocks("done.", List.of(BLOCK)))
                .isEqualTo("done.\n\n" + BLOCK + "\n");
        assertThat(ChatProcessText.appendActionBlocks(null, List.of(BLOCK))).isEqualTo("\n\n" + BLOCK + "\n");
        assertThat(ChatProcessText.appendActionBlocks("done.\n\n" + BLOCK + "\n", List.of(BLOCK)))
                .isEqualTo("done.\n\n" + BLOCK + "\n");
    }

    @Test
    void textIsUntouchedWithoutABlockList() {
        assertThat(ChatProcessText.appendActionBlocks("done.", null)).isEqualTo("done.");
        assertThat(ChatProcessText.appendActionBlocks("done.", "not-a-list")).isEqualTo("done.");
        assertThat(ChatProcessText.appendActionBlocks(null, List.of())).isNull();
    }
}
