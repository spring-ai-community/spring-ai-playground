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
package org.springaicommunity.playground.service.mcp.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;
import org.springaicommunity.playground.service.mcp.risk.DefaultIntegrityVerifier;
import org.springaicommunity.playground.service.tool.ToolActivationCalculator;
import org.springaicommunity.playground.service.tool.ToolManifest;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService;
import org.springaicommunity.playground.service.tool.ToolSpecPersistenceService.ToolSpecsMcpServerSetting;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.ToolSpecService.ToolMcpServerSetting;
import org.springaicommunity.playground.service.util.EnvVarResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Order(1)
public class ToolsContribution implements BundleContribution {

    static final String TOOL_SPECS_FILE = "tool/save/toolSpecsMcpSetting.json";
    private static final String SOURCE_PREFIX = "tool:";
    private static final Logger logger = LoggerFactory.getLogger(ToolsContribution.class);

    private final ToolSpecService toolSpecService;
    private final ObjectProvider<ToolSpecPersistenceService> toolSpecPersistenceServiceProvider;
    private final CanonicalHasher canonicalHasher;
    private final DefaultIntegrityVerifier integrityVerifier;
    private final ToolActivationCalculator activationCalculator;

    public ToolsContribution(ToolSpecService toolSpecService,
            ObjectProvider<ToolSpecPersistenceService> toolSpecPersistenceServiceProvider,
            CanonicalHasher canonicalHasher, DefaultIntegrityVerifier integrityVerifier,
            ToolActivationCalculator activationCalculator) {
        this.toolSpecService = toolSpecService;
        this.toolSpecPersistenceServiceProvider = toolSpecPersistenceServiceProvider;
        this.canonicalHasher = canonicalHasher;
        this.integrityVerifier = integrityVerifier;
        this.activationCalculator = activationCalculator;
    }

    @Override
    public void validate(ShipRequest request, ShipDraft draft) {
        Set<String> defaultToolIds = defaultToolIds();
        for (String toolId : request.toolIds()) {
            ToolSpec spec = findSpec(toolId).orElse(null);
            if (spec == null) {
                draft.block("Unknown tool id: " + toolId);
                continue;
            }
            if (spec.draft()) {
                draft.block("Tool '" + spec.name() + "' is a draft; publish it first");
                continue;
            }
            if (this.integrityVerifier.verifyTool(spec).status()
                    == DefaultIntegrityVerifier.Status.REVOKED_TAMPERED) {
                draft.block("Tool '" + spec.name() + "' fails integrity verification and cannot be bundled");
                continue;
            }
            validateSpec(spec, draft);
            String level = this.toolSpecService.riskLevelOf(spec);
            draft.ship(new ShippedTool(spec.name(),
                    defaultToolIds.contains(toolId) ? ShippedTool.Kind.BUILT_IN : ShippedTool.Kind.TOOL,
                    spec.description(), level, RiskLevel.valueOf(level), hitlRequired(spec)));
        }
    }

    private void validateSpec(ToolSpec spec, ShipDraft draft) {
        List<String> missingEnvVars = this.activationCalculator.missingEnvVars(spec);
        if (!missingEnvVars.isEmpty()) {
            draft.warn("Tool '" + spec.name() + "' could not run locally (missing " + missingEnvVars
                    + "); it is bundled without a verified local run");
        }
        for (Map.Entry<String, String> variable : spec.staticVariables()) {
            if (BundleSecrets.literalSecretVariable(variable.getKey(), variable.getValue())) {
                draft.warn("Tool '" + spec.name() + "' static variable '" + variable.getKey()
                        + "' looks like a literal secret; reference it as ${ENV_VAR} instead");
            }
        }
        Set<String> refs = new LinkedHashSet<>();
        spec.staticVariables().forEach(variable -> refs.addAll(EnvVarResolver.findRefs(variable.getValue())));
        refs.addAll(EnvVarResolver.findRefs(spec.code()));
        for (String ref : refs) {
            draft.requireEnv(new BundleManifest.EnvVar(ref, "referenced by tool '" + spec.name() + "'",
                    BundleSecrets.secretName(ref)));
        }
    }

    @Override
    public void write(ShipRequest request, BundleWriter writer) throws IOException {
        List<ToolSpec> specs = request.toolIds().stream().map(toolId -> findSpec(toolId).orElseThrow()).toList();
        Set<String> defaultToolIds = defaultToolIds();
        List<ToolSpec> customSpecs = specs.stream().filter(spec -> !defaultToolIds.contains(spec.toolId())).toList();
        ToolSpecsMcpServerSetting setting = new ToolSpecsMcpServerSetting(customSpecs,
                new ToolMcpServerSetting(false, new LinkedHashSet<>(request.toolIds())));
        writer.write(TOOL_SPECS_FILE, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(setting));
        specs.forEach(spec -> writer.toolHash(spec.name(), this.canonicalHasher.hashTool(spec)));
        writer.tools(request.toolIds());
    }

    @Override
    public Map<String, String> fingerprints(ShipRequest request) {
        Set<String> defaultToolIds = defaultToolIds();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String toolId : request.toolIds()) {
            if (defaultToolIds.contains(toolId)) continue;
            findSpec(toolId).ifPresent(spec ->
                    fingerprints.put(SOURCE_PREFIX + spec.name(), this.canonicalHasher.hashTool(spec)));
        }
        return fingerprints;
    }

    @Override
    public void verify(BundleManifest manifest, List<String> problems) {
        Set<String> defaultToolIds = defaultToolIds();
        for (String toolId : manifest.contents().tools()) {
            ToolSpec spec = findSpec(toolId).orElse(null);
            if (spec == null) {
                problems.add("tool id " + toolId + " not present in this runtime");
                continue;
            }
            String expected = manifest.integrity().tools().get(spec.name());
            if (expected == null) {
                problems.add("tool '" + spec.name() + "' has no integrity hash in the manifest");
                continue;
            }
            if (expected.equals(this.canonicalHasher.hashTool(spec))) continue;
            if (defaultToolIds.contains(toolId)) {
                logger.warn("Built-in tool '{}' differs from the exporting runtime (image version drift); "
                        + "serving the image's version", spec.name());
            } else {
                problems.add("tool '" + spec.name() + "' content hash mismatch; "
                        + "the bundled spec was modified after export");
            }
        }
    }

    @Override
    public List<McpServerInfo> serve(BundleManifest manifest) {
        this.toolSpecService.setToolMcpServerSetting(new ToolMcpServerSetting(false,
                new LinkedHashSet<>(manifest.contents().tools())));
        this.toolSpecService.reconcileNativeExposure();
        return List.of();
    }

    private Optional<ToolSpec> findSpec(String toolId) {
        return this.toolSpecService.getToolSpecList().stream()
                .filter(spec -> toolId.equals(spec.toolId()))
                .findFirst();
    }

    private Set<String> defaultToolIds() {
        ToolSpecPersistenceService persistenceService = this.toolSpecPersistenceServiceProvider.getIfAvailable();
        return persistenceService == null ? Set.of() : persistenceService.getDefaultToolIds();
    }

    private static boolean hitlRequired(ToolSpec spec) {
        return spec.humanInTheLoop() != null
                && spec.humanInTheLoop().mode() == ToolManifest.HumanInTheLoop.Mode.REQUIRED;
    }

}
