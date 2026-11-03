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
package org.springaicommunity.playground.webui.mcp;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.notification.Notification;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpServerDeleteGuardUiTest extends SpringBrowserlessTest {

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Test
    void deletingTheSelectedBuiltInServerIsRefusedByTheService() {
        McpServerView view = navigate(McpServerView.class);
        String builtin = this.mcpServerInfoService.getDefaultMcpServerInfo().serverName();
        Icon deleteIcon = $(Icon.class, view)
                .withCondition(icon -> icon.getTooltip() != null
                        && "Delete Selected MCP Server".equals(icon.getTooltip().getText()))
                .first();
        MenuItem deleteItem = (MenuItem) deleteIcon.getParent().orElseThrow();

        ComponentUtil.fireEvent(deleteItem, new ClickEvent<>(deleteItem));
        roundTrip();

        assertThat($(Notification.class).all())
                .extracting(notification -> notification.getElement().getProperty("text"))
                .contains("Built-in MCP server cannot be deleted.");
        assertThat(this.mcpServerInfoService.getDefaultMcpServerInfo().serverName()).isEqualTo(builtin);
    }
}
