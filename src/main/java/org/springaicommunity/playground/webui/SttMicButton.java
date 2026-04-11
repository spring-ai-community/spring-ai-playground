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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValueAndElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import jakarta.servlet.http.HttpServletRequest;

public class SttMicButton extends Button {

    private static final String BUTTON_ID = "micButton";

    private enum SttSupport {CHROME, ELECTRON, UNSUPPORTED_BROWSER}

    private final SttSupport sttSupport;
    private final String serverUrl;

    public <C extends Component & HasValueAndElement<?, String>> SttMicButton(C targetComponent) {
        super(VaadinUtils.styledLargeIcon(VaadinIcon.MICROPHONE.create()));
        this.sttSupport = resolveSttSupport();
        this.serverUrl = (this.sttSupport == SttSupport.ELECTRON) ? resolveServerUrl() : null;
        addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        setId(BUTTON_ID);
        configureByEnvironment(targetComponent);
    }

    private <C extends Component & HasValueAndElement<?, String>> void configureByEnvironment(C targetComponent) {
        switch (this.sttSupport) {
            case CHROME -> {
                setTooltipText("Voice input");
                addClickListener(e ->
                        getElement().executeJs("window.STTModule.toggle($0, $1)",
                                targetComponent.getId().orElse(""),
                                getId().orElse(BUTTON_ID))
                );
            }
            case ELECTRON -> {
                setTooltipText("Voice input is only available in the browser");
                addClickListener(e -> showElectronDialog());
            }
            case UNSUPPORTED_BROWSER -> {
                setTooltipText("Voice input is only supported in Chrome");
                addClickListener(e -> showUnsupportedBrowserDialog());
            }
        }
    }

    private SttSupport resolveSttSupport() {
        VaadinRequest request = VaadinService.getCurrentRequest();
        if (request == null) return SttSupport.UNSUPPORTED_BROWSER;
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return SttSupport.UNSUPPORTED_BROWSER;
        if (userAgent.contains("Electron")) return SttSupport.ELECTRON;
        if (userAgent.contains("Chrome/")) return SttSupport.CHROME;
        return SttSupport.UNSUPPORTED_BROWSER;
    }

    private String resolveServerUrl() {
        try {
            VaadinRequest request = VaadinService.getCurrentRequest();
            if (request == null) return null;
            HttpServletRequest http = (HttpServletRequest) request;
            String scheme = http.getScheme();
            String host = http.getServerName();
            int port = http.getServerPort();
            boolean isDefaultPort = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return isDefaultPort
                    ? scheme + "://" + host
                    : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    private void showElectronDialog() {
        Dialog dialog = new Dialog();
        dialog.setCloseOnOutsideClick(true);
        dialog.setCloseOnEsc(true);
        dialog.setHeaderTitle("Voice Input Not Supported");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        content.add(new Paragraph(
                "Voice input is not supported in the desktop app. "
                        + "Please open in a Chrome browser to use voice input."));

        if (this.serverUrl != null) {
            Paragraph urlInfo = new Paragraph("URL: " + this.serverUrl);
            urlInfo.getStyle().set("font-weight", "bold");
            content.add(urlInfo);
        }

        dialog.add(content);

        if (this.serverUrl != null) {
            Button openBrowserButton = new Button("Open in Browser", e -> {
                UI.getCurrent().getPage().open(this.serverUrl, "_blank");
                dialog.close();
            });
            openBrowserButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            dialog.getFooter().add(openBrowserButton);
        }

        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    private void showUnsupportedBrowserDialog() {
        Dialog dialog = new Dialog();
        dialog.setCloseOnOutsideClick(true);
        dialog.setCloseOnEsc(true);
        dialog.setHeaderTitle("Voice Input Not Supported");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        content.add(new Paragraph(
                "Voice input is only supported in the Chrome browser. "
                        + "Please open this page in Chrome to use voice input."));

        dialog.add(content);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    public boolean isSttAvailable() {
        return this.sttSupport == SttSupport.CHROME;
    }
}