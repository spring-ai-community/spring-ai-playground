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
package org.springaicommunity.playground.webui.chat;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.analytics.UsageAnalyticsService;
import org.springaicommunity.playground.service.chat.ChatDocumentIntakeService;
import org.springaicommunity.playground.service.chat.ChatExportService;
import org.springaicommunity.playground.service.chat.ChatHistory;
import org.springaicommunity.playground.service.chat.ChatHistoryService;
import org.springaicommunity.playground.service.chat.ChatService;
import org.springaicommunity.playground.service.chat.ChatSessionService;
import org.springaicommunity.playground.service.chat.ChatSessionService.NewChatRequest;
import org.springaicommunity.playground.service.chat.ChatSessionService.PresetToolMatch;
import org.springaicommunity.playground.service.chat.ChatStreamRegistry;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetCatalog.Preset;
import org.springaicommunity.playground.service.chat.ChatSystemPromptPresetService;
import org.springaicommunity.playground.service.chat.ChatSystemPromptTemplateRenderer;
import org.springaicommunity.playground.service.chat.ChatToolPreferences;
import org.springaicommunity.playground.service.chat.ChatToolResolver;
import org.springaicommunity.playground.service.chat.ChatTurnService;
import org.springaicommunity.playground.service.chat.OllamaModelDownloadService;
import org.springaicommunity.playground.service.chat.VisionCapabilityService;
import org.springaicommunity.playground.service.tool.ChatImageStore;
import org.springaicommunity.playground.service.tool.ConversationFileUploadStore;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.webui.PersistentUiDataStorage;
import org.springaicommunity.playground.webui.SpringAiPlaygroundAppLayout;
import org.springaicommunity.playground.webui.UsageEventTracker;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.ContentWorkspaceView;
import org.springaicommunity.playground.webui.common.WorkspaceSettingsDrawer;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.beans.PropertyChangeSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.springaicommunity.playground.webui.VaadinUtils.styledButton;

@SpringComponent
@UIScope
@AnonymousAllowed
@CssImport("./playground/chat-styles.css")
@Route(value = "agentic-chat", layout = SpringAiPlaygroundAppLayout.class)
public class ChatView extends ContentWorkspaceView implements BeforeEnterObserver, HasDynamicTitle {

    public static final String CHAT_HISTORY_CHANGE_EVENT = "CHAT_HISTORY_CHANGE_EVENT";
    public static final String CHAT_HISTORY_SELECT_EVENT = "CHAT_HISTORY_SELECT_EVENT";
    public static final String CHAT_HISTORY_EMPTY_EVENT = "CHAT_HISTORY_EMPTY_EVENT";

    private static final String DEFAULT_PAGE_TITLE = "Agentic Chat";

    private final PersistentUiDataStorage persistentUiDataStorage;
    private final ChatService chatService;
    private final Consumer<ChatHistory> completeChatHistoryConsumer;
    private final ChatHistoryService chatHistoryService;
    private final ChatTurnService chatTurnService;
    private final ChatToolResolver chatToolResolver;
    private final ToolSpecService toolSpecService;
    private final ChatSessionService chatSessionService;
    private final ToolActivationCalculator toolActivationCalculator;
    private final ChatExportService chatExportService;
    private final SpringAiPlaygroundOptions playgroundOptions;
    private final ChatClientActionRegistry clientActionRegistry;
    private final ConversationFileUploadStore fileUploadStore;
    private final ChatImageStore imageStore;
    private final ChatDocumentIntakeService documentIntakeService;
    private final VisionCapabilityService visionCapabilityService;
    private final ChatSystemPromptPresetService chatSystemPromptPresetService;
    private final ChatSystemPromptTemplateRenderer chatSystemPromptTemplateRenderer;
    private final OllamaModelDownloadService ollamaModelDownloadService;
    private final UsageAnalyticsService usageAnalyticsService;
    private final UsageEventTracker usageEventTracker;
    private final ChatStreamRegistry chatStreamRegistry;
    private final ChatHistoryView chatHistoryView;
    private final WorkspaceSettingsDrawer settingsDrawer;
    private ChatModelSettingView chatModelSettingView;
    private ChatContentView chatContentView;
    private String pageTitle = DEFAULT_PAGE_TITLE;

    public ChatView(PersistentUiDataStorage persistentUiDataStorage, ChatService chatService,
            ChatHistoryService chatHistoryService, ChatTurnService chatTurnService,
            ChatToolResolver chatToolResolver,
            ToolSpecService toolSpecService, ChatSessionService chatSessionService,
            ToolActivationCalculator toolActivationCalculator,
            ChatExportService chatExportService,
            ChatSystemPromptPresetService chatSystemPromptPresetService,
            ChatSystemPromptTemplateRenderer chatSystemPromptTemplateRenderer,
            OllamaModelDownloadService ollamaModelDownloadService,
            SpringAiPlaygroundOptions playgroundOptions,
            ChatClientActionRegistry clientActionRegistry, ConversationFileUploadStore fileUploadStore,
            ChatImageStore imageStore, ChatDocumentIntakeService documentIntakeService,
            VisionCapabilityService visionCapabilityService, UsageAnalyticsService usageAnalyticsService,
            UsageEventTracker usageEventTracker, ChatStreamRegistry chatStreamRegistry) {
        this.persistentUiDataStorage = persistentUiDataStorage;
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.chatTurnService = chatTurnService;
        this.chatToolResolver = chatToolResolver;
        this.toolSpecService = toolSpecService;
        this.chatSessionService = chatSessionService;
        this.toolActivationCalculator = toolActivationCalculator;
        this.chatExportService = chatExportService;
        this.chatSystemPromptPresetService = chatSystemPromptPresetService;
        this.chatSystemPromptTemplateRenderer = chatSystemPromptTemplateRenderer;
        this.ollamaModelDownloadService = ollamaModelDownloadService;
        this.playgroundOptions = playgroundOptions;
        this.clientActionRegistry = clientActionRegistry;
        this.fileUploadStore = fileUploadStore;
        this.imageStore = imageStore;
        this.documentIntakeService = documentIntakeService;
        this.visionCapabilityService = visionCapabilityService;
        this.usageAnalyticsService = usageAnalyticsService;
        this.usageEventTracker = usageEventTracker;
        this.chatStreamRegistry = chatStreamRegistry;

        PropertyChangeSupport chatHistoryChangeSupport = new PropertyChangeSupport(this);
        chatHistoryChangeSupport.addPropertyChangeListener(CHAT_HISTORY_SELECT_EVENT,
                event -> this.changeChatContent((ChatHistory) event.getNewValue()));
        chatHistoryChangeSupport.addPropertyChangeListener(CHAT_HISTORY_EMPTY_EVENT, event -> {
            if ((boolean) event.getNewValue())
                addNewChatContent();
        });
        this.completeChatHistoryConsumer =
                chatHistory -> chatHistoryChangeSupport.firePropertyChange(CHAT_HISTORY_CHANGE_EVENT, null,
                        chatHistoryService.updateChatHistory(chatHistory));

        this.chatHistoryView =
                new ChatHistoryView(persistentUiDataStorage, chatHistoryService, chatHistoryChangeSupport);
        chatHistoryChangeSupport.addPropertyChangeListener(CHAT_HISTORY_CHANGE_EVENT,
                event -> this.chatHistoryView.changeChatHistoryContent((ChatHistory) event.getNewValue()));
        chatHistoryChangeSupport.addPropertyChangeListener(CHAT_HISTORY_CHANGE_EVENT,
                event -> refreshPageTitle((ChatHistory) event.getNewValue()));

        configureSidebar(this.chatHistoryView, "History");

        Button newChatButton =
                styledButton("New Chat", VaadinIcon.CHAT.create(), event -> addNewChatContent());
        addHeaderAction(newChatButton);

        installConversationExportMenu();
        installPromptLibraryButton();

        this.settingsDrawer = installSettingsDrawer(VaadinIcon.COG_O, "Agentic Chat Setting",
                "Agentic Chat Setting");
        this.settingsDrawer.setBodyFactory(this::buildChatModelSettingView);
        this.settingsDrawer.setApplyButton("Apply & New Chat", this::applySettingsAndNewChat, false);

        addNewChatContent();
    }

    private void installConversationExportMenu() {
        var exportIcon = VaadinUtils.styledIcon(VaadinIcon.DOWNLOAD.create());
        exportIcon.setTooltipText("Export conversation");
        var exportMenu = getHeaderEndMenuBar().addItem(exportIcon).getSubMenu();
        exportMenu.addItem("Markdown (.md)", event -> exportCurrent("md"));
        exportMenu.addItem("Plain text (.txt)", event -> exportCurrent("txt"));
        exportMenu.addItem("JSON (.json)", event -> exportCurrent("json"));
        exportMenu.addItem("PDF (print)", event -> exportCurrent("pdf"));
    }

    private void exportCurrent(String format) {
        if (Objects.nonNull(this.chatContentView)) this.chatContentView.exportConversation(format);
    }

    private void installPromptLibraryButton() {
        var promptsIcon = VaadinUtils.styledIcon(VaadinIcon.CLIPBOARD_TEXT.create());
        promptsIcon.setTooltipText("Prompt Library");
        getHeaderEndMenuBar().addItem(promptsIcon, event -> new PromptLibraryDialog(
                this.chatSystemPromptPresetService, this.chatSystemPromptTemplateRenderer,
                this::applyPromptFromLibrary, this.toolSpecService::readiness, this.toolSpecService.defaultTools(),
                this.toolSpecService::riskLevelOf, this.toolSpecService::categoryOf).open());
    }

    private void applyPromptFromLibrary(Preset preset) {
        this.settingsDrawer.open();
        if (Objects.nonNull(this.chatModelSettingView)) this.chatModelSettingView.applyPreset(preset);
    }

    private ChatModelSettingView buildChatModelSettingView() {
        refreshDownloadedModelsCache();
        this.chatModelSettingView = new ChatModelSettingView(this.chatService.getModels(),
                this.chatContentView.getSystemPrompt(), this.chatContentView.getChatOption(),
                this.chatContentView.getExtraOptions(), this.chatService.getChatProvider(),
                this.chatSystemPromptPresetService, this.ollamaModelDownloadService, this.toolSpecService.defaultTools(),
                this.toolSpecService::riskLevelOf, this.toolSpecService::categoryOf,
                this.chatService.getDefaultMemoryWindow(),
                this::presetToolMissingKeys, enabled -> {
                    this.settingsDrawer.setApplyEnabled(enabled);
                    if (!enabled) trackPresetBlocked();
                });
        return this.chatModelSettingView;
    }

    private void refreshDownloadedModelsCache() {
        if (!this.ollamaModelDownloadService.isEnabled()) return;
        UI ui = VaadinUtils.getUi(this);
        CompletableFuture.runAsync(this.ollamaModelDownloadService::refreshLocalModels)
                .thenRun(() -> ui.access(() -> {
                    if (Objects.nonNull(this.chatModelSettingView)) this.chatModelSettingView.refreshModelItems();
                }));
    }

    private List<String> presetToolMissingKeys(String toolName) {
        return this.toolSpecService.getToolSpecAsOpt(toolName)
                .map(this.toolActivationCalculator::missingEnvVars)
                .orElse(List.of());
    }

    private void applySettingsAndNewChat() {
        if (Objects.isNull(this.chatModelSettingView)) {
            addNewChatContent();
            this.settingsDrawer.close();
            return;
        }
        if (!this.chatModelSettingView.validate()) {
            Notification.show("Fix the highlighted settings before applying.", 3000, Notification.Position.MIDDLE);
            return;
        }
        String model = this.chatModelSettingView.getChatOptions().getModel();
        if (!this.chatSessionService.isModelDownloaded(model)) {
            new ModelDownloadDialog(model, this.ollamaModelDownloadService, () -> {
                this.usageEventTracker.track(VaadinUtils.getUi(this), "model_downloaded",
                        Map.of("model", model, "via", "app"));
                if (Objects.nonNull(this.chatModelSettingView)) this.chatModelSettingView.refreshModelItems();
                applySettingsAndNewChat();
            }).open();
            return;
        }
        List<String> presetTools = this.chatModelSettingView.getSelectedPresetTools();
        if (presetTools.isEmpty()) {
            commitSettingsAndNewChat(this.chatModelSettingView.isActivePresetDynamicTools()
                    ? ChatToolPreferences.defaults().withDynamicTools(true) : ChatToolPreferences.defaults());
            return;
        }
        openPresetExposureDialog(this.chatSessionService.resolvePresetTools(presetTools));
    }

    private void commitSettingsAndNewChat(ChatToolPreferences toolPreferences) {
        startChat(this.chatSessionService.startChat(newChatRequest(toolPreferences)));
    }

    private NewChatRequest newChatRequest(ChatToolPreferences toolPreferences) {
        return new NewChatRequest(this.chatModelSettingView.getSystemPromptTextArea(),
                this.chatModelSettingView.getChatOptions(), this.chatModelSettingView.getChatExtraOptions(),
                toolPreferences);
    }

    private void startChat(ChatHistory chatHistory) {
        this.chatHistoryView.clearSelectHistory();
        changeChatContent(chatHistory);
        this.settingsDrawer.close();
        trackSettingsApplied();
    }

    private void trackSettingsApplied() {
        UI ui = VaadinUtils.getUi(this);
        this.usageEventTracker.track(ui, "model_selected", Map.of(
                "provider", this.chatService.getChatProvider().name().toLowerCase(Locale.ROOT),
                "model", this.chatModelSettingView.getChatOptions().getModel()));
        Preset preset = this.chatModelSettingView.getActivePreset();
        if (preset == null) return;
        this.usageEventTracker.track(ui, "preset_applied", Map.of(
                "preset_id", this.usageAnalyticsService.presetLabel(preset),
                "dynamic_tools", this.chatModelSettingView.isActivePresetDynamicTools(),
                "tool_count", this.chatModelSettingView.getSelectedPresetTools().size()));
    }

    private void trackPresetBlocked() {
        if (Objects.isNull(this.chatModelSettingView)) return;
        Preset preset = this.chatModelSettingView.getActivePreset();
        if (preset == null) return;
        this.usageEventTracker.track(VaadinUtils.getUi(this), "preset_blocked",
                Map.of("preset_id", this.usageAnalyticsService.presetLabel(preset)));
    }

    private void applyPresetExposureAndNewChat(List<ToolSpec> matched) {
        startChat(this.chatSessionService.startChatWithPresetTools(
                newChatRequest(ChatToolPreferences.defaults()), matched));
    }

    private void openPresetExposureDialog(PresetToolMatch match) {
        Dialog dialog = VaadinUtils.headerDialog("Apply preset tools");
        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.getStyle().set("gap", "var(--lumo-space-xs)");
        body.setMaxWidth("30rem");
        if (match.matched().isEmpty()) {
            body.add(new Span("This preset has no tools that can be exposed without setup, so the built-in "
                    + "MCP server is left unchanged and the chat starts with the current selection."));
        } else {
            body.add(new Span("Resets the built-in MCP server to expose exactly these "
                    + match.matched().size() + " tool(s):"));
            match.matched().forEach(spec -> {
                Span line = new Span(spec.name());
                line.getStyle().set("font-size", "var(--lumo-font-size-s)");
                body.add(line);
            });
        }
        if (!match.unmatched().isEmpty()) {
            Span skippedHeading = new Span("These need an API key and are skipped:");
            skippedHeading.getStyle().set("margin-top", "var(--lumo-space-s)")
                    .set("font-size", "var(--lumo-font-size-s)");
            body.add(skippedHeading);
            match.unmatched().forEach((name, issue) -> {
                Span line = new Span(name + " - " + issueText(issue));
                line.getStyle().set("color", "var(--lumo-error-text-color)")
                        .set("font-size", "var(--lumo-font-size-s)");
                body.add(line);
            });
        }
        dialog.add(body);
        Button cancel = new Button("Cancel", event -> dialog.close());
        Button apply = new Button("Apply", event -> {
            dialog.close();
            if (match.matched().isEmpty()) commitSettingsAndNewChat(ChatToolPreferences.defaults());
            else applyPresetExposureAndNewChat(match.matched());
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancel, apply);
        dialog.open();
    }

    private void addNewChatContent() {
        this.chatHistoryView.clearSelectHistory();
        changeChatContent(this.chatSessionService.newDefaultChat());
    }

    private void changeChatContent(ChatHistory chatHistory) {
        if (Objects.isNull(chatHistory))
            return;

        if (Objects.nonNull(this.chatContentView)
                && chatHistory.conversationId().equals(this.chatContentView.getConversationId()))
            return;

        this.chatContentView = new ChatContentView(this.chatService, this.chatHistoryService, chatHistory,
                this.completeChatHistoryConsumer, this.chatTurnService, this.chatToolResolver,
                this.toolSpecService, this.chatExportService,
                this.playgroundOptions, this.clientActionRegistry, this.fileUploadStore,
                this.imageStore, this.documentIntakeService, this.visionCapabilityService, this.usageAnalyticsService,
                this.usageEventTracker, this.chatStreamRegistry);
        ChatOptions chatOptions = chatHistory.chatOptions();
        String label = this.chatService.isChatModelAbsent() ? "No model provider configured"
                : String.format("%s: %s", this.chatService.getChatModelProvider(), chatOptions.getModel());
        this.pageTitle = pageTitleOf(chatHistory);
        UI ui = VaadinUtils.getUi(this);
        ui.access(() -> {
            setHeaderLabel(label);
            setContent(this.chatContentView);
            ui.getPage().getHistory().replaceState(null, conversationLocation(chatHistory.conversationId()));
            ui.getPage().setTitle(this.pageTitle);
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> convParam = event.getLocation().getQueryParameters().getParameters().get("conv");
        if (convParam == null || convParam.isEmpty()) {
            this.chatHistoryView.unpinConversation();
            syncLocationToCurrentConversation();
            return;
        }
        String convId = convParam.getFirst();
        if (convId == null || convId.isBlank()) return;
        ChatHistory existing = this.chatHistoryService.getChatHistory(convId);
        if (existing != null) {
            this.chatHistoryView.pinConversation(existing.conversationId());
            changeChatContent(existing);
        } else {
            Notification n = Notification.show(
                    "Conversation " + shortenId(convId) + " is not in active chat history - starting a fresh chat.",
                    6000, Notification.Position.TOP_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        }
    }

    private static String shortenId(String s) {
        if (s == null) return "";
        return s.length() <= 14 ? s : s.substring(0, 14);
    }

    @Override
    public String getPageTitle() {
        return this.pageTitle;
    }

    private void refreshPageTitle(ChatHistory chatHistory) {
        if (Objects.isNull(this.chatContentView)
                || !chatHistory.conversationId().equals(this.chatContentView.getConversationId()))
            return;
        String updatedTitle = pageTitleOf(chatHistory);
        if (updatedTitle.equals(this.pageTitle))
            return;
        UI ui = VaadinUtils.getUi(this);
        ui.access(() -> {
            this.pageTitle = updatedTitle;
            ui.getPage().setTitle(updatedTitle);
        });
    }

    private void syncLocationToCurrentConversation() {
        if (Objects.isNull(this.chatContentView))
            return;
        String location = conversationLocation(this.chatContentView.getConversationId());
        UI ui = VaadinUtils.getUi(this);
        ui.access(() -> ui.getPage().getHistory().replaceState(null, location));
    }

    private static String conversationLocation(String conversationId) {
        return "agentic-chat?conv=" + URLEncoder.encode(conversationId, StandardCharsets.UTF_8);
    }

    private static String pageTitleOf(ChatHistory chatHistory) {
        String title = chatHistory.title();
        return Objects.isNull(title) || title.isBlank() ? DEFAULT_PAGE_TITLE : title;
    }

    private static String issueText(ChatSessionService.PresetToolIssue issue) {
        return switch (issue) {
            case NOT_A_DEFAULT_TOOL -> "not a built-in default tool";
            case NOT_EXPOSED -> "un-checked in MCP exposure";
            case NEEDS_SETUP -> "needs setup (publish or API key)";
            case NOT_ENABLED -> "not enabled";
        };
    }
}
