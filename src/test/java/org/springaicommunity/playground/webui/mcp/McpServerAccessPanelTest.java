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
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.PasswordField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.oauth.OAuthTokenEncryptor;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpServerAccessPanelTest extends SpringBrowserlessTest {

    @TempDir
    Path homeDir;

    private McpServerAuthTokenService newService(String configuredToken) throws IOException {
        return new McpServerAuthTokenService(new SpringAiPlaygroundOptions(null, false, null, null, null,
                new SpringAiPlaygroundOptions.McpServer(null, null, configuredToken)), this.homeDir,
                new OAuthTokenEncryptor(this.homeDir), event -> { });
    }

    private Component attach(McpServerAccessPanel accessPanel) {
        Component panelRoot = accessPanel.build();
        UI.getCurrent().add(panelRoot);
        return panelRoot;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pickingBearerTokenGeneratesOneAndApplyRequiresIt() throws IOException {
        McpServerAuthTokenService authTokenService = newService(null);
        McpServerAccessPanel accessPanel = new McpServerAccessPanel(authTokenService);
        Component panelRoot = attach(accessPanel);
        RadioButtonGroup<AccessMode> modeGroup = $(RadioButtonGroup.class, panelRoot).single();
        assertThat(modeGroup.getValue()).isEqualTo(AccessMode.NONE);
        assertThat($(PasswordField.class, panelRoot).all()).isEmpty();

        modeGroup.setValue(AccessMode.BEARER_TOKEN);

        String generatedToken = $(PasswordField.class, panelRoot).single().getValue();
        assertThat(generatedToken).hasSizeGreaterThanOrEqualTo(43);
        assertThat(accessPanel.apply()).isTrue();
        assertThat(authTokenService.matches(generatedToken)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankTokenBlocksApplyAndNoneReopensTheServer() throws IOException {
        McpServerAuthTokenService authTokenService = newService(null);
        authTokenService.requireToken("existing-panel-token");
        McpServerAccessPanel accessPanel = new McpServerAccessPanel(authTokenService);
        Component panelRoot = attach(accessPanel);
        RadioButtonGroup<AccessMode> modeGroup = $(RadioButtonGroup.class, panelRoot).single();
        PasswordField tokenField = $(PasswordField.class, panelRoot).single();
        assertThat(tokenField.getValue()).isEqualTo("existing-panel-token");

        tokenField.setValue("");
        assertThat(accessPanel.apply()).isFalse();
        assertThat(tokenField.isInvalid()).isTrue();
        assertThat(authTokenService.matches("existing-panel-token")).isTrue();

        modeGroup.setValue(AccessMode.NONE);
        assertThat(accessPanel.apply()).isTrue();
        assertThat(authTokenService.accessMode()).isEqualTo(AccessMode.NONE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredTokenRendersReadOnlyWithTheConfigurationNotice() throws IOException {
        McpServerAccessPanel accessPanel = new McpServerAccessPanel(newService("configured-panel-token"));
        Component panelRoot = attach(accessPanel);

        assertThat($(RadioButtonGroup.class, panelRoot).single().isReadOnly()).isTrue();
        assertThat($(PasswordField.class, panelRoot).single().isReadOnly()).isTrue();
        assertThat($(Button.class, panelRoot).all()).filteredOn(button -> "Generate".equals(button.getText()))
                .allMatch(button -> !button.isEnabled());
        assertThat($(Span.class, panelRoot).all()).anyMatch(span -> span.getText().startsWith("Set by configuration"));
        assertThat(accessPanel.apply()).isTrue();
    }
}
