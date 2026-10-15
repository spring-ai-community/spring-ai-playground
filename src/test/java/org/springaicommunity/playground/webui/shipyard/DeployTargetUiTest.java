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

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetFixtures;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService;
import org.springaicommunity.playground.service.mcp.bundle.OllamaModelProbe;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeployTargetUiTest extends SpringBrowserlessTest {

    private static final String TOOL_ID = "target-ui-t1";
    private static final String SERVER_NAME = "target-ui-server";

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private OllamaModelProbe ollamaModelProbe;

    @TempDir
    Path home;

    private final List<List<String>> commands = new ArrayList<>();

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    @Test
    void theRunDialogStartsOnTheLocalTargetWithAnOptionalToken() {
        DeployTargetService targets = targets(Map.of());
        RunPlanDialog dialog = open(targets, () -> { });

        assertThat(targetSelect(dialog).getValue().isLocal()).isTrue();
        assertThat(tokenField(dialog).getLabel()).endsWith("(optional)");
        assertThat(tokenField(dialog).getValue()).isEmpty();
        assertThat(command(dialog)).startsWith("docker compose -f ").doesNotContain("--context");
        assertThat(spanTexts(dialog)).contains("Port: localhost:18400 -> 8282")
                .anyMatch(text -> text.startsWith("Docker 29.3.1 reachable on Docker on this machine"));
        assertThat(runButton(dialog).isEnabled()).isTrue();
    }

    @Test
    void pickingARemoteTargetRequiresATokenAndShowsWhatChanges() {
        DeployTargetService targets = targets(Map.of());
        RunPlanDialog dialog = open(targets, () -> { });

        test(targetSelect(dialog)).selectItem("build-box - docker context build-box (ssh://ops@build.example.com)");

        assertThat(tokenField(dialog).getValue()).hasSizeGreaterThan(20);
        assertThat(tokenField(dialog).getLabel()).isEqualTo(DockerCliService.AUTH_TOKEN_ENV);
        assertThat(command(dialog)).startsWith("docker --context build-box compose -f ");
        assertThat(spanTexts(dialog)).contains("Port: build.example.com:18400 -> 8282",
                "host.docker.internal inside the container means build.example.com, not this machine")
                .anyMatch(text -> text.startsWith("The bundle folder is sent as build context"));
        assertThat(runButton(dialog).isEnabled()).isTrue();

        test(tokenField(dialog)).setValue("");
        assertThat(runButton(dialog).isEnabled()).isFalse();

        test(targetSelect(dialog)).selectItem("local - Docker on this machine");
        assertThat(runButton(dialog).isEnabled()).isTrue();
    }

    @Test
    void runningOnARemoteTargetRemembersIt() {
        DeployTargetService targets = targets(Map.of());
        AtomicBoolean started = new AtomicBoolean();
        RunPlanDialog dialog = open(targets, () -> started.set(true));

        test(targetSelect(dialog)).selectItem("build-box - docker context build-box (ssh://ops@build.example.com)");
        test(runButton(dialog)).click();

        assertThat(started).isTrue();
        assertThat(targets.targetOf(SERVER_NAME).name()).isEqualTo("build-box");
        assertThat(this.commands).anySatisfy(command ->
                assertThat(command).containsSequence("--context", "build-box", "compose"));
    }

    @Test
    void anUnreachableTargetCannotBeRunOn() {
        DeployTargetService targets = targets(Map.of());
        RunPlanDialog dialog = open(targets, () -> { });

        test(targetSelect(dialog)).selectItem("offline - docker context offline (tcp://10.9.9.9:2375)");

        assertThat(spanTexts(dialog)).contains("Docker on docker context offline (tcp://10.9.9.9:2375) is not reachable");
        assertThat(runButton(dialog).isEnabled()).isFalse();
    }

    @Test
    void aConfiguredServerTargetLocksThePicker() {
        DeployTargetService targets = targets(Map.of(
                "spring.ai.playground.shipyard.servers[0].name", SERVER_NAME,
                "spring.ai.playground.shipyard.servers[0].target", "build-box"));
        RunPlanDialog dialog = open(targets, () -> { });

        assertThat(targetSelect(dialog).getValue().name()).isEqualTo("build-box");
        assertThat(targetSelect(dialog).isReadOnly()).isTrue();
        assertThat(targetSelect(dialog).getHelperText()).contains("spring.ai.playground.shipyard.servers[].target");
    }

    @Test
    void aTokenSetInConfigurationFillsTheFieldReadOnlyOnEveryTarget() {
        DeployTargetService targets = targets(Map.of(DockerCliService.AUTH_TOKEN_ENV, "configured-token"));
        RunPlanDialog dialog = open(targets, () -> { });

        assertThat(tokenField(dialog).getValue()).isEqualTo("configured-token");
        assertThat(tokenField(dialog).isReadOnly()).isTrue();
        assertThat(tokenField(dialog).getHelperText()).contains("configuration (SAIP_MCP_AUTH_TOKEN)");

        test(targetSelect(dialog)).selectItem("build-box - docker context build-box (ssh://ops@build.example.com)");
        assertThat(tokenField(dialog).getValue()).isEqualTo("configured-token");
        test(runButton(dialog)).click();
        assertThat(targets.authToken(SERVER_NAME)).contains("configured-token");
    }

    @Test
    void loopbackUpstreamUrlsAreRecognised() {
        assertThat(RunPlanner.loopbackUrl("http://localhost:18611/mcp")).isTrue();
        assertThat(RunPlanner.loopbackUrl("http://127.0.0.2:18611/mcp")).isTrue();
        assertThat(RunPlanner.loopbackUrl("http://host.docker.internal:18611/mcp")).isTrue();
        assertThat(RunPlanner.loopbackUrl("http://[::1]:18611/mcp")).isTrue();
        assertThat(RunPlanner.loopbackUrl("https://mcp.deepwiki.com/mcp")).isFalse();
        assertThat(RunPlanner.loopbackUrl("not a url")).isFalse();
    }

    @Test
    void theTargetsDialogListsEveryOriginAndAddsAndRemovesOwnTargets() {
        DeployTargetService targets = targets(Map.of(
                "spring.ai.playground.shipyard.targets[0].name", "prod",
                "spring.ai.playground.shipyard.targets[0].docker-host", "tcp://10.0.0.5:2376"));
        AtomicBoolean changed = new AtomicBoolean();
        TargetsDialog dialog = new TargetsDialog(targets, () -> changed.set(true));
        dialog.open();

        assertThat(gridNames(dialog)).containsExactly("local", "prod", "build-box", "offline");
        assertThat(actionTexts(dialog, 1)).containsExactly("Check");

        test(field(dialog, "Name")).setValue("lab");
        test(field(dialog, "Docker host")).setValue("ssh://me@lab.local");
        test(field(dialog, "Public host (optional)")).setValue("lab.example.com");
        test(button(dialog, "Add target")).click();

        assertThat(changed).isTrue();
        assertThat(gridNames(dialog)).containsExactly("local", "prod", "lab", "build-box", "offline");
        assertThat(targets.find("lab")).hasValueSatisfying(
                target -> assertThat(target.endpointHost()).isEqualTo("lab.example.com"));

        assertThat(actionTexts(dialog, 2)).containsExactly("Check", "Remove");
        actionButtons(dialog, 2).getLast().click();
        assertThat(gridNames(dialog)).doesNotContain("lab");
    }

    private DeployTargetService targets(Map<String, String> properties) {
        return DeployTargetFixtures.service(this.home, properties, Set.of("offline"), this.commands);
    }

    private RunPlanDialog open(DeployTargetService targets, Runnable onStarted) {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "targetEchoTool", "echoes", List.of(), List.of(),
                "return 1;", CodeType.Javascript, null).withDraft(false));
        this.bundleExportService.exportBundle(new ShipRequest(SERVER_NAME, "target ui bundle", "0.1.0",
                List.of(TOOL_ID), Transport.BOTH, 18400, new BundleManifest.Models(null, null)));
        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        RunPlanDialog dialog = new RunPlanDialog(entry, "base:latest",
                new RunPlanner(targets, this.ollamaModelProbe, this.bundleRegistryService), onStarted);
        dialog.open();
        return dialog;
    }

    @SuppressWarnings("unchecked")
    private Select<DeployTarget> targetSelect(RunPlanDialog dialog) {
        return $(Select.class, dialog).first();
    }

    private PasswordField tokenField(RunPlanDialog dialog) {
        return $(PasswordField.class, dialog)
                .withCondition(field -> field.getLabel().startsWith(DockerCliService.AUTH_TOKEN_ENV)).first();
    }

    private String command(RunPlanDialog dialog) {
        return $(TextArea.class, dialog).withCondition(area -> "Command".equals(area.getLabel())).first().getValue();
    }

    private Button runButton(RunPlanDialog dialog) {
        return button(dialog, "Run");
    }

    private Button button(Component root, String text) {
        return $(Button.class, root).withCondition(button -> text.equals(button.getText())).first();
    }

    private TextField field(TargetsDialog dialog, String label) {
        return $(TextField.class, dialog).withCondition(field -> label.equals(field.getLabel())).first();
    }

    @SuppressWarnings("unchecked")
    private List<Button> actionButtons(TargetsDialog dialog, int row) {
        Grid<DeployTarget> grid = $(Grid.class, dialog).first();
        return test(grid).getCellComponent(row, 4).getChildren()
                .filter(Button.class::isInstance).map(Button.class::cast).toList();
    }

    private List<String> actionTexts(TargetsDialog dialog, int row) {
        return actionButtons(dialog, row).stream().map(Button::getText).toList();
    }

    private List<String> spanTexts(Component root) {
        return $(Span.class, root).all().stream().map(Span::getText).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> gridNames(TargetsDialog dialog) {
        Grid<DeployTarget> grid = $(Grid.class, dialog).first();
        return grid.getGenericDataView().getItems().map(DeployTarget::name).toList();
    }

}
