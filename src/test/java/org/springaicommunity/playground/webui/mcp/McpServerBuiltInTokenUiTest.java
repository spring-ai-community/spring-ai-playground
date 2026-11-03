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
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.webui.common.WorkspaceSettingsDrawer;
import org.springaicommunity.playground.webui.home.HomeView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.playground.mcp-server.auth-token=browserless-ui-token")
class McpServerBuiltInTokenUiTest extends SpringBrowserlessTest {

    private static final String MANAGED_HINT = "Bearer token is managed in the built-in server settings (gear icon).";

    @Autowired
    private McpServerInfoService mcpServerInfoService;

    @Test
    void builtInServerDetailHidesTheAuthorizationHeaderButKeepsItOnTheConnection() {
        McpServerView view = navigate(McpServerView.class);
        McpServerConfigView configView = $(McpServerConfigView.class, view).first();

        assertThat($(TextField.class, configView).all()).extracting(TextField::getValue)
                .noneMatch(fieldValue -> fieldValue.contains("browserless-ui-token"))
                .noneMatch("Authorization"::equalsIgnoreCase);
        assertThat(configView.getElement().getTextRecursively()).doesNotContain("browserless-ui-token");
        assertThat($(Span.class, configView).all()).extracting(Span::getText).contains(MANAGED_HINT);
        assertThat(this.mcpServerInfoService.getDefaultMcpServerInfo().connectionAsJson())
                .contains("Bearer browserless-ui-token");
    }

    @Test
    void builtInServerFormCannotBeSavedBecauseTheAppManagesItsConnection() {
        McpServerView view = navigate(McpServerView.class);
        McpServerConfigView configView = $(McpServerConfigView.class, view).first();
        Button save = $(Button.class, configView)
                .withCondition(button -> "Save & Connect".equals(button.getText())).first();

        $(TextArea.class, configView).first().setValue("edited description");

        assertThat(save.isEnabled()).isFalse();
        assertThat(configView.getElement().getTextRecursively())
                .contains("The built-in server connection is managed by the app");
    }

    @Test
    void applyingTheBuiltInServerDrawerRebuildsTheInspectorSoItListsTheCurrentTools() {
        McpServerView view = navigate(McpServerView.class);
        roundTrip();
        McpContentView before = $(McpContentView.class, view).single();
        WorkspaceSettingsDrawer drawer = $(WorkspaceSettingsDrawer.class, view).first();
        drawer.open();
        roundTrip();

        Button apply = $(Button.class, drawer).withCondition(button -> "Apply".equals(button.getText())).first();
        test(apply).click();
        roundTrip();

        assertThat($(McpContentView.class, view).single()).isNotSameAs(before);
    }

    @Test
    void homeBindPillReadsTokenRequiredInsteadOfNoAuth() {
        HomeView view = navigate(HomeView.class);

        String homeText = view.getElement().getTextRecursively();
        assertThat(homeText).contains("token required").doesNotContain("no auth");
    }
}
