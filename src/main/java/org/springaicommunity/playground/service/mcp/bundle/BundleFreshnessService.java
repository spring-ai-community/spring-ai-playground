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

import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BundleFreshnessService {

    public enum Change { CHANGED, REMOVED }

    public record SourceChange(String source, Change change) {

        public String label() {
            int separator = source.indexOf(':');
            return separator < 0 ? source : source.substring(separator + 1);
        }
    }

    private final BundleExportService bundleExportService;

    public BundleFreshnessService(BundleExportService bundleExportService) {
        this.bundleExportService = bundleExportService;
    }

    public List<SourceChange> changes(BundleEntry entry) {
        Map<String, String> shipped = entry.manifest().provenance().sources();
        if (entry.buildConfig() == null || shipped.isEmpty()) return List.of();
        Map<String, String> current = this.bundleExportService.fingerprints(entry.buildConfig());
        List<SourceChange> changes = new ArrayList<>();
        shipped.forEach((source, fingerprint) -> {
            String now = current.get(source);
            if (now == null) {
                changes.add(new SourceChange(source, Change.REMOVED));
            } else if (!now.equals(fingerprint)) {
                changes.add(new SourceChange(source, Change.CHANGED));
            }
        });
        return changes;
    }

}
