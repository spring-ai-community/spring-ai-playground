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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpServerAddFlowTest extends SpringBrowserlessTest {

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Test
    void addCustomServerShowsBlankConfigForm() {
        McpServerView view = navigate(McpServerView.class);

        Button addServer = $(Button.class, view)
                .withCondition(button -> "Add Custom Server".equals(button.getTooltip().getText()))
                .first();
        test(addServer).click();
        roundTrip();

        McpServerConfigView configView = $(McpServerConfigView.class, view).first();
        assertThat(configView).isNotNull();
    }

    @Test
    void saveAndConnectSurfacesTheServiceDuplicateNameRefusal() {
        long now = System.currentTimeMillis();
        McpServerInfo taken = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, "dup-ui", "d", now, now,
                "{\"url\":\"https://dup.example.com\"}");
        this.mcpServerInfoService.updateMcpServerInfo(taken.mcpTransportType(), taken.serverName(), taken);
        try {
            McpServerView view = navigate(McpServerView.class);
            Button addServer = $(Button.class, view)
                    .withCondition(button -> "Add Custom Server".equals(button.getTooltip().getText()))
                    .first();
            test(addServer).click();
            roundTrip();
            McpServerConfigView configView = $(McpServerConfigView.class, view).first();
            TextField nameField = $(TextField.class, configView)
                    .withCondition(field -> "Server name".equals(field.getLabel())).first();
            test(nameField).setValue("dup-ui");
            Button save = $(Button.class, configView)
                    .withCondition(button -> "Save & Connect".equals(button.getText())).first();
            assertThat(save.isEnabled()).isTrue();

            test(save).click();
            roundTrip();
            Button proceed = $(Button.class)
                    .withCondition(button -> "I understand, proceed".equals(button.getText())).first();
            test(proceed).click();
            roundTrip();

            assertThat($(Notification.class).all())
                    .extracting(notification -> notification.getElement().getProperty("text"))
                    .contains("Failed to save : MCP connection already exists with name dup-ui");
        } finally {
            this.mcpServerInfoService.deleteMcpServerInfo(taken.mcpTransportType(), taken.serverName());
        }
    }

    @Test
    void savingAServerWhoseEnvironmentVariableIsMissingNamesTheVariable() {
        McpServerView view = navigate(McpServerView.class);
        Button addServer = $(Button.class, view)
                .withCondition(button -> "Add Custom Server".equals(button.getTooltip().getText()))
                .first();
        test(addServer).click();
        roundTrip();
        McpServerConfigView configView = $(McpServerConfigView.class, view).first();
        test($(TextField.class, configView)
                .withCondition(field -> "Server name".equals(field.getLabel())).first()).setValue("gated-ui");
        test($(TextField.class, configView)
                .withCondition(field -> "URL".equals(field.getLabel())).first())
                .setValue("https://${QA_UNSET_MCP_HOST_VARIABLE}/");
        try {
            test($(Button.class, configView)
                    .withCondition(button -> "Save & Connect".equals(button.getText())).first()).click();
            roundTrip();
            $(Button.class).withCondition(button -> "I understand, proceed".equals(button.getText())).all()
                    .forEach(proceed -> test(proceed).click());
            roundTrip();

            assertThat($(Notification.class).all())
                    .extracting(notification -> notification.getElement().getProperty("text"))
                    .anyMatch(text -> text.startsWith("Saved, but not connected. Set QA_UNSET_MCP_HOST_VARIABLE"));
            assertThat(this.mcpServerInfoService.externalServerSummary().connected()).isZero();
        } finally {
            this.mcpServerInfoService.findByServerName("gated-ui").ifPresent(saved ->
                    this.mcpServerInfoService.deleteMcpServerInfo(saved.mcpTransportType(), saved.serverName()));
        }
    }

    @Test
    void builtInServerDetailShowsNoManagedTokenHintWhileTheServerIsOpen() {
        McpServerView view = navigate(McpServerView.class);
        McpServerConfigView configView = $(McpServerConfigView.class, view).first();

        assertThat($(Span.class, configView).all()).extracting(Span::getText)
                .noneMatch(spanText -> spanText.startsWith("Bearer token is managed"));
    }

}
