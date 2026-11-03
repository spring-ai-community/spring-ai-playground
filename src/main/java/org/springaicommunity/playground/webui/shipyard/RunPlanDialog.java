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

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.springaicommunity.playground.service.mcp.bundle.BundleArtifactWriter;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.Kind;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.Line;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunPlan;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunRequest;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunResult;
import org.springaicommunity.playground.webui.VaadinUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RunPlanDialog extends Dialog {

    private final Map<String, HasValue<?, String>> envFields = new LinkedHashMap<>();
    private final Select<DeployTarget> targetSelect = new Select<>();
    private final Div targetChecks = new Div();
    private final Span portLine = new Span();
    private final PasswordField authTokenField =
            new PasswordField(DockerCliService.AUTH_TOKEN_ENV);
    private final TextField ollamaEndpointField = new TextField("Ollama endpoint for the container");
    private final Div modelChecks = new Div();
    private final TextArea commandPreview = new TextArea("Command");
    private final Button runButton = new Button("Run");
    private final BundleEntry entry;
    private final BundleManifest manifest;
    private final String baseImage;
    private final RunPlanner runPlanner;
    private String generatedToken;
    private RunPlan plan;

    public RunPlanDialog(BundleEntry entry, String baseImage, RunPlanner runPlanner, Runnable onStarted) {
        this.entry = entry;
        this.manifest = entry.manifest();
        this.baseImage = baseImage;
        this.runPlanner = runPlanner;
        setHeaderTitle("Run: " + entry.name());
        setWidth("640px");

        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);

        this.targetSelect.setLabel("Target");
        this.targetSelect.setItems(runPlanner.targets());
        this.targetSelect.setItemLabelGenerator(target -> target.name() + " - " + target.where());
        this.targetSelect.setWidthFull();
        this.targetSelect.setValue(runPlanner.currentTarget(entry.name()));
        if (runPlanner.targetConfigured(entry.name())) {
            this.targetSelect.setReadOnly(true);
            this.targetSelect.setHelperText("Set in configuration under "
                    + BundleExportService.CONFIGURED_SERVERS_PROPERTY + "[].target");
        } else {
            this.targetSelect.setHelperText("Add remote Docker hosts under Targets in the Shipyard header");
        }
        this.targetSelect.addValueChangeListener(e -> refreshTarget());
        layout.add(this.targetSelect, this.targetChecks);

        layout.add(planLine("Image", BundleArtifactWriter.localImage(this.manifest) + " (built from "
                + entry.dir().resolve("Dockerfile") + " on " + baseImage + ")"));
        layout.add(this.portLine);
        layout.add(planLine("State", "every start serves a fresh copy of the bundle; the bundle folder is never "
                + "written"));

        BundleManifest.Models models = this.manifest.requires().models();
        if (!models.modelFree()) {
            layout.add(sectionLabel("Models this server needs"), this.modelChecks);
        }
        if (BundleArtifactWriter.usesOllama(models)) {
            Optional<String> configuredOllama = runPlanner.configuredOllamaEndpoint();
            this.ollamaEndpointField.setValue(runPlanner.defaultOllamaEndpoint());
            this.ollamaEndpointField.setReadOnly(configuredOllama.isPresent());
            this.ollamaEndpointField.setHelperText(configuredOllama.isPresent()
                    ? configuredHelp(BundleArtifactWriter.OLLAMA_BASE_URL_OVERRIDE_ENV)
                    : "The container reaches Ollama on the machine it runs on through " + RunPlanner.CONTAINER_HOST
                            + "; change it to use another Ollama");
            this.ollamaEndpointField.setWidthFull();
            this.ollamaEndpointField.setValueChangeMode(ValueChangeMode.LAZY);
            this.ollamaEndpointField.addValueChangeListener(e -> refreshModelChecks());
            layout.add(this.ollamaEndpointField);
        }

        for (BundleManifest.EnvVar envVar : this.manifest.requires().env()) {
            if (envVar.secret()) {
                PasswordField field = new PasswordField(envVar.name());
                field.setHelperText(envVar.purpose() + " - injected at run time, never written to disk. "
                        + "Leave empty to pass this app's own value through");
                field.setWidthFull();
                this.envFields.put(envVar.name(), field);
                layout.add(field);
            } else {
                TextField field = new TextField(envVar.name());
                field.setHelperText(envVar.purpose());
                field.setWidthFull();
                this.envFields.put(envVar.name(), field);
                layout.add(field);
            }
        }
        this.authTokenField.setWidthFull();
        this.authTokenField.setValueChangeMode(ValueChangeMode.EAGER);
        this.authTokenField.addValueChangeListener(e -> refreshRunEnabled());
        layout.add(this.authTokenField);

        this.commandPreview.setReadOnly(true);
        this.commandPreview.setWidthFull();
        layout.add(this.commandPreview);
        add(layout);

        this.runButton.addClickListener(e -> {
            try {
                RunResult result = runPlanner.run(new RunRequest(entry.name(), target().name(), envValues(),
                        this.ollamaEndpointField.getValue(), this.authTokenField.getValue()));
                VaadinUtils.showInfoNotification("Container started on " + result.target().name() + ": "
                        + DockerCliService.containerName(entry.name()));
                close();
                onStarted.run();
            } catch (RuntimeException ex) {
                VaadinUtils.showErrorNotification(ex.getMessage());
            }
        });
        this.runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(this.runButton, new Button("Cancel", e -> close()));
        refreshTarget();
    }

    private DeployTarget target() {
        return Objects.requireNonNullElseGet(this.targetSelect.getValue(), DeployTarget::local);
    }

    private void refreshTarget() {
        DeployTarget target = target();
        this.plan = this.runPlanner.plan(this.entry, target, this.baseImage, this.ollamaEndpointField.getValue());
        this.portLine.setText("Port: " + this.plan.endpoint() + " -> 8282");
        stylePlanLine(this.portLine);

        this.targetChecks.removeAll();
        this.plan.targetLines().forEach(line -> this.targetChecks.add(render(line)));

        Optional<String> configuredToken = this.plan.configuredToken();
        if (configuredToken.isPresent()) {
            this.authTokenField.setValue(configuredToken.get());
            this.authTokenField.setReadOnly(true);
            this.authTokenField.setLabel(DockerCliService.AUTH_TOKEN_ENV);
            this.authTokenField.setHelperText(configuredHelp(DockerCliService.AUTH_TOKEN_ENV)
                    + " The connect snippets include it.");
        } else if (this.plan.tokenRequired()) {
            if (this.authTokenField.getValue().isBlank()) {
                this.generatedToken = RunPlanner.newToken();
                this.authTokenField.setValue(this.generatedToken);
            }
            this.authTokenField.setLabel(DockerCliService.AUTH_TOKEN_ENV);
            this.authTokenField.setHelperText("Required on a remote target: the port is published on "
                    + target.endpointHost() + ". Generated for you; the connect snippets include it");
        } else {
            if (this.generatedToken != null && this.generatedToken.equals(this.authTokenField.getValue())) {
                this.authTokenField.clear();
            }
            this.generatedToken = null;
            this.authTokenField.setLabel(DockerCliService.AUTH_TOKEN_ENV + " (optional)");
            this.authTokenField.setHelperText("Require this bearer token on /mcp; leave empty for open access");
        }

        this.commandPreview.setValue(this.plan.commandPreview());
        renderModelChecks(this.plan.modelLines());
        refreshRunEnabled();
    }

    private void refreshModelChecks() {
        renderModelChecks(this.runPlanner.modelLines(this.manifest, target(), this.ollamaEndpointField.getValue()));
    }

    private void renderModelChecks(List<Line> lines) {
        this.modelChecks.removeAll();
        lines.forEach(line -> this.modelChecks.add(render(line)));
    }

    private void refreshRunEnabled() {
        this.runButton.setEnabled(this.plan != null && this.plan.runnable(this.authTokenField.getValue()));
    }

    private Map<String, String> envValues() {
        Map<String, String> env = new LinkedHashMap<>();
        this.envFields.forEach((name, field) -> env.put(name, field.getValue()));
        return env;
    }

    private static String configuredHelp(String name) {
        return "Set in this app's configuration (" + name + ").";
    }

    private Component render(Line line) {
        return line.kind() == Kind.NOTE ? noteLine(line.text()) : checkLine(line.kind() == Kind.OK, line.text());
    }

    private HorizontalLayout checkLine(boolean ok, String text) {
        Icon icon = (ok ? VaadinIcon.CHECK_CIRCLE : VaadinIcon.WARNING).create();
        icon.setSize("var(--lumo-icon-size-s)");
        icon.getStyle().set("flex", "none")
                .set("color", ok ? "var(--lumo-success-color)" : "var(--lumo-warning-color, #c77800)");
        Span label = new Span(text);
        label.getStyle().set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout line = new HorizontalLayout(icon, label);
        line.setAlignItems(HorizontalLayout.Alignment.CENTER);
        line.getStyle().set("gap", "var(--lumo-space-xs)");
        return line;
    }

    private Span noteLine(String text) {
        Span note = new Span(text);
        note.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("display", "block")
                .set("color", "var(--lumo-secondary-text-color)");
        return note;
    }

    private Span sectionLabel(String text) {
        Span label = new Span(text);
        label.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "700")
                .set("text-transform", "uppercase").set("letter-spacing", "0.05em")
                .set("color", "var(--lumo-secondary-text-color)");
        return label;
    }

    private Span planLine(String label, String value) {
        Span span = new Span(label + ": " + value);
        stylePlanLine(span);
        return span;
    }

    private static void stylePlanLine(Span span) {
        span.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)");
    }

}
