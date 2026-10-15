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
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget.Origin;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.util.Objects;

public class TargetsDialog extends Dialog {

    private final DeployTargetService deployTargetService;
    private final Runnable onChanged;
    private final Grid<DeployTarget> grid = new Grid<>();
    private final TextField nameField = new TextField("Name");
    private final TextField dockerHostField = new TextField("Docker host");
    private final TextField publicHostField = new TextField("Public host (optional)");

    public TargetsDialog(DeployTargetService deployTargetService, Runnable onChanged) {
        this.deployTargetService = deployTargetService;
        this.onChanged = onChanged;
        setHeaderTitle("Targets");
        setWidth("960px");

        Span intro = new Span("Where Shipyard servers run. Each server remembers the target it last ran on; status, "
                + "logs and connect snippets follow it. Remote Docker contexts are picked up automatically. This app "
                + "stores no keys: ssh:// uses your ssh-agent and ~/.ssh/config, TLS lives in the docker context.");
        intro.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");

        this.grid.addColumn(DeployTarget::name).setHeader("Name").setAutoWidth(true).setFlexGrow(0);
        this.grid.addColumn(DeployTarget::where).setHeader("Docker").setFlexGrow(2);
        this.grid.addColumn(DeployTarget::endpointHost).setHeader("Clients connect to").setFlexGrow(1);
        this.grid.addColumn(target -> target.origin().label()).setHeader("From").setAutoWidth(true).setFlexGrow(0);
        this.grid.addComponentColumn(this::actions).setHeader("").setWidth("250px").setFlexGrow(0);
        this.grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        this.grid.setAllRowsVisible(true);
        refreshGrid();

        this.nameField.setPlaceholder("build-box");
        this.dockerHostField.setPlaceholder("ssh://user@host or tcp://host:2376");
        this.dockerHostField.setHelperText("Same value as DOCKER_HOST");
        this.publicHostField.setPlaceholder("defaults to the docker host name");
        this.publicHostField.setHelperText("Address clients use to reach published ports");
        Button addButton = new Button("Add target", e -> addTarget());
        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout form = new HorizontalLayout(this.nameField, this.dockerHostField, this.publicHostField,
                addButton);
        form.setAlignItems(HorizontalLayout.Alignment.BASELINE);
        form.setWidthFull();
        form.expand(this.dockerHostField, this.publicHostField);

        Span configured = new Span("Targets can also be set in configuration under "
                + DeployTargetService.CONFIGURED_TARGETS_PROPERTY + "; those are read-only here.");
        configured.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)");

        VerticalLayout layout = new VerticalLayout(intro, this.grid, form, configured);
        layout.setPadding(false);
        add(layout);
        getFooter().add(new Button("Close", e -> close()));
    }

    private HorizontalLayout actions(DeployTarget target) {
        Span result = new Span();
        result.getStyle().set("font-size", "var(--lumo-font-size-s)");
        Button check = new Button("Check", e -> {
            DockerAvailability availability = this.deployTargetService.check(target);
            result.setText(availability.daemonUp() ? "Docker " + Objects.toString(availability.version(), "")
                    : availability.cliFound() ? "Unreachable" : "No Docker CLI");
            result.getStyle().set("color", availability.daemonUp()
                    ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        });
        check.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        HorizontalLayout actions = new HorizontalLayout(check, result);
        actions.setAlignItems(HorizontalLayout.Alignment.CENTER);
        if (target.origin() == Origin.ADDED) {
            Button remove = new Button("Remove", e -> removeTarget(target));
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL,
                    ButtonVariant.LUMO_ERROR);
            actions.addComponentAtIndex(1, remove);
        }
        return actions;
    }

    private void addTarget() {
        try {
            DeployTarget added = this.deployTargetService.addTarget(this.nameField.getValue().strip(),
                    this.dockerHostField.getValue().strip(), this.publicHostField.getValue().strip());
            this.nameField.clear();
            this.dockerHostField.clear();
            this.publicHostField.clear();
            refreshGrid();
            this.onChanged.run();
            VaadinUtils.showInfoNotification("Added target '" + added.name() + "'");
        } catch (RuntimeException e) {
            VaadinUtils.showErrorNotification(e.getMessage());
        }
    }

    private void removeTarget(DeployTarget target) {
        try {
            this.deployTargetService.removeTarget(target.name());
            refreshGrid();
            this.onChanged.run();
        } catch (RuntimeException e) {
            VaadinUtils.showErrorNotification(e.getMessage());
        }
    }

    private void refreshGrid() {
        this.grid.setItems(this.deployTargetService.targets());
    }

}
