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
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class BundleExportService {

    private static final Logger logger = LoggerFactory.getLogger(BundleExportService.class);
    static final Pattern SERVER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+(-M\\d+)?");
    private static final Pattern GA_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final String RELEASE_IMAGE_TAG_PREFIX = "v";
    private static final String UNRELEASED_IMAGE_TAG = "latest";
    public static final String BUILD_CONFIG_FILE = "build-config.json";
    public static final int DEFAULT_HTTP_PORT = 18282;

    public static final String CONFIGURED_SERVERS_PROPERTY = "spring.ai.playground.shipyard.servers";
    public static final String DEFAULT_VERSION = "0.1.0";
    public static final String OPENAI_API_KEY_ENV = "SPRING_AI_OPENAI_APIKEY";

    public record ValidationReport(List<String> blockers, List<String> warnings,
            List<BundleManifest.EnvVar> envRequirements, List<ShippedTool> tools) {

        public boolean exportable() {
            return blockers.isEmpty();
        }

        public long secretEnvCount() {
            return envRequirements.stream().filter(BundleManifest.EnvVar::secret).count();
        }
    }

    public record ExportResult(Path bundleDir, BundleManifest manifest, List<Path> files) {}

    private final List<BundleContribution> contributions;
    private final BundleArtifactWriter artifactWriter;
    private final BundleRegistryService registryService;
    private final SpringAiPlaygroundOptions.Shipyard shipyardOptions;
    private final String appVersion;

    public BundleExportService(List<BundleContribution> contributions, BundleArtifactWriter artifactWriter,
            BundleRegistryService registryService, SpringAiPlaygroundOptions playgroundOptions,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.contributions = List.copyOf(contributions);
        this.artifactWriter = artifactWriter;
        this.registryService = registryService;
        this.shipyardOptions = playgroundOptions.shipyard();
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        this.appVersion = buildProperties == null ? "dev" : buildProperties.getVersion();
    }

    public String imageTag() {
        if (imageTagConfigured()) return this.shipyardOptions.imageTag();
        return isReleaseVersion() ? RELEASE_IMAGE_TAG_PREFIX + this.appVersion : UNRELEASED_IMAGE_TAG;
    }

    public String image() {
        return this.shipyardOptions.imageRepository() + ":" + imageTag();
    }

    public boolean managedByConfiguration(String serverName) {
        return this.shipyardOptions.manages(serverName);
    }

    private boolean isReleaseVersion() {
        return RELEASE_VERSION_PATTERN.matcher(this.appVersion).matches();
    }

    private boolean imageTagConfigured() {
        return this.shipyardOptions.imageTag() != null && !this.shipyardOptions.imageTag().isBlank();
    }

    public ShipRequest normalize(ShipRequest request) {
        ShipRequest normalized = request;
        for (BundleContribution contribution : this.contributions) {
            normalized = contribution.normalize(normalized);
        }
        return normalized;
    }

    public ValidationReport validate(ShipRequest request) {
        ShipDraft draft = draft(request);
        if (request.serverName() != null && managedByConfiguration(request.serverName())) {
            draft.block("Server '" + request.serverName() + "' is managed by configuration; change it under "
                    + CONFIGURED_SERVERS_PROPERTY);
        }
        return report(draft);
    }

    ValidationReport validateContent(ShipRequest request) {
        return report(draft(request));
    }

    private static ValidationReport report(ShipDraft draft) {
        return new ValidationReport(draft.blockers(), draft.warnings(), draft.env(), draft.tools());
    }

    private ShipDraft draft(ShipRequest rawRequest) {
        ShipRequest request = normalize(rawRequest);
        ShipDraft draft = new ShipDraft();
        if (request.serverName() == null || request.serverName().isBlank()) {
            draft.block("Server name is required");
        } else if (!SERVER_NAME_PATTERN.matcher(request.serverName()).matches()) {
            draft.block("Server name may only contain letters, digits, '.', '_' and '-'");
        } else if (this.registryService.find(request.serverName()).isPresent()) {
            draft.warn("Server '" + request.serverName() + "' already exists; shipping replaces its bundle");
        }
        if (request.version() == null || !IMAGE_TAG_PATTERN.matcher(request.version()).matches()) {
            draft.block("Version must be a valid image tag: letters, digits, '_', '.' and '-', "
                    + "not starting with '.' or '-'");
        }
        if (request.toolCount() == 0) {
            draft.block("Select at least one tool");
        }
        this.contributions.forEach(contribution -> contribution.validate(request, draft));
        blockNameClashes(draft);
        if (BundleArtifactWriter.usesProvider(request.models(), "openai")) {
            draft.requireEnv(new BundleManifest.EnvVar(OPENAI_API_KEY_ENV, "API key for the required OpenAI model",
                    true));
        }
        if (!imageTagConfigured() && !isReleaseVersion()) {
            draft.warn("App version '" + this.appVersion + "' is not a release or milestone; generated artifacts pin image tag '"
                    + UNRELEASED_IMAGE_TAG + "'");
        }
        return draft;
    }

    private static void blockNameClashes(ShipDraft draft) {
        Map<String, ShippedTool> byName = new LinkedHashMap<>();
        for (ShippedTool tool : draft.tools()) {
            ShippedTool previous = byName.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                draft.block("Two tools are named '" + tool.name() + "' (" + previous.kind().label() + " and "
                        + tool.kind().label() + "); an MCP server cannot expose two tools with the same name");
            }
        }
    }

    public ExportResult exportBundle(ShipRequest request) {
        return export(request, validate(request));
    }

    ExportResult exportConfigured(ShipRequest request) {
        return export(request, validateContent(request));
    }

    public Map<String, String> fingerprints(ShipRequest rawRequest) {
        ShipRequest request = normalize(rawRequest);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        this.contributions.forEach(contribution -> fingerprints.putAll(contribution.fingerprints(request)));
        return fingerprints;
    }

    private ExportResult export(ShipRequest rawRequest, ValidationReport report) {
        ShipRequest request = normalize(rawRequest);
        if (!report.exportable()) {
            throw new IllegalArgumentException("Bundle is not exportable: " + report.blockers());
        }
        Path bundleDir = this.registryService.bundleDir(request.serverName());
        if (Files.exists(bundleDir) && !this.registryService.isBundleDir(bundleDir)) {
            throw new IllegalArgumentException(
                    "Target directory exists and is not a bundle: " + bundleDir);
        }
        try {
            return writeBundle(request, report, bundleDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write bundle " + bundleDir + ": " + e, e);
        }
    }

    private ExportResult writeBundle(ShipRequest request, ValidationReport report, Path bundleDir)
            throws IOException {
        BundleWriter writer = new BundleWriter(bundleDir);
        BundleRegistryService.deleteTree(writer.homeDir());
        for (BundleContribution contribution : this.contributions) {
            contribution.write(request, writer);
        }

        List<BundleManifest.ToolRisk> riskSummary = new ArrayList<>();
        RiskLevel maxLevel = RiskLevel.L0;
        for (ShippedTool tool : report.tools()) {
            riskSummary.add(new BundleManifest.ToolRisk(tool.name(), tool.riskLabel(), tool.hitl(),
                    tool.kind().name(), tool.description()));
            if (tool.riskCap().ordinal() > maxLevel.ordinal()) maxLevel = tool.riskCap();
        }

        BundleManifest manifest = new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server(request.serverName(), request.description(), request.version()),
                new BundleManifest.Requires(minRuntimeVersion(), report.envRequirements(), request.models(),
                        writer.upstreamServers()),
                writer.contents(),
                new BundleManifest.Risk(maxLevel.name(), riskSummary),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, writer.fileHashes(),
                        writer.toolHashes()),
                new BundleManifest.Provenance("spring-ai-playground " + this.appVersion, Instant.now().toString(),
                        fingerprints(request)));
        Path manifestFile = writer.homeDir().resolve(BundleManifest.FILE_NAME);
        Files.write(manifestFile, PersistenceServiceInterface.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(manifest));

        Path buildConfigFile = bundleDir.resolve(BUILD_CONFIG_FILE);
        Files.write(buildConfigFile,
                PersistenceServiceInterface.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(request));

        List<Path> artifactFiles = this.artifactWriter.writeArtifacts(bundleDir, manifest, request, image());

        List<Path> files = new ArrayList<>(List.of(manifestFile, buildConfigFile));
        files.addAll(writer.files());
        files.addAll(artifactFiles);
        logger.info("Exported MCP server bundle: name={}, tools={}, dir={}", request.serverName(),
                report.tools().size(), bundleDir);
        return new ExportResult(bundleDir, manifest, files);
    }

    private String minRuntimeVersion() {
        return GA_VERSION_PATTERN.matcher(this.appVersion).matches() ? this.appVersion : "0.2.0";
    }

    static String relativize(Path bundleDir, Path file) {
        return bundleDir.relativize(file).toString().replace('\\', '/');
    }

    static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
