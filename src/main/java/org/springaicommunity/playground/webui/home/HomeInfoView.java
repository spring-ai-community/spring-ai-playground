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
package org.springaicommunity.playground.webui.home;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.observability.ObservabilityTimeSeries;
import org.springaicommunity.playground.observability.system.SystemMetricsSnapshot;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ProviderReadinessService;
import org.springaicommunity.playground.service.home.ReleaseCheckService;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService;
import org.springaicommunity.playground.webui.chat.ChatView;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class HomeInfoView extends Div {

    private static final Logger logger = LoggerFactory.getLogger(HomeInfoView.class);

    private static final String CONFIGURE_URL =
            "https://spring-ai-community.github.io/spring-ai-playground/getting-started/";

    private final Div updateBannerSlot;
    private final Div alertBannerSlot;
    private final HomeChecklist checklist;
    private final ReleaseCheckService releaseCheckService;

    public HomeInfoView(ToolSpecService toolSpecService,
            McpServerInfoService mcpServerInfoService,
            McpClientService mcpClientService,
            McpCatalogService mcpCatalogService,
            OfflineEtlPipelineService offlineEtlPipelineService,
            ChatHistoryService chatHistoryService,
            ToolSpecPersistenceService toolSpecPersistenceService,
            ToolActivationCalculator toolActivationCalculator,
            ObjectProvider<ChatModel> chatModelProvider,
            ProviderReadinessService providerReadinessService,
            ObservabilityTimeSeries observabilityTimeSeries,
            SystemMetricsSnapshot systemMetricsSnapshot,
            McpRiskEventRingBuffer mcpRiskEventRingBuffer,
            ReleaseCheckService releaseCheckService,
            Environment environment) {
        setSizeFull();
        this.releaseCheckService = releaseCheckService;

        VerticalLayout content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(false);
        content.setSpacing(false);
        content.getStyle()
                .set("padding", "2rem")
                .set("gap", "1.75rem")
                .set("max-width", "1400px")
                .set("margin", "0 auto");

        this.updateBannerSlot = new Div();
        this.updateBannerSlot.setWidthFull();
        this.updateBannerSlot.setVisible(false);

        this.alertBannerSlot = new Div();
        this.alertBannerSlot.setWidthFull();
        this.alertBannerSlot.setVisible(false);
        if (chatModelProvider.getIfAvailable() == null) {
            renderProviderAlert();
        }

        HomeSystemPanel systemPanel = new HomeSystemPanel(
                providerReadinessService, environment,
                toolSpecService, mcpServerInfoService,
                observabilityTimeSeries, systemMetricsSnapshot, mcpRiskEventRingBuffer);
        HomeSurfaceCards surfaceCards = new HomeSurfaceCards(
                toolSpecService,
                mcpServerInfoService, offlineEtlPipelineService, mcpCatalogService,
                chatHistoryService);
        this.checklist = new HomeChecklist(
                providerReadinessService, toolSpecService, toolSpecPersistenceService,
                offlineEtlPipelineService, chatHistoryService);
        HomeRecentActivity recentActivity = new HomeRecentActivity(
                toolSpecService, toolSpecPersistenceService,
                mcpServerInfoService, offlineEtlPipelineService, chatHistoryService);

        content.add(
                this.alertBannerSlot,
                this.updateBannerSlot,
                createHero(chatModelProvider, chatHistoryService),
                surfaceCards,
                systemPanel,
                this.checklist,
                recentActivity
        );

        Scroller scroller = new Scroller(content);
        scroller.setSizeFull();
        scroller.setScrollDirection(Scroller.ScrollDirection.VERTICAL);
        add(scroller);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();

        ui.getPage().executeJs(
                "const checklistCollapsed = localStorage.getItem('home_checklist_collapsed') === 'true';"
                        + "$0.$server.onClientEnvironment(checklistCollapsed);",
                getElement());

        CompletableFuture.supplyAsync(this.releaseCheckService::availableUpdate)
                .thenAccept(release -> release.ifPresent(update -> ui.access(() -> renderUpdateBanner(update))))
                .exceptionally(ex -> {
                    logger.debug("Update check failed", ex);
                    return null;
                });
    }

    @ClientCallable
    private void onClientEnvironment(boolean checklistCollapsed) {
        checklist.applyCollapsedState(checklistCollapsed);
    }


    private Component createHero(ObjectProvider<ChatModel> chatModelProvider,
            ChatHistoryService chatHistoryService) {
        Div hero = new Div();
        hero.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "0.5rem");

        H1 title = new H1("Spring AI Playground");
        title.getStyle()
                .set("margin", "0")
                .set("font-size", "2.25rem")
                .set("letter-spacing", "-0.02em");

        Paragraph tagline = new Paragraph("Safe Local Execution Layer for AI Agent Tools");
        tagline.getStyle()
                .set("margin", "0")
                .set("font-size", "var(--lumo-font-size-l)")
                .set("color", "var(--lumo-secondary-text-color)");

        Span motto = new Span("No pass, no run.");
        motto.getStyle()
                .set("display", "inline-block")
                .set("padding", "0.3rem 0.8rem")
                .set("border-radius", "999px")
                .set("background-color", "var(--lumo-primary-color-10pct)")
                .set("color", "var(--lumo-primary-text-color)")
                .set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("flex-shrink", "0")
                .set("white-space", "nowrap");

        Div mottoHint = new Div();
        mottoHint.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("line-height", "1.55")
                .set("flex", "1 1 320px")
                .set("min-width", "0");

        Span hintBefore = new Span("Every tool earns a ");
        Span localPassBadge = new Span("Local Pass");
        localPassBadge.getStyle()
                .set("display", "inline-block")
                .set("padding", "0.05rem 0.45rem")
                .set("margin", "0 0.15rem")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background-color", "var(--lumo-success-color-10pct)")
                .set("color", "var(--lumo-success-text-color)")
                .set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("letter-spacing", "0.02em")
                .set("vertical-align", "baseline");
        Span hintAfter = new Span(
                " - a local test-run with your sample arguments. "
                        + "Only passing tools go live on the built-in MCP server "
                        + "and become callable from chat.");
        mottoHint.add(hintBefore, localPassBadge, hintAfter);

        Div principleRow = new Div();
        principleRow.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("align-items", "center")
                .set("gap", "1rem")
                .set("margin-top", "0.5rem");
        principleRow.add(motto, mottoHint);

        hero.add(title, tagline, principleRow);
        Component cta = createHeroCta(chatModelProvider, chatHistoryService);
        if (cta != null) {
            hero.add(cta);
        }
        return hero;
    }

    private Component createHeroCta(ObjectProvider<ChatModel> chatModelProvider,
            ChatHistoryService chatHistoryService) {
        Div row = new Div();
        row.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("align-items", "center")
                .set("gap", "0.75rem")
                .set("margin-top", "0.75rem");

        if (chatModelProvider.getIfAvailable() == null) {
            return null;
        }

        Optional<ChatHistory> recent = chatHistoryService.getChatHistoryList().stream()
                .max(Comparator.comparingLong(ChatHistory::updateTimestamp));
        if (recent.isEmpty()) {
            return null;
        }
        ChatHistory history = recent.get();
        String label = (history.title() == null || history.title().isBlank())
                ? "last chat" : history.title();
        Button resume = new Button("Resume · " + label, VaadinIcon.PLAY.create());
        resume.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        String conversationId = history.conversationId();
        resume.addClickListener(e -> UI.getCurrent().navigate(
                ChatView.class, QueryParameters.simple(Map.of("conv", conversationId))));
        row.add(resume);
        return row;
    }

    private static void styleCtaAnchor(Anchor anchor) {
        anchor.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("gap", "0.4rem")
                .set("color", "var(--lumo-primary-contrast-color)")
                .set("background-color", "var(--lumo-primary-color)")
                .set("padding", "0.55rem 1rem")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("text-decoration", "none")
                .set("font-weight", "500");
    }


    private void renderProviderAlert() {
        Div banner = new Div();
        banner.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("flex-wrap", "wrap")
                .set("gap", "0.75rem")
                .set("padding", "0.75rem 1rem")
                .set("background-color", "var(--lumo-warning-color-10pct)")
                .set("border", "1px solid var(--lumo-warning-color-50pct)")
                .set("border-radius", "var(--lumo-border-radius-l)");

        Icon icon = VaadinIcon.WARNING.create();
        icon.getStyle()
                .set("width", "var(--lumo-icon-size-s)")
                .set("height", "var(--lumo-icon-size-s)")
                .set("color", "var(--lumo-warning-text-color)");

        Div textBlock = new Div();
        textBlock.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "0.15rem")
                .set("flex", "1 1 auto")
                .set("min-width", "0");
        Span headline = new Span("No model provider configured");
        headline.getStyle()
                .set("font-weight", "600")
                .set("color", "var(--lumo-body-text-color)");
        Span detail = new Span("Configure Ollama or an OpenAI key to start chatting.");
        detail.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");
        textBlock.add(headline, detail);

        Anchor configure = new Anchor(CONFIGURE_URL, "Configure a provider");
        configure.setTarget("_blank");
        configure.getElement().setAttribute("rel", "noopener");
        styleCtaAnchor(configure);
        HomeUi.routeToLauncherOnDesktop(configure, "config-card");

        Button dismiss = new Button(VaadinIcon.CLOSE_SMALL.create(),
                e -> alertBannerSlot.setVisible(false));
        dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        dismiss.setAriaLabel("Dismiss");

        banner.add(icon, textBlock, configure, dismiss);
        alertBannerSlot.removeAll();
        alertBannerSlot.add(banner);
        alertBannerSlot.setVisible(true);
    }


    private void renderUpdateBanner(ReleaseCheckService.LatestRelease release) {
        Div banner = new Div();
        banner.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("flex-wrap", "wrap")
                .set("gap", "0.75rem")
                .set("padding", "0.75rem 1rem")
                .set("background-color", "var(--lumo-contrast-5pct)")
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)");

        Icon icon = VaadinIcon.ARROW_UP.create();
        icon.getStyle()
                .set("width", "var(--lumo-icon-size-s)")
                .set("height", "var(--lumo-icon-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");

        Div textBlock = new Div();
        textBlock.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "0.15rem")
                .set("flex", "1 1 auto")
                .set("min-width", "0");

        Span headline = new Span("Update available · " + release.tagName());
        headline.getStyle()
                .set("font-weight", "600")
                .set("color", "var(--lumo-body-text-color)");

        Div detailLine = new Div();
        detailLine.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("align-items", "center")
                .set("gap", "0.4rem")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");

        String releaseTitle = release.name();
        if (releaseTitle != null && !releaseTitle.isBlank()
                && !releaseTitle.equalsIgnoreCase(release.tagName())) {
            detailLine.add(new Span(releaseTitle));
        }
        if (release.publishedAtEpochMs() != null) {
            if (detailLine.getElement().getChildCount() > 0) {
                detailLine.add(HomeUi.divider());
            }
            detailLine.add(new Span("Released " + HomeUi.relativeTime(release.publishedAtEpochMs())));
        }
        if (detailLine.getElement().getChildCount() > 0) {
            detailLine.add(HomeUi.divider());
        }
        detailLine.add(new Span("Download the desktop installer for your platform."));

        textBlock.add(headline, detailLine);

        Anchor downloadLink = new Anchor(ReleaseCheckService.DOWNLOAD_GUIDE, "Download");
        downloadLink.setTarget("_blank");
        downloadLink.getElement().setAttribute("rel", "noopener");
        downloadLink.getStyle()
                .set("color", "var(--lumo-primary-contrast-color)")
                .set("background-color", "var(--lumo-primary-color)")
                .set("padding", "0.35rem 0.8rem")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("text-decoration", "none")
                .set("font-weight", "500")
                .set("font-size", "var(--lumo-font-size-s)");

        Anchor notesLink = new Anchor(release.htmlUrl(), "Release notes");
        notesLink.setTarget("_blank");
        notesLink.getElement().setAttribute("rel", "noopener");
        notesLink.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("text-decoration", "none")
                .set("font-size", "var(--lumo-font-size-s)");

        Button dismiss = new Button(VaadinIcon.CLOSE_SMALL.create(),
                e -> updateBannerSlot.setVisible(false));
        dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        dismiss.setAriaLabel("Dismiss");

        banner.add(icon, textBlock, downloadLink, notesLink, dismiss);
        updateBannerSlot.removeAll();
        updateBannerSlot.add(banner);
        updateBannerSlot.setVisible(true);
    }


}
