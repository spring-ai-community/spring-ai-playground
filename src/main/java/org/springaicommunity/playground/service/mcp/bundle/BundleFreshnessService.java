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
import java.util.Optional;

@Service
public class BundleFreshnessService {

    public enum Change { CHANGED, REMOVED, ADDED }

    public record SourceChange(String source, Change change, String detail) {

        public SourceChange(String source, Change change) {
            this(source, change, null);
        }

        public String label() {
            int separator = source.indexOf(':');
            return separator < 0 ? source : source.substring(separator + 1);
        }

        public Optional<KnowledgeSourceKey> knowledge() {
            return KnowledgeSourceKey.parse(this.source);
        }
    }

    public record KnowledgeSourceKey(String tool, String document) {

        public boolean pipeline() {
            return this.document == null;
        }

        public static Optional<KnowledgeSourceKey> parse(String source) {
            if (source == null || !source.startsWith(KnowledgeContribution.SOURCE_PREFIX)) return Optional.empty();
            String rest = source.substring(KnowledgeContribution.SOURCE_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) return Optional.empty();
            String tool = rest.substring(0, slash);
            String part = rest.substring(slash);
            if (part.equals(KnowledgeContribution.PIPELINE_SOURCE_SUFFIX)) {
                return Optional.of(new KnowledgeSourceKey(tool, null));
            }
            if (part.startsWith(KnowledgeContribution.DOCUMENT_SOURCE_SUFFIX)) {
                return Optional.of(new KnowledgeSourceKey(tool,
                        part.substring(KnowledgeContribution.DOCUMENT_SOURCE_SUFFIX.length())));
            }
            return Optional.empty();
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
                changes.add(new SourceChange(source, Change.REMOVED, documentOf(source)));
            } else if (!now.equals(fingerprint)) {
                changes.add(new SourceChange(source, Change.CHANGED, documentOf(source)));
            }
        });
        current.forEach((source, fingerprint) -> {
            if (shipped.containsKey(source)) return;
            KnowledgeSourceKey.parse(source).filter(key -> !key.pipeline())
                    .filter(key -> shipped.keySet().stream().anyMatch(shippedSource -> shippedSource.startsWith(
                            KnowledgeContribution.SOURCE_PREFIX + key.tool() + "/")))
                    .ifPresent(key -> changes.add(new SourceChange(source, Change.ADDED, key.document())));
        });
        return changes;
    }

    private static String documentOf(String source) {
        return KnowledgeSourceKey.parse(source).map(KnowledgeSourceKey::document).orElse(null);
    }

}
