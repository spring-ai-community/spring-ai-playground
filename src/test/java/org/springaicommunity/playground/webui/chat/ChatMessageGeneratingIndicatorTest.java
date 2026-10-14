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

import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageGeneratingIndicatorTest {

    @Test
    void generatingIndicatorIsThreeHiddenDotsUntilShown() {
        ChatMessage message = new ChatMessage("Assistant", LocalDateTime.of(2026, 10, 14, 12, 0), -1);
        Element indicator = message.getElement().getChild(0).getChildren()
                .filter(child -> "saip-generating".equals(child.getAttribute("class"))).findFirst().orElseThrow();

        assertThat(indicator.getChildCount()).isEqualTo(3);
        assertThat(message.isGenerating()).isFalse();

        message.showGenerating();
        assertThat(message.isGenerating()).isTrue();
        assertThat(indicator.getStyle().get("display")).isEqualTo("inline-flex");

        message.hideGenerating();
        assertThat(message.isGenerating()).isFalse();
    }
}
