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
package org.springaicommunity.playground.webui.common;

import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSettingsDrawerTest {

    @Test
    void closedDrawerIsInertAndHiddenSoFocusCannotScrollItIntoView() {
        WorkspaceSettingsDrawer drawer = new WorkspaceSettingsDrawer("Settings");
        drawer.setBodyFactory(() -> new Span("body"));

        assertThat(drawer.getElement().hasAttribute("inert")).isTrue();
        assertThat(drawer.getStyle().get("visibility")).isEqualTo("hidden");

        drawer.open();
        assertThat(drawer.getElement().hasAttribute("inert")).isFalse();
        assertThat(drawer.getStyle().get("visibility")).isEqualTo("visible");
        assertThat(drawer.getStyle().get("transform")).isEqualTo("translateX(0)");

        drawer.close();
        assertThat(drawer.getElement().hasAttribute("inert")).isTrue();
        assertThat(drawer.getStyle().get("visibility")).isEqualTo("hidden");
        assertThat(drawer.getStyle().get("transition")).contains("visibility 0s linear 220ms");
    }
}
