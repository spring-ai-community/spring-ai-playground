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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.service.management.ManagementStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpManagementStatusPanelTest {

    @Test
    void anActiveStatusListsToolsCategoriesAndApproval() {
        ManagementStatus status = new ManagementStatus(true, null, Set.of("monitor", "expose"),
                ApprovalMode.DESTRUCTIVE, 12);

        assertThat(McpManagementStatusPanel.describe(status))
                .isEqualTo("AI management tools: on, 12 tools, categories expose, monitor, human approval destructive");
        assertThat(McpManagementStatusPanel.hint(status)).contains("bearer token", "Observability");
    }

    @Test
    void anInactiveStatusExplainsWhy() {
        ManagementStatus status = ManagementStatus.inactive("no bearer token on the built-in server",
                ApprovalMode.DESTRUCTIVE);

        assertThat(McpManagementStatusPanel.describe(status)).contains("off", "no bearer token");
        assertThat(McpManagementStatusPanel.hint(status)).contains("Require a bearer token");
    }

    @Test
    void disabledApprovalIsCalledOut() {
        ManagementStatus status = new ManagementStatus(true, null, Set.of("expose"), ApprovalMode.NONE, 3);

        assertThat(McpManagementStatusPanel.hint(status)).contains("Approval is disabled");
    }

}
