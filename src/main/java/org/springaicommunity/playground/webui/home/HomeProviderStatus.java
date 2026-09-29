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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.slf4j.Logger;
import org.springaicommunity.playground.service.chat.ProviderReadinessService;
import org.springaicommunity.playground.service.chat.ProviderReadinessService.ChatProviderStatus;
import org.springaicommunity.playground.service.chat.ProviderReadinessService.EmbeddingStatus;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class HomeProviderStatus extends Div {

    private static final Logger logger = LoggerFactory.getLogger(HomeProviderStatus.class);
    private static final String CONFIGURE_URL =
            "https://spring-ai-community.github.io/spring-ai-playground/getting-started/";

    private final ProviderReadinessService readinessService;

    private Div chatReadinessDot;
    private Span chatReadinessLabel;
    private Span embeddingDimensionsLabel;

    HomeProviderStatus(ProviderReadinessService readinessService) {
        this.readinessService = readinessService;

        getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("gap", "0.6rem")
                .set("flex", "1 1 auto")
                .set("min-width", "0");
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        render();
        UI ui = event.getUI();
        probeChatReadiness(ui);
        probeEmbeddingDimensions(ui);
    }

    private void render() {
        removeAll();
        add(buildChatPill());
        Div embeddingPill = buildEmbeddingPill();
        if (embeddingPill != null) {
            add(embeddingPill);
        }
    }

    private Div buildChatPill() {
        Div pill = pillContainer();
        ChatProviderStatus status = readinessService.chatProvider();

        if (!status.configured()) {
            pill.add(statusDot("var(--lumo-error-color)"));
            Span warn = new Span("No model provider configured");
            warn.getStyle().set("font-weight", "500");
            pill.add(warn);
            Anchor configureLink = new Anchor(CONFIGURE_URL, "Set up →");
            configureLink.setTarget("_blank");
            configureLink.getElement().setAttribute("rel", "noopener");
            configureLink.getStyle()
                    .set("color", "var(--lumo-primary-text-color)")
                    .set("text-decoration", "none")
                    .set("font-weight", "500");
            HomeUi.routeToLauncherOnDesktop(configureLink, "config-card");
            pill.add(configureLink);
            this.chatReadinessDot = null;
            this.chatReadinessLabel = null;
            return pill;
        }

        this.chatReadinessDot = statusDot("var(--lumo-contrast-30pct)");
        pill.add(this.chatReadinessDot);

        Span providerLabel = new Span(status.provider());
        providerLabel.getStyle().set("font-weight", "600");
        pill.add(providerLabel);

        String model = status.model();
        if (model != null && !model.isBlank()) {
            pill.add(HomeUi.divider(), HomeUi.mutedLabel(model));
        }

        this.chatReadinessLabel = HomeUi.mutedLabel("Checking…");
        pill.add(HomeUi.divider(), this.chatReadinessLabel);
        return pill;
    }

    private Div buildEmbeddingPill() {
        Optional<EmbeddingStatus> embedding = readinessService.embeddingProvider();
        if (embedding.isEmpty()) return null;
        Div pill = pillContainer();
        pill.add(statusDot("var(--lumo-primary-color)"));

        Span label = new Span("Embedding");
        label.getStyle().set("font-weight", "600");
        pill.add(label);

        String model = embedding.get().model();
        if (model != null && !model.isBlank()) {
            pill.add(HomeUi.divider(), HomeUi.mutedLabel(model));
        }

        this.embeddingDimensionsLabel = HomeUi.mutedLabel(readinessService.cachedEmbeddingDimensions()
                .map(dimensions -> dimensions + "d").orElse("…d"));
        pill.add(HomeUi.divider(), this.embeddingDimensionsLabel);
        return pill;
    }

    private void probeChatReadiness(UI ui) {
        if (!readinessService.chatProvider().configured()) return;
        CompletableFuture.supplyAsync(readinessService::isChatProviderReady)
                .thenAccept(ready -> ui.access(() -> applyChatReadiness(ready)))
                .exceptionally(ex -> {
                    logger.debug("chat readiness probe failed", ex);
                    ui.access(() -> applyChatReadiness(false));
                    return null;
                });
    }

    private void applyChatReadiness(boolean ready) {
        if (chatReadinessDot == null || chatReadinessLabel == null) return;
        chatReadinessDot.getStyle().set("background-color",
                ready ? "var(--lumo-success-color)" : "var(--lumo-error-color)");
        chatReadinessLabel.setText(ready ? "Ready" : "Not reachable");
    }

    private void probeEmbeddingDimensions(UI ui) {
        if (readinessService.cachedEmbeddingDimensions().isPresent() || readinessService.embeddingProbeAttempted())
            return;
        if (readinessService.embeddingProvider().isEmpty()) return;
        CompletableFuture.supplyAsync(readinessService::probeEmbeddingDimensions).thenAccept(dims ->
                ui.access(() -> {
                    if (embeddingDimensionsLabel != null) {
                        embeddingDimensionsLabel.setText(dims.map(value -> value + "d").orElse("dim n/a"));
                    }
                }));
    }

    private static Div pillContainer() {
        Div pill = new Div();
        pill.getStyle()
                .set("display", "inline-flex")
                .set("flex-wrap", "wrap")
                .set("max-width", "100%")
                .set("align-items", "center")
                .set("gap", "0.55rem")
                .set("padding", "0.4rem 0.9rem")
                .set("border-radius", "999px")
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("background-color", "var(--lumo-base-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("width", "fit-content");
        return pill;
    }

    private static Div statusDot(String color) {
        Div dot = new Div();
        dot.getStyle()
                .set("width", "8px")
                .set("height", "8px")
                .set("border-radius", "50%")
                .set("background-color", color)
                .set("flex-shrink", "0");
        return dot;
    }

}
