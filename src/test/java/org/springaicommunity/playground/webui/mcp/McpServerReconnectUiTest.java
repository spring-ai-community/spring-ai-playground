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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.McpRegistrationService;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.McpServerInfoService;
import org.springaicommunity.playground.service.mcp.McpServerOverviewService.ServerOverview;
import org.springaicommunity.playground.service.mcp.catalog.McpCategoryService;
import org.springaicommunity.playground.service.mcp.catalog.McpTagSuggestionService;
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springaicommunity.playground.service.mcp.client.McpClientService.ServerStatus;
import org.springaicommunity.playground.service.mcp.client.McpTransportType;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationInput;
import org.springaicommunity.playground.service.mcp.risk.McpRegistrationRiskPreview.RegistrationPreview;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.boot.test.context.SpringBootTest;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class McpServerReconnectUiTest extends SpringBrowserlessTest {

    private static final String SERVER_NAME = "wiki";

    private final McpServerInfoService infoService = mock(McpServerInfoService.class);
    private final McpClientService clientService = mock(McpClientService.class);
    private final McpRegistrationService registrationService = mock(McpRegistrationService.class);
    private final PropertyChangeSupport events = new PropertyChangeSupport(this);
    private final List<PropertyChangeEvent> received = new CopyOnWriteArrayList<>();

    private McpServerConfigView mountSavedServer() {
        McpServerInfo saved = new McpServerInfo(McpTransportType.STREAMABLE_HTTP, SERVER_NAME, "a wiki", 1L, 1L,
                "{\"url\":\"http://127.0.0.1:18382\",\"endpoint\":\"/mcp\"}", McpServerInfo.DEFAULT_CATEGORY, Set.of(),
                1_000L);
        when(infoService.getMcpServerInfos()).thenReturn(Map.of(McpTransportType.STREAMABLE_HTTP, List.of(saved)));
        when(infoService.findByServerName(SERVER_NAME)).thenReturn(Optional.of(saved));
        McpRegistrationRiskPreview riskPreview = mock(McpRegistrationRiskPreview.class);
        when(riskPreview.preview(any(RegistrationInput.class)))
                .thenReturn(new RegistrationPreview(RiskLevel.L5, null, Map.of(), false));
        this.events.addPropertyChangeListener(this.received::add);
        McpServerConfigView view = new McpServerConfigView(saved, infoService, clientService,
                mock(McpCategoryService.class), mock(McpTagSuggestionService.class), riskPreview,
                registrationService, this.events);
        UI.getCurrent().add(view);
        return view;
    }

    private Button reconnectButton(McpServerConfigView view) {
        return $(Button.class, view).withCondition(button -> "Reconnect".equals(button.getText())).single();
    }

    @Test
    void clickingReconnectStartsTheSavedConnectionAndRefreshesTheScreen() throws InterruptedException {
        McpServerConfigView view = mountSavedServer();
        when(registrationService.reconnect(SERVER_NAME)).thenReturn(new ServerOverview(SERVER_NAME,
                McpTransportType.STREAMABLE_HTTP, ServerStatus.OK, null, null, Set.of(), RiskLevel.L2, "EXAMPLE",
                Set.of(), "a wiki", false, true));
        when(clientService.getToolListAsOpt(any())).thenReturn(Optional.of(List.of()));

        test(reconnectButton(view)).click();
        List<PropertyChangeEvent> events = pollUntil(() -> {
            VaadinSession session = VaadinSession.getCurrent();
            session.getService().runPendingAccessTasks(session);
            return this.received;
        }, list -> !list.isEmpty());

        verify(registrationService).reconnect(SERVER_NAME);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getPropertyName()).isEqualTo(McpServerView.MCP_CONNECTION_RECONNECT_EVENT);
            assertThat(((McpServerInfo) event.getNewValue()).serverName()).isEqualTo(SERVER_NAME);
        });
        assertThat(reconnectButton(view).isEnabled()).isTrue();
        assertThat($(Notification.class).all()).anyMatch(notification ->
                String.valueOf(notification.getElement().getProperty("text")).contains("Reconnected 'wiki' - 0 tool(s)."));
    }

    @Test
    void aFailedReconnectReportsTheErrorAndStillRefreshesTheScreen() throws InterruptedException {
        McpServerConfigView view = mountSavedServer();
        when(registrationService.reconnect(SERVER_NAME)).thenReturn(new ServerOverview(SERVER_NAME,
                McpTransportType.STREAMABLE_HTTP, ServerStatus.ERROR, "Connection refused", null, Set.of(),
                RiskLevel.L5, "EXAMPLE", Set.of(), "a wiki", false, false));

        test(reconnectButton(view)).click();
        pollUntil(() -> {
            VaadinSession session = VaadinSession.getCurrent();
            session.getService().runPendingAccessTasks(session);
            return this.received;
        }, list -> !list.isEmpty());

        assertThat($(Notification.class).all()).anyMatch(notification ->
                String.valueOf(notification.getElement().getProperty("text")).contains("Reconnect failed: Connection refused"));
        assertThat(reconnectButton(view).isEnabled()).isTrue();
    }

    private static <T> T pollUntil(Supplier<T> supplier, Predicate<T> ready) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        T value = supplier.get();
        while (!ready.test(value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            value = supplier.get();
        }
        return value;
    }

}
