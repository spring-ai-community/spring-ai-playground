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
package org.springaicommunity.playground.webui;

import com.vaadin.browserless.SpringBrowserlessTest;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.webui.chat.ChatView;
import org.springaicommunity.playground.webui.home.HomeView;
import org.springaicommunity.playground.webui.mcp.McpServerView;
import org.springaicommunity.playground.webui.observability.ObservabilityView;
import org.springaicommunity.playground.webui.tool.ToolStudioView;
import org.springaicommunity.playground.webui.vectorstore.VectorStoreView;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RouteRenderSmokeTest extends SpringBrowserlessTest {

    @Test
    void everyRoutedViewRendersInsideAppLayout() {
        assertThat(navigate(HomeView.class)).isNotNull();
        assertThat(navigate(ChatView.class)).isNotNull();
        assertThat(navigate(McpServerView.class)).isNotNull();
        assertThat(navigate(ObservabilityView.class)).isNotNull();
        assertThat(navigate(ToolStudioView.class)).isNotNull();
        assertThat(navigate(VectorStoreView.class)).isNotNull();
        assertThat(navigate(HomeView.class)).isNotNull();
    }

}
