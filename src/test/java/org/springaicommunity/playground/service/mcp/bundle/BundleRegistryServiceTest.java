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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleRegistryServiceTest {

    @TempDir
    Path homeDir;

    private BundleRegistryService registry() {
        return new BundleRegistryService(homeDir);
    }

    private void writeBundle(String dirName, String manifestServerName) throws IOException {
        BundleManifest manifest = new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server(manifestServerName, null, "0.1.0"),
                new BundleManifest.Requires("0.2.0", List.of(), new BundleManifest.Models(null, null),
                        List.of()),
                new BundleManifest.Contents(List.of("t-1"), null, null, null),
                new BundleManifest.Risk("L0", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, Map.of(), Map.of()),
                new BundleManifest.Provenance("test", "now"));
        Path manifestFile = homeDir.resolve("mcp").resolve("bundles").resolve(dirName)
                .resolve(BundleManifest.HOME_DIR).resolve(BundleManifest.FILE_NAME);
        Files.createDirectories(manifestFile.getParent());
        Files.write(manifestFile, PersistenceServiceInterface.OBJECT_MAPPER.writeValueAsBytes(manifest));
    }

    @Test
    void rejectsServerNamesOutsideThePattern() {
        BundleRegistryService registry = registry();
        assertThatThrownBy(() -> registry.bundleDir("../evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.delete("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.find(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsBundleWhoseManifestNameDoesNotMatchItsDirectory() throws IOException {
        writeBundle("honest-server", "honest-server");
        writeBundle("mismatched-dir", "../escape-attempt");

        BundleRegistryService registry = registry();

        assertThat(registry.list()).hasSize(1)
                .allMatch(entry -> entry.name().equals("honest-server"));
        assertThat(registry.find("honest-server")).isPresent();
        assertThat(registry.find("mismatched-dir")).isEmpty();
    }

}
