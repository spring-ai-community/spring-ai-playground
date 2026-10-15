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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class BundleRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(BundleRegistryService.class);

    public record BundleEntry(String name, BundleManifest manifest, Path dir,
            ShipRequest buildConfig) {

        public int effectiveHttpPort() {
            return buildConfig == null ? BundleExportService.DEFAULT_HTTP_PORT
                    : buildConfig.effectiveHttpPort();
        }
    }

    public static final String BUNDLES_DIR = "mcp/bundles";

    private final Path bundlesDir;

    public BundleRegistryService(Path springAiPlaygroundHomeDir) {
        this.bundlesDir = springAiPlaygroundHomeDir.resolve(BUNDLES_DIR);
    }

    public Path bundleDir(String serverName) {
        if (serverName == null || !BundleExportService.SERVER_NAME_PATTERN.matcher(serverName).matches()) {
            throw new IllegalArgumentException("Invalid server name: " + serverName);
        }
        return this.bundlesDir.resolve(serverName);
    }

    public boolean isBundleDir(Path dir) {
        return Files.isRegularFile(manifestFile(dir));
    }

    public List<BundleEntry> list() {
        if (!Files.isDirectory(this.bundlesDir)) return List.of();
        try (Stream<Path> children = Files.list(this.bundlesDir)) {
            return children.filter(this::isBundleDir)
                    .map(this::readEntry)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(BundleEntry::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list bundles: " + this.bundlesDir, e);
        }
    }

    public Optional<BundleEntry> find(String serverName) {
        Path dir = bundleDir(serverName);
        return isBundleDir(dir) ? readEntry(dir) : Optional.empty();
    }

    public void delete(String serverName) {
        Path dir = bundleDir(serverName);
        if (!isBundleDir(dir)) return;
        try {
            deleteTree(dir);
            logger.info("Deleted bundle: {}", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete bundle: " + dir, e);
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Optional<BundleEntry> readEntry(Path dir) {
        try {
            BundleManifest manifest = PersistenceServiceInterface.OBJECT_MAPPER
                    .readValue(Files.readAllBytes(manifestFile(dir)), BundleManifest.class);
            if (!dir.getFileName().toString().equals(manifest.server().name())) {
                logger.warn("Skipping bundle at {}: manifest server name '{}' does not match the "
                        + "directory name", dir, manifest.server().name());
                return Optional.empty();
            }
            ShipRequest buildConfig = null;
            Path buildConfigFile = dir.resolve(BundleExportService.BUILD_CONFIG_FILE);
            if (Files.isRegularFile(buildConfigFile)) {
                buildConfig = PersistenceServiceInterface.OBJECT_MAPPER
                        .readValue(Files.readAllBytes(buildConfigFile),
                                ShipRequest.class);
            }
            return Optional.of(new BundleEntry(manifest.server().name(), manifest, dir, buildConfig));
        } catch (IOException | RuntimeException e) {
            logger.warn("Skipping unreadable bundle at {} ({})", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private static Path manifestFile(Path dir) {
        return dir.resolve(BundleManifest.HOME_DIR).resolve(BundleManifest.FILE_NAME);
    }

}
