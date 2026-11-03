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
package org.springaicommunity.playground.webui.shipyard;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.tabs.TabsVariant;
import com.vaadin.flow.component.textfield.TextArea;
import org.springaicommunity.playground.service.mcp.bundle.BundleArtifactWriter;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ConnectSection extends Div {

    private final TextArea snippetArea = new TextArea();
    private final Map<Tab, String> snippetByTab = new LinkedHashMap<>();

    public ConnectSection(BundleEntry entry, DeployTarget target, int hostPort, Optional<String> authToken,
            ConnectionSnippetService connectionSnippetService, Runnable onAddToThisApp,
            Runnable onTestConnection, Span testResult) {
        String url = ConnectionSnippetService.mcpUrl(target, hostPort);

        Span heading = new Span("Connect");
        heading.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-m)");

        Span endpointLabel = new Span("Endpoint");
        endpointLabel.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        Span endpoint = new Span(url);
        endpoint.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "2px var(--lumo-space-xs)")
                .set("border-radius", "var(--lumo-border-radius-s)");
        Button testButton = new Button("Test connection", e -> onTestConnection.run());
        testButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        HorizontalLayout endpointRow = new HorizontalLayout(endpointLabel, endpoint, testButton, testResult);
        endpointRow.setAlignItems(HorizontalLayout.Alignment.CENTER);
        endpointRow.getStyle().set("gap", "var(--lumo-space-s)").set("flex-wrap", "wrap");

        HorizontalLayout oneClick = new HorizontalLayout();
        oneClick.getStyle().set("gap", "var(--lumo-space-s)").set("flex-wrap", "wrap")
                .set("margin", "var(--lumo-space-s) 0");
        Button addHere = new Button("Add to this app", VaadinIcon.BOLT.create(), e -> onAddToThisApp.run());
        addHere.addThemeVariants(ButtonVariant.LUMO_SMALL);
        oneClick.add(addHere);
        for (ConnectionSnippetService.DeepLink link : connectionSnippetService.deepLinksFor(
                entry.manifest(), target, hostPort, authToken)) {
            Anchor anchor = new Anchor();
            anchor.setUnsafeHref(link.url());
            anchor.setText("Install in " + link.client());
            anchor.getElement().getThemeList().add("badge");
            anchor.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
                    .set("text-decoration", "none");
            oneClick.add(anchor);
        }

        Tabs clientTabs = new Tabs();
        clientTabs.addThemeVariants(TabsVariant.LUMO_SMALL);
        for (ConnectionSnippetService.Snippet snippet : connectionSnippetService.snippetsFor(
                entry.manifest(), target, hostPort, authToken)) {
            Tab tab = new Tab(snippet.label());
            this.snippetByTab.put(tab, snippet.content());
            clientTabs.add(tab);
        }
        clientTabs.addSelectedChangeListener(e -> showSnippet(e.getSelectedTab()));

        this.snippetArea.setReadOnly(true);
        this.snippetArea.setWidthFull();
        this.snippetArea.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        Button copyButton = new Button("Copy", VaadinIcon.COPY_O.create(), e -> copySnippet());
        copyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        HorizontalLayout snippetHeader = new HorizontalLayout(clientTabs, copyButton);
        snippetHeader.setAlignItems(HorizontalLayout.Alignment.CENTER);
        snippetHeader.setWidthFull();
        snippetHeader.expand(clientTabs);

        String restart = "Restart the client after adding, then look for '" + entry.name() + "' in its MCP list. "
                + "The stdio snippet runs the image " + BundleArtifactWriter.localImage(entry.manifest())
                + " that Run builds on " + (target.isLocal() ? "this machine."
                        : "target '" + target.name() + "'; the client machine needs the same Docker access (docker "
                                + String.join(" ", target.cliArguments()) + ").");
        Span hint = new Span(authToken.isPresent()
                ? "Includes the bearer token this server requires; treat it as a secret. " + restart
                : restart);
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
                .set("margin-top", "var(--lumo-space-xs)");

        add(heading, endpointRow, oneClick, snippetHeader, this.snippetArea, hint);
        getStyle().set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-m)").set("box-sizing", "border-box");
        this.snippetByTab.keySet().stream().findFirst().ifPresent(this::showSnippet);
    }

    private void showSnippet(Tab tab) {
        this.snippetArea.setValue(this.snippetByTab.getOrDefault(tab, ""));
    }

    private void copySnippet() {
        this.snippetArea.getElement().executeJs("navigator.clipboard.writeText($0)",
                this.snippetArea.getValue());
        VaadinUtils.showInfoNotification("Copied to clipboard");
    }

}
