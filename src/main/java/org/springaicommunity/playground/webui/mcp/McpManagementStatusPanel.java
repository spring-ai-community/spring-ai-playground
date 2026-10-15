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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;
import org.springaicommunity.playground.service.management.ManagementStatus;
import org.springaicommunity.playground.service.management.ManagementToolRegistrar;

import java.util.Locale;

public class McpManagementStatusPanel {

    private final ManagementToolRegistrar registrar;

    public McpManagementStatusPanel(ManagementToolRegistrar registrar) {
        this.registrar = registrar;
    }

    public Component build() {
        ManagementStatus status = this.registrar.status();
        Span state = new Span(describe(status));
        state.getElement().setAttribute("data-testid", "management-status");
        boolean unsafe = status.active() && status.approval() == ApprovalMode.NONE;
        state.getStyle().set("font-weight", "600")
                .set("color", unsafe ? "var(--lumo-error-text-color)" : "var(--lumo-body-text-color)");
        Span hint = new Span(hint(status));
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        Div root = new Div(state, hint);
        root.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px");
        return root;
    }

    static String describe(ManagementStatus status) {
        if (!status.active()) {
            return "AI management tools: off (" + status.reason() + ")";
        }
        return "AI management tools: on, " + status.toolCount() + " tools, categories "
                + String.join(", ", status.categories().stream().sorted().toList()) + ", human approval "
                + status.approval().name().toLowerCase(Locale.ROOT);
    }

    static String hint(ManagementStatus status) {
        if (status.active()) {
            return status.approval() == ApprovalMode.NONE
                    ? "Approval is disabled: destructive tools run without asking. Only for trusted automation."
                    : "External MCP clients that present the bearer token can list, expose and monitor MCP "
                            + "servers here. Every call is recorded in Observability, Safety. Set by "
                            + "spring.ai.playground.management.*.";
        }
        return "Require a bearer token above to let external AI clients manage this app. Settings: "
                + "spring.ai.playground.management.*.";
    }

}
