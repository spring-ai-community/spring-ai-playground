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
package org.springaicommunity.playground.service.management;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.Management;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AccessMode;
import org.springaicommunity.playground.service.mcp.McpServerAuthTokenService.AuthTokenChangedEvent;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class ManagementToolRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(ManagementToolRegistrar.class);

    private final SpringAiPlaygroundOptions options;
    private final ObjectProvider<McpSyncServer> syncServerProvider;
    private final ToolSpecService toolSpecService;
    private final McpServerAuthTokenService authTokenService;
    private final ObjectProvider<ManagementToolContribution> contributions;
    private final ManagementApprovalGate approvalGate;
    private final Path homeDir;
    private final Environment environment;
    private final Set<String> registeredNames = new TreeSet<>();
    private final Map<String, SyncResourceSpecification> registeredResources = new LinkedHashMap<>();
    private final Map<String, SyncResourceTemplateSpecification> registeredTemplates = new LinkedHashMap<>();
    private final Map<String, SyncPromptSpecification> registeredPrompts = new LinkedHashMap<>();

    private volatile ManagementStatus status;

    public ManagementToolRegistrar(SpringAiPlaygroundOptions options, ObjectProvider<McpSyncServer> syncServerProvider,
            ToolSpecService toolSpecService, McpServerAuthTokenService authTokenService,
            ObjectProvider<ManagementToolContribution> contributions, ManagementApprovalGate approvalGate,
            Path springAiPlaygroundHomeDir, Environment environment) {
        this.options = options;
        this.syncServerProvider = syncServerProvider;
        this.toolSpecService = toolSpecService;
        this.authTokenService = authTokenService;
        this.contributions = contributions;
        this.approvalGate = approvalGate;
        this.homeDir = springAiPlaygroundHomeDir;
        this.environment = environment;
        this.status = ManagementStatus.inactive("not evaluated yet", options.management().approval());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    @EventListener
    public void onAuthTokenChanged(AuthTokenChangedEvent event) {
        refresh();
    }

    public ManagementStatus status() {
        return this.status;
    }

    public synchronized void refresh() {
        Management management = this.options.management();
        String blocker = blocker(management);
        Map<String, ManagementTool> desired = blocker == null ? desiredTools(management) : Map.of();
        Set<String> obsolete = this.registeredNames.stream().filter(name -> !desired.containsKey(name))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!obsolete.isEmpty()) {
            this.toolSpecService.removeServedTools(obsolete);
            this.registeredNames.removeAll(obsolete);
        }
        List<SyncToolSpecification> additions = desired.values().stream()
                .filter(tool -> !this.registeredNames.contains(tool.name()))
                .map(this.approvalGate::wrap)
                .toList();
        if (!additions.isEmpty()) {
            this.toolSpecService.addServedTools(additions);
            desired.keySet().stream().filter(name -> !this.registeredNames.contains(name))
                    .forEach(this.registeredNames::add);
        }
        refreshGuides(blocker == null ? management : null);
        this.status = blocker == null
                ? new ManagementStatus(true, null, desired.values().stream().map(ManagementTool::category)
                        .collect(Collectors.toSet()), management.approval(), desired.size())
                : ManagementStatus.inactive(blocker, management.approval());
        logger.info("management tools: {}", describe(this.status));
    }

    private void refreshGuides(Management management) {
        McpSyncServer server = this.syncServerProvider.getIfAvailable();
        if (server == null) {
            return;
        }
        Map<String, SyncResourceSpecification> desiredResources = new LinkedHashMap<>();
        Map<String, SyncResourceTemplateSpecification> desiredTemplates = new LinkedHashMap<>();
        Map<String, SyncPromptSpecification> desiredPrompts = new LinkedHashMap<>();
        if (management != null) {
            this.contributions.orderedStream()
                    .filter(contribution -> management.categoryEnabled(contribution.category()))
                    .forEach(contribution -> {
                        contribution.resources().forEach(resource ->
                                desiredResources.put(resource.resource().uri(), resource));
                        contribution.resourceTemplates().forEach(template ->
                                desiredTemplates.put(template.resourceTemplate().uriTemplate(), template));
                        contribution.prompts().forEach(prompt -> desiredPrompts.put(prompt.prompt().name(), prompt));
                    });
        }
        try {
            new TreeSet<>(this.registeredResources.keySet()).stream()
                    .filter(uri -> !desiredResources.containsKey(uri)).forEach(uri -> {
                        server.removeResource(uri);
                        this.registeredResources.remove(uri);
                    });
            desiredResources.forEach((uri, resource) -> {
                if (!this.registeredResources.containsKey(uri)) {
                    server.addResource(resource);
                    this.registeredResources.put(uri, resource);
                }
            });
            new TreeSet<>(this.registeredTemplates.keySet()).stream()
                    .filter(uriTemplate -> !desiredTemplates.containsKey(uriTemplate)).forEach(uriTemplate -> {
                        server.removeResourceTemplate(uriTemplate);
                        this.registeredTemplates.remove(uriTemplate);
                    });
            desiredTemplates.forEach((uriTemplate, template) -> {
                if (!this.registeredTemplates.containsKey(uriTemplate)) {
                    server.addResourceTemplate(template);
                    this.registeredTemplates.put(uriTemplate, template);
                }
            });
            new TreeSet<>(this.registeredPrompts.keySet()).stream()
                    .filter(name -> !desiredPrompts.containsKey(name)).forEach(name -> {
                        server.removePrompt(name);
                        this.registeredPrompts.remove(name);
                    });
            desiredPrompts.forEach((name, prompt) -> {
                if (!this.registeredPrompts.containsKey(name)) {
                    server.addPrompt(prompt);
                    this.registeredPrompts.put(name, prompt);
                }
            });
        } catch (IllegalStateException | IllegalArgumentException e) {
            logger.warn("Management guides could not be registered: {}", e.getMessage());
        }
    }

    private String blocker(Management management) {
        if (!management.enabled()) {
            return "disabled by spring.ai.playground.management.enabled=false";
        }
        if (Files.isRegularFile(this.homeDir.resolve(BundleManifest.FILE_NAME))) {
            return "not offered by a shipped server bundle";
        }
        if (this.environment.getProperty("spring.ai.mcp.server.stdio", Boolean.class, false)) {
            return "not offered in stdio mode";
        }
        if (this.syncServerProvider.getIfAvailable() == null) {
            return "no synchronous MCP server is running";
        }
        if (this.authTokenService.accessMode() != AccessMode.BEARER_TOKEN) {
            return "no bearer token on the built-in server";
        }
        return null;
    }

    private Map<String, ManagementTool> desiredTools(Management management) {
        Map<String, ManagementTool> desired = new LinkedHashMap<>();
        this.contributions.orderedStream()
                .filter(contribution -> management.categoryEnabled(contribution.category()))
                .flatMap(contribution -> contribution.tools().stream())
                .filter(tool -> !management.toolExcluded(tool.name()))
                .sorted(Comparator.comparing(ManagementTool::name))
                .forEach(tool -> desired.put(tool.name(), tool));
        return desired;
    }

    private static String describe(ManagementStatus status) {
        return status.active()
                ? "active, categories=" + new TreeSet<>(status.categories()) + ", approval=" + status.approval()
                        + ", " + status.toolCount() + " tools"
                : "inactive, reason=" + status.reason();
    }

}
