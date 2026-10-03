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
import org.springaicommunity.playground.service.chat.ChatHistory;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryViewTest {

    @Test
    void untitledConversationsAreShownWithThePlaceholderLabel() {
        assertThat(ChatHistoryView.displayTitle(history(null))).isEqualTo(ChatHistoryView.UNTITLED_CHAT_LABEL);
        assertThat(ChatHistoryView.displayTitle(history("  "))).isEqualTo(ChatHistoryView.UNTITLED_CHAT_LABEL);
    }

    @Test
    void titledConversationsKeepTheirOwnTitle() {
        assertThat(ChatHistoryView.displayTitle(history("Quarterly plan"))).isEqualTo("Quarterly plan");
    }

    private static ChatHistory history(String title) {
        return new ChatHistory("Chat-1", title, 1L, 1L, "system", null, null);
    }
}
