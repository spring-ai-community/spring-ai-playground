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
import org.springaicommunity.playground.observability.VectorOperationLog;
import org.springaicommunity.playground.observability.VectorOperationLog.VectorOperation;
import org.springaicommunity.playground.webui.observability.ObservabilityDashboardSidebar.DashboardEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VectorTabRecentSearchesTest extends SpringBrowserlessTest {

    @Autowired
    private VectorOperationLog vectorOperationLog;

    @Test
    void theVectorDashboardCountsLoggedOperationsAndListsTheRecentSearchText() {
        this.vectorOperationLog.record(new VectorOperation(System.currentTimeMillis(), 21, VectorOperationLog.QUERY,
                "simple", 4L, 0.5, 2, null, false), "when does the wifi password rotate");
        ObservabilityView view = navigate(ObservabilityView.class);

        $(ObservabilityDashboardSidebar.class, view).first()
                .select(new DashboardEntry("vector", "Vector Database", VaadinIcon.SEARCH_PLUS));
        roundTrip();

        String dashboardText = $(VectorTab.class, view).single().getElement().getTextRecursively();
        assertThat(dashboardText).contains("Recent searches")
                .contains("2 docs · 21 ms · when does the wifi password rotate")
                .doesNotContain("No searches yet.")
                .containsPattern("[1-9][0-9]* vector ops");
    }
}
