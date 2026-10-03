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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.webui.tool.ToolListView;
import org.springaicommunity.playground.webui.tool.ToolStudioView;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WorkspaceSidebarHeaderTest extends SpringBrowserlessTest {

    @Test
    void theTitleShrinksWithAnEllipsisSoTheHeaderActionsStayInsideTheSidebar() {
        ToolStudioView view = navigate(ToolStudioView.class);
        ToolListView sidebar = $(ToolListView.class, view).single();
        Header header = $(Header.class, sidebar).first();
        Span title = $(Span.class, header).first();
        MenuBar actions = $(MenuBar.class, header).single();

        test($(TextField.class, sidebar).first()).setValue("get");
        roundTrip();

        assertThat(title.getText()).startsWith("Tool List (");
        assertThat(title.getElement().getAttribute("title")).isEqualTo(title.getText());
        assertThat(title.getStyle().get("min-width")).isEqualTo("0");
        assertThat(title.getStyle().get("text-overflow")).isEqualTo("ellipsis");
        assertThat(title.getStyle().get("overflow")).isEqualTo("hidden");
        assertThat(actions.getStyle().get("flex")).isEqualTo("0 0 auto");
        assertThat(actions.getWidth()).isNull();
    }
}
