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
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundleExportServiceImageTagTest {

    @Test
    void aMilestoneBuildPinsItsOwnReleaseImage() {
        assertThat(serviceAt("0.2.0-M14").imageTag()).isEqualTo("v0.2.0-M14");
    }

    @Test
    void aGeneralAvailabilityBuildPinsItsOwnReleaseImage() {
        assertThat(serviceAt("1.0.0").imageTag()).isEqualTo("v1.0.0");
    }

    @Test
    void aDevelopmentBuildFallsBackToLatest() {
        assertThat(serviceAt("0.2.0-SNAPSHOT").imageTag()).isEqualTo("latest");
        assertThat(serviceAt(null).imageTag()).isEqualTo("latest");
    }

    @Test
    void aConfiguredTagWinsOverTheBuildVersion() {
        SpringAiPlaygroundOptions.Shipyard shipyard = new SpringAiPlaygroundOptions.Shipyard(null, null, "custom",
                null, null);
        assertThat(serviceAt("0.2.0-M14", shipyard).imageTag()).isEqualTo("custom");
    }

    private static BundleExportService serviceAt(String version) {
        return serviceAt(version, new SpringAiPlaygroundOptions.Shipyard(null, null, null, null, null));
    }

    @SuppressWarnings("unchecked")
    private static BundleExportService serviceAt(String version, SpringAiPlaygroundOptions.Shipyard shipyard) {
        ObjectProvider<BuildProperties> buildPropertiesProvider = mock(ObjectProvider.class);
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(version == null ? null : buildProperties(version));
        SpringAiPlaygroundOptions options = new SpringAiPlaygroundOptions(null, false, null, null, null, null, null,
                shipyard, null);
        return new BundleExportService(List.of(), mock(BundleArtifactWriter.class),
                mock(BundleRegistryService.class), options, buildPropertiesProvider);
    }

    private static BuildProperties buildProperties(String version) {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        return new BuildProperties(properties);
    }

}
