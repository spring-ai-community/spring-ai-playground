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
package org.springaicommunity.playground.webui.observability;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.webui.observability.ObservabilityDashboardSidebar.DashboardEntry;
import org.springaicommunity.playground.webui.observability.components.BaseDashboardTab;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ObservabilityDashboardSweepTest extends SpringBrowserlessTest {

    private static final List<DashboardEntry> ALL_DASHBOARDS = List.of(
            new DashboardEntry("overview", "Overview", VaadinIcon.DASHBOARD),
            new DashboardEntry("agentic-chat", "Agentic Chat", VaadinIcon.CHAT),
            new DashboardEntry("tokens", "Tokens & Cost", VaadinIcon.MONEY),
            new DashboardEntry("llm", "AI Models", VaadinIcon.CUBES),
            new DashboardEntry("tools", "Tool Studio", VaadinIcon.TOOLS),
            new DashboardEntry("mcp", "MCP Servers", VaadinIcon.TOOLBOX),
            new DashboardEntry("mcp-primitives", "MCP Inspector", VaadinIcon.SEARCH),
            new DashboardEntry("safety", "Safety", VaadinIcon.SHIELD),
            new DashboardEntry("vector", "Vector Database", VaadinIcon.SEARCH_PLUS),
            new DashboardEntry("host", "Host", VaadinIcon.SERVER),
            new DashboardEntry("ollama", "Ollama", VaadinIcon.HARDDRIVE),
            new DashboardEntry("web-application", "Web Application", VaadinIcon.GLOBE_WIRE),
            new DashboardEntry("logs", "Logs", VaadinIcon.FILE_TEXT_O),
            new DashboardEntry("traces", "Traces", VaadinIcon.SITEMAP));

    @Test
    void selectingEveryDashboardEntryRendersItsTab() {
        ObservabilityView view = navigate(ObservabilityView.class);
        ObservabilityDashboardSidebar sidebar = $(ObservabilityDashboardSidebar.class, view).first();

        for (DashboardEntry entry : ALL_DASHBOARDS) {
            sidebar.select(entry);
            roundTrip();
            assertThat($(BaseDashboardTab.class, view).all())
                    .as("dashboard %s attached", entry.slug())
                    .isNotEmpty();
        }
    }

}
