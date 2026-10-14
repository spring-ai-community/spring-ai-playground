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

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.tabs.TabSheetVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpNotificationStore;
import org.springaicommunity.playground.service.mcp.risk.McpToolRiskEvaluator;
import org.springaicommunity.playground.webui.mcp.inspector.ElicitationTab;
import org.springaicommunity.playground.webui.mcp.inspector.InspectorHelpers;
import org.springaicommunity.playground.webui.mcp.inspector.InspectorHelpers.ToolInfo;
import org.springaicommunity.playground.webui.mcp.inspector.NotificationsTab;
import org.springaicommunity.playground.webui.mcp.inspector.PingTab;
import org.springaicommunity.playground.webui.mcp.inspector.RootsTab;
import org.springaicommunity.playground.webui.mcp.inspector.SamplingTab;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.PromptPrimitive;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.ResourcePrimitive;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.ResourceTemplatePrimitive;
import org.springaicommunity.playground.webui.mcp.inspector.primitives.server.ToolPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@CssImport("./playground/mcp-inspector-styles.css")
public class McpServerInspectorView extends VerticalLayout {

    private final McpServerInfo serverInfo;
    private final McpClientService clientService;
    private final List<ToolInfo> allTools;
    private final List<ToolPrimitive> cards = new ArrayList<>();

    private final VerticalLayout cardsContainer = new VerticalLayout();
    private final VerticalLayout resourcesContainer = new VerticalLayout();
    private final VerticalLayout promptsContainer = new VerticalLayout();
    private final VerticalLayout pingContainer = new VerticalLayout();
    private final VerticalLayout notificationsContainer = new VerticalLayout();
    private final VerticalLayout rootsContainer = new VerticalLayout();
    private final VerticalLayout samplingContainer = new VerticalLayout();
    private final VerticalLayout elicitationContainer = new VerticalLayout();

    private final Tab toolsTab = tab(VaadinIcon.WRENCH, "Tools");
    private final Tab resourcesTab = tab(VaadinIcon.FILE_TEXT_O, "Resources");
    private final Tab promptsTab = tab(VaadinIcon.COMMENT_ELLIPSIS_O, "Prompts");
    private final Tab pingTab = tab(VaadinIcon.SPARK_LINE, "Ping");
    private final Tab notificationsTabRef = tab(VaadinIcon.BELL_O, "Notifications");
    private final Tab rootsTabRef = tab(VaadinIcon.FOLDER_O, "Roots");
    private final Tab samplingTabRef = tab(VaadinIcon.MAGIC, "Sampling");
    private final Tab elicitationTabRef = tab(VaadinIcon.QUESTION_CIRCLE_O, "Elicitation");

    private final TabSheet tabSheet = new TabSheet();
    private final Map<String, Notification> approvalPrompts = new LinkedHashMap<>();
    private final Map<String, ToolPrimitive> approvalCards = new LinkedHashMap<>();
    private Runnable approvalWatchUnsubscribe;

    private boolean resourcesLoaded = false;
    private boolean promptsLoaded = false;
    private boolean pingTabInitialized = false;
    private NotificationsTab notificationsTab;
    private RootsTab rootsTab;
    private SamplingTab samplingTab;
    private ElicitationTab elicitationTab;

    public McpServerInspectorView(McpServerInfo serverInfo, McpClientService clientService,
            List<McpSchema.Tool> tools, McpToolRiskEvaluator riskEvaluator) {
        this.serverInfo = Objects.requireNonNull(serverInfo);
        this.clientService = Objects.requireNonNull(clientService);
        this.allTools = tools.stream().map(InspectorHelpers::toToolInfo).toList();

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        for (VerticalLayout container : List.of(cardsContainer, resourcesContainer, promptsContainer, pingContainer, notificationsContainer, rootsContainer, samplingContainer, elicitationContainer)) {
            container.setPadding(false);
            container.setSpacing(false);
            container.setWidthFull();
            container.getStyle().set("padding", "0.8em 1em");
        }

        for (int i = 0; i < allTools.size(); i++) {
            ToolInfo info = allTools.get(i);
            McpToolRiskEvaluator.ToolRiskView risk = riskEvaluator.evaluateTool(serverInfo, info.name(),
                    info.description(), info.propertySchemas());
            ToolPrimitive card = new ToolPrimitive(info, i + 1, serverInfo, clientService, risk);
            cards.add(card);
            cardsContainer.add(card);
        }

        VerticalLayout toolsTabContent = new VerticalLayout(createToolbar(), cardsContainer);
        toolsTabContent.setPadding(false);
        toolsTabContent.setSpacing(false);
        toolsTabContent.setSizeFull();

        tabSheet.addClassName("inspector-tabsheet");
        tabSheet.addThemeVariants(TabSheetVariant.LUMO_BORDERED);
        tabSheet.setWidthFull();
        tabSheet.add(toolsTab, toolsTabContent);
        tabSheet.add(resourcesTab, resourcesContainer);
        tabSheet.add(promptsTab, promptsContainer);
        tabSheet.add(pingTab, pingContainer);
        tabSheet.add(notificationsTabRef, notificationsContainer);
        tabSheet.add(rootsTabRef, rootsContainer);
        tabSheet.add(samplingTabRef, samplingContainer);
        tabSheet.add(elicitationTabRef, elicitationContainer);

        tabSheet.addSelectedChangeListener(e -> {
            Tab selected = e.getSelectedTab();
            if (selected == resourcesTab && !resourcesLoaded) loadResources();
            else if (selected == promptsTab && !promptsLoaded) loadPrompts();
            else if (selected == pingTab && !pingTabInitialized) {
                pingTabInitialized = true;
                pingContainer.add(new PingTab(serverInfo, clientService));
            } else if (selected == notificationsTabRef && notificationsTab == null) {
                notificationsTab = new NotificationsTab(serverInfo, clientService);
                notificationsContainer.add(notificationsTab);
                notificationsTab.attachListeners(UI.getCurrent());
            } else if (selected == rootsTabRef && rootsTab == null) {
                rootsTab = new RootsTab(serverInfo, clientService);
                rootsContainer.add(rootsTab);
            } else if (selected == samplingTabRef && samplingTab == null) {
                samplingTab = new SamplingTab(serverInfo, clientService);
                samplingContainer.add(samplingTab);
                samplingTab.attachListeners(UI.getCurrent());
            } else if (selected == elicitationTabRef && elicitationTab == null) {
                elicitationTab = new ElicitationTab(serverInfo, clientService);
                elicitationContainer.add(elicitationTab);
                elicitationTab.attachListeners(UI.getCurrent());
            }
        });

        add(createHeader(), tabSheet);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        this.approvalWatchUnsubscribe = this.clientService.subscribePendingChange(this.serverInfo, () -> {
            try {
                ui.access(this::syncApprovalPrompts);
            } catch (RuntimeException ignore) {
            }
        });
        syncApprovalPrompts();
    }

    private void syncApprovalPrompts() {
        List<McpNotificationStore.PendingElicitation> pending =
                this.clientService.snapshotPendingElicitations(this.serverInfo);
        Set<String> pendingIds = pending.stream()
                .map(McpNotificationStore.PendingElicitation::id)
                .collect(Collectors.toSet());
        this.approvalPrompts.entrySet().removeIf(entry -> {
            if (pendingIds.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        this.approvalCards.entrySet().removeIf(entry -> {
            if (pendingIds.contains(entry.getKey())) return false;
            entry.getValue().clearApprovalRequest(entry.getKey());
            return true;
        });
        for (McpNotificationStore.PendingElicitation elicitation : pending) {
            if (this.approvalPrompts.containsKey(elicitation.id())
                    || this.approvalCards.containsKey(elicitation.id())) {
                continue;
            }
            Optional<ToolPrimitive> runningCard = this.cards.stream().filter(ToolPrimitive::isRunning).findFirst();
            if (runningCard.isPresent()) {
                runningCard.get().showApprovalRequest(elicitation,
                        () -> this.tabSheet.setSelectedTab(this.elicitationTabRef));
                this.approvalCards.put(elicitation.id(), runningCard.get());
            } else {
                this.approvalPrompts.put(elicitation.id(), openApprovalPrompt(elicitation));
            }
        }
    }

    private Notification openApprovalPrompt(McpNotificationStore.PendingElicitation elicitation) {
        Notification prompt = new Notification();
        prompt.setDuration(0);
        prompt.setPosition(Notification.Position.TOP_END);
        prompt.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
        Span text = new Span("Approval needed: " + elicitation.request().message());
        text.getStyle().set("max-width", "32em");
        Button review = new Button("Review", click -> {
            this.tabSheet.setSelectedTab(this.elicitationTabRef);
            prompt.close();
        });
        review.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        HorizontalLayout row = new HorizontalLayout(text);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        if (InspectorHelpers.requiresNoInput(elicitation.request())) {
            Button accept = new Button("Accept", click -> {
                this.clientService.completeElicitation(this.serverInfo, elicitation.id(),
                        new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()));
                prompt.close();
            });
            accept.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
            row.add(accept);
        }
        row.add(review);
        prompt.add(row);
        prompt.open();
        return prompt;
    }

    private Component createHeader() {
        H4 logo = new H4("MCP Inspector");
        logo.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");
        HorizontalLayout headerLayout = new HorizontalLayout(logo);
        headerLayout.setWidthFull();
        headerLayout.setPadding(true);
        headerLayout.setAlignItems(FlexComponent.Alignment.CENTER);
        headerLayout.setJustifyContentMode(FlexComponent.JustifyContentMode.START);
        return headerLayout;
    }

    private static Tab tab(VaadinIcon icon, String caption) {
        return new Tab(icon.create(), new Text(caption));
    }

    private void loadPrompts() {
        promptsLoaded = true;
        try {
            List<McpSchema.Prompt> prompts =
                    clientService.getPromptListAsOpt(serverInfo).orElseGet(List::of);
            for (McpSchema.Prompt p : prompts) {
                promptsContainer.add(new PromptPrimitive(p, serverInfo, clientService));
            }
            if (prompts.isEmpty()) {
                promptsContainer.add(InspectorHelpers.emptyState("No prompts exposed by this server."));
            }
        } catch (Exception ex) {
            promptsContainer.add(InspectorHelpers.emptyState("Failed to load prompts: " + ex.getMessage()));
        }
    }

    private void loadResources() {
        resourcesLoaded = true;
        try {
            List<McpSchema.Resource> resources =
                    clientService.getResourceListAsOpt(serverInfo).orElseGet(List::of);
            List<McpSchema.ResourceTemplate> templates =
                    clientService.getResourceTemplateListAsOpt(serverInfo).orElseGet(List::of);

            if (!resources.isEmpty()) {
                resourcesContainer.add(InspectorHelpers.sectionHeader("Resources", resources.size()));
                for (McpSchema.Resource res : resources) {
                    resourcesContainer.add(new ResourcePrimitive(res, serverInfo, clientService));
                }
            }
            if (!templates.isEmpty()) {
                resourcesContainer.add(InspectorHelpers.sectionHeader("Resource Templates", templates.size()));
                for (McpSchema.ResourceTemplate tmpl : templates) {
                    resourcesContainer.add(new ResourceTemplatePrimitive(tmpl, serverInfo, clientService));
                }
            }
            if (resources.isEmpty() && templates.isEmpty()) {
                resourcesContainer.add(InspectorHelpers.emptyState(
                        "No resources or templates exposed by this server."));
            }
        } catch (Exception ex) {
            resourcesContainer.add(InspectorHelpers.emptyState("Failed to load resources: " + ex.getMessage()));
        }
    }

    private Component createToolbar() {
        TextField search = new TextField();
        search.setPlaceholder("Search tools by name or description…");
        search.setClearButtonVisible(true);
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setWidth("420px");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(150);
        search.addValueChangeListener(e -> applyFilter(e.getValue()));

        Span count = new Span(allTools.size() + " tool" + (allTools.size() == 1 ? "" : "s"));
        count.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");

        HorizontalLayout bar = new HorizontalLayout(search, count);
        bar.setWidthFull();
        bar.setPadding(false);
        bar.setSpacing(true);
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle().set("padding", "0 0 0.6em").set("gap", "0.8em");
        return bar;
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        for (ToolPrimitive card : cards) {
            card.setVisible(card.matches(needle));
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (this.approvalWatchUnsubscribe != null) {
            this.approvalWatchUnsubscribe.run();
            this.approvalWatchUnsubscribe = null;
        }
        this.approvalPrompts.values().forEach(Notification::close);
        this.approvalPrompts.clear();
        this.approvalCards.clear();
        try {
            if (notificationsTab != null) notificationsTab.detach();
            if (samplingTab != null) samplingTab.detach();
            if (elicitationTab != null) elicitationTab.detach();
        } catch (RuntimeException ignore) {}
        super.onDetach(detachEvent);
    }
}
