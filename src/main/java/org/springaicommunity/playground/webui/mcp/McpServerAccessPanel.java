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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.radiobutton.RadioGroupVariant;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.server.VaadinRequest;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.Source;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.springaicommunity.playground.webui.VaadinUtils.styledIcon;

public class McpServerAccessPanel {

    static final int SHORT_TOKEN_LENGTH = 16;

    private final McpServerAuthTokenService authTokenService;

    private RadioButtonGroup<AccessMode> modeGroup;
    private PasswordField tokenField;

    public McpServerAccessPanel(McpServerAuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    public Component build() {
        boolean localBrowser = isLocalBrowser();
        boolean editable = this.authTokenService.editable() && localBrowser;

        this.modeGroup = new RadioButtonGroup<>("Authentication");
        this.modeGroup.addThemeVariants(RadioGroupVariant.LUMO_VERTICAL);
        this.modeGroup.setItems(AccessMode.NONE, AccessMode.BEARER_TOKEN);
        this.modeGroup.setItemLabelGenerator(McpServerAccessPanel::modeLabel);
        this.modeGroup.setValue(this.authTokenService.accessMode());
        this.modeGroup.setReadOnly(!editable);

        this.tokenField = new PasswordField();
        this.tokenField.setPlaceholder("Bearer token");
        this.tokenField.setWidthFull();
        this.tokenField.setRevealButtonVisible(localBrowser);
        this.tokenField.setReadOnly(!editable);
        if (localBrowser) this.authTokenService.currentToken().ifPresent(this.tokenField::setValue);
        this.tokenField.addValueChangeListener(event -> refreshTokenHelper());

        Button generateButton = new Button("Generate", styledIcon(VaadinIcon.REFRESH.create()),
                event -> this.tokenField.setValue(this.authTokenService.generate()));
        generateButton.setEnabled(editable);
        Button copyButton = new Button("Copy", styledIcon(VaadinIcon.COPY_O.create()), event -> copyToken());
        copyButton.setEnabled(localBrowser);

        HorizontalLayout tokenRow = new HorizontalLayout(this.tokenField, generateButton, copyButton);
        tokenRow.setWidthFull();
        tokenRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        tokenRow.setFlexGrow(1, this.tokenField);
        tokenRow.setVisible(this.modeGroup.getValue() == AccessMode.BEARER_TOKEN);
        this.modeGroup.addValueChangeListener(event -> {
            tokenRow.setVisible(event.getValue() == AccessMode.BEARER_TOKEN);
            if (event.getValue() == AccessMode.BEARER_TOKEN && this.tokenField.isEmpty() && editable)
                this.tokenField.setValue(this.authTokenService.generate());
        });

        VerticalLayout root = new VerticalLayout(this.modeGroup, tokenRow);
        root.setPadding(false);
        root.setSpacing(false);
        String notice = notice(localBrowser);
        if (notice != null) {
            Span noticeSpan = new Span(notice);
            noticeSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            root.add(noticeSpan);
        }
        refreshTokenHelper();
        return root;
    }

    public boolean apply() {
        if (this.modeGroup == null || this.modeGroup.isReadOnly()) return true;
        AccessMode selectedMode = this.modeGroup.getValue();
        String enteredToken = this.tokenField.getValue();
        if (selectedMode == AccessMode.BEARER_TOKEN && (enteredToken == null || enteredToken.isBlank())) {
            this.tokenField.setInvalid(true);
            this.tokenField.setErrorMessage("Enter a token or click Generate.");
            return false;
        }
        boolean unchanged = selectedMode == this.authTokenService.accessMode()
                && (selectedMode == AccessMode.NONE
                        || this.authTokenService.currentToken().filter(enteredToken.strip()::equals).isPresent());
        if (unchanged) return true;
        if (selectedMode == AccessMode.BEARER_TOKEN) {
            this.authTokenService.requireToken(enteredToken);
            VaadinUtils.showInfoNotification("Built-in MCP server now requires the bearer token on /mcp.");
        } else {
            this.authTokenService.open();
            VaadinUtils.showInfoNotification("Built-in MCP server is open: no token required on /mcp.");
        }
        return true;
    }

    private void refreshTokenHelper() {
        String enteredToken = this.tokenField.getValue();
        this.tokenField.setInvalid(false);
        this.tokenField.setHelperText(enteredToken != null && !enteredToken.isBlank()
                && enteredToken.strip().length() < SHORT_TOKEN_LENGTH
                ? "Short token - Generate creates a 256-bit one."
                : "Clients send: Authorization: Bearer <token>");
    }

    private void copyToken() {
        if (this.tokenField.isEmpty()) return;
        this.tokenField.getElement().executeJs("navigator.clipboard.writeText($0)", this.tokenField.getValue());
        VaadinUtils.showInfoNotification("Bearer token copied to clipboard.");
    }

    private String notice(boolean localBrowser) {
        if (!localBrowser)
            return "Authentication can only be viewed and changed from the machine that runs the app.";
        if (this.authTokenService.source() == Source.CONFIGURATION)
            return "Set by configuration (" + McpServerAuthTokenService.ENV_NAME + " or "
                    + McpServerAuthTokenService.PROPERTY_NAME + "). Change it there and restart.";
        if (this.authTokenService.source() == Source.UNREADABLE)
            return "The stored token could not be read on this machine, so /mcp is locked. Set a new token.";
        return null;
    }

    private static String modeLabel(AccessMode accessMode) {
        return accessMode == AccessMode.NONE ? "None - open to anyone who can reach the port" : "Bearer token";
    }

    private static boolean isLocalBrowser() {
        VaadinRequest currentRequest = VaadinRequest.getCurrent();
        if (currentRequest == null || currentRequest.getRemoteAddr() == null) return true;
        try {
            return InetAddress.getByName(currentRequest.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
