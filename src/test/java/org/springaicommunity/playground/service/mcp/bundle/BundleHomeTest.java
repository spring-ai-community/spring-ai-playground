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
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleHomeTest {

    @TempDir
    Path source;

    @Test
    void everyPreparationIsAFreshCopyThatLeavesTheSourceUntouched() throws IOException {
        Files.writeString(this.source.resolve(BundleManifest.FILE_NAME), "{}");
        Path settings = this.source.resolve("tool").resolve("save").resolve("toolSpecsMcpSetting.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"toolSpecs\":[]}");

        Path first = BundleHome.prepareWorkingHome(this.source);
        Path second = BundleHome.prepareWorkingHome(this.source);
        try {
            Files.writeString(first.resolve("tool/save/toolSpecsMcpSetting.json"), "changed at run time");
            Files.createDirectories(first.resolve("logs"));

            assertThat(first).isNotEqualTo(second).isNotEqualTo(this.source);
            assertThat(second.resolve("tool/save/toolSpecsMcpSetting.json")).hasContent("{\"toolSpecs\":[]}");
            assertThat(settings).hasContent("{\"toolSpecs\":[]}");
            assertThat(this.source.resolve("logs")).doesNotExist();
        } finally {
            FileSystemUtils.deleteRecursively(first);
            FileSystemUtils.deleteRecursively(second);
        }
    }

    @Test
    void aSourceWithoutManifestIsRejected() {
        assertThatThrownBy(() -> BundleHome.prepareWorkingHome(this.source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BundleHome.SOURCE_PROPERTY)
                .hasMessageContaining(BundleManifest.FILE_NAME);
    }

}
