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
import org.springaicommunity.playground.service.mcp.risk.McpExposedToolService;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.util.EnvVarResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BundleBootstrapper implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(BundleBootstrapper.class);

    private final Path homeDir;
    private final List<BundleContribution> contributions;
    private final ToolSpecService toolSpecService;
    private final ObjectProvider<McpExposedToolService> exposedToolServiceProvider;
    private final BundleDependencyMonitor dependencyMonitor;
    private final BundleReadiness bundleReadiness;
    private final Environment environment;

    public BundleBootstrapper(Path springAiPlaygroundHomeDir, List<BundleContribution> contributions,
            ToolSpecService toolSpecService, ObjectProvider<McpExposedToolService> exposedToolServiceProvider,
            BundleDependencyMonitor dependencyMonitor, BundleReadiness bundleReadiness, Environment environment) {
        this.homeDir = springAiPlaygroundHomeDir;
        this.contributions = List.copyOf(contributions);
        this.toolSpecService = toolSpecService;
        this.exposedToolServiceProvider = exposedToolServiceProvider;
        this.dependencyMonitor = dependencyMonitor;
        this.bundleReadiness = bundleReadiness;
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Path manifestFile = this.homeDir.resolve(BundleManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            return;
        }
        BundleManifest manifest = readManifest(manifestFile);
        serve(manifest);
    }

    void serve(BundleManifest manifest) {
        if (!BundleManifest.CURRENT_BUNDLE_VERSION.equals(manifest.bundleVersion())) {
            throw new IllegalStateException("Unsupported bundle version '" + manifest.bundleVersion()
                    + "'; this runtime supports version " + BundleManifest.CURRENT_BUNDLE_VERSION);
        }
        verifyFileHashes(manifest);
        verifyEnv(manifest);
        verifyModels(manifest);
        List<String> problems = new ArrayList<>();
        this.contributions.forEach(contribution -> contribution.verify(manifest, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Bundle tool verification failed: " + problems);
        }

        this.toolSpecService.setExposureMode(exposureModeFor(manifest));
        List<McpServerInfo> upstreams = new ArrayList<>();
        this.contributions.forEach(contribution -> upstreams.addAll(contribution.serve(manifest)));
        McpExposedToolService exposedToolService = this.exposedToolServiceProvider.getIfAvailable();
        if (exposedToolService != null) {
            exposedToolService.sync();
        }
        this.dependencyMonitor.watch(manifest, upstreams);
        this.bundleReadiness.markReady();
        logger.info("Serving MCP server bundle '{}' v{}: {} tool(s) and {} proxied tool(s) exposed",
                manifest.server().name(), manifest.server().version(), manifest.contents().tools().size(),
                manifest.contents().composedMembers().size());
    }

    private void verifyFileHashes(BundleManifest manifest) {
        for (Map.Entry<String, String> entry : manifest.integrity().files().entrySet()) {
            Path file = resolveBundleFile(entry.getKey());
            try {
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("Bundle file missing: " + entry.getKey());
                }
                String actual = BundleExportService.sha256(file);
                if (!actual.equals(entry.getValue())) {
                    throw new IllegalStateException("Bundle file hash mismatch for " + entry.getKey()
                            + "; the bundle was modified after export");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to hash bundle file: " + file, e);
            }
        }
    }

    private Path resolveBundleFile(String relativePath) {
        String withoutHomePrefix = relativePath.startsWith(BundleManifest.HOME_DIR + "/")
                ? relativePath.substring(BundleManifest.HOME_DIR.length() + 1)
                : relativePath;
        return shippedBundleDir().resolve(withoutHomePrefix);
    }

    private Path shippedBundleDir() {
        String source = this.environment.getProperty(BundleHome.SOURCE_PROPERTY);
        return source == null || source.isBlank() ? this.homeDir : Path.of(source);
    }

    private void verifyEnv(BundleManifest manifest) {
        List<String> missing = manifest.requires().env().stream()
                .map(BundleManifest.EnvVar::name)
                .filter(name -> EnvVarResolver.lookup(name).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Bundle requires environment variables that are not set: "
                    + missing);
        }
    }

    private void verifyModels(BundleManifest manifest) {
        BundleManifest.Models models = manifest.requires().models();
        requireModel("chat", models.chat());
        requireModel("embedding", models.embedding());
    }

    private void requireModel(String kind, BundleManifest.ModelRef required) {
        String property = "spring.ai.model." + kind;
        String configured = this.environment.getProperty(property, "");
        if (required == null) {
            if (!"none".equalsIgnoreCase(configured)) {
                throw new IllegalStateException("Bundle declares no " + kind + " model requirement, so "
                        + property + " must be 'none' but is '" + configured
                        + "'; restore the compose environment or rebuild the bundle with a " + kind
                        + " model requirement");
            }
            return;
        }
        if (!required.provider().equalsIgnoreCase(configured)) {
            throw new IllegalStateException("Bundle requires " + kind + " model provider '"
                    + required.provider() + "' (model '" + required.model() + "') but " + property + "='"
                    + configured + "'");
        }
        String modelProperty = "spring.ai." + required.provider().toLowerCase(Locale.ROOT) + "." + kind
                + ".options.model";
        String configuredModel = this.environment.getProperty(modelProperty);
        if (configuredModel != null && !configuredModel.equals(required.model())) {
            throw new IllegalStateException("Bundle requires " + kind + " model '" + required.model() + "' but "
                    + modelProperty + "='" + configuredModel + "'");
        }
    }

    static ToolSpecService.ExposureMode exposureModeFor(BundleManifest manifest) {
        if (manifest.contents().composedMembers().isEmpty()) return ToolSpecService.ExposureMode.BUILTIN_ONLY;
        return manifest.contents().tools().isEmpty()
                ? ToolSpecService.ExposureMode.COMPOSED_ONLY : ToolSpecService.ExposureMode.BOTH;
    }

    private static BundleManifest readManifest(Path manifestFile) {
        try {
            return PersistenceServiceInterface.OBJECT_MAPPER
                    .readValue(Files.readAllBytes(manifestFile), BundleManifest.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to read bundle manifest: " + manifestFile, e);
        }
    }

}
