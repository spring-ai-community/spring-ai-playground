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
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.Change;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.KnowledgeSourceKey;
import org.springaicommunity.playground.service.mcp.bundle.BundleFreshnessService.SourceChange;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundleFreshnessServiceTest {

    private final BundleExportService exportService = mock(BundleExportService.class);
    private final BundleFreshnessService service = new BundleFreshnessService(exportService);

    private BundleEntry entry(Map<String, String> shippedSources) {
        BundleManifest manifest = mock(BundleManifest.class, RETURNS_DEEP_STUBS);
        when(manifest.provenance().sources()).thenReturn(shippedSources);
        when(manifest.contents().ragPipelines()).thenReturn(List.of(new BundleManifest.RagPipelineRef("p1",
                new BundleManifest.Expose(true, false), "search_kb", "d", "KB", 4,
                List.of(new BundleManifest.DocumentRef("d1", "Handbook", 3)))));
        ShipRequest request = new ShipRequest("kb-server", null, "0.1.0", List.of(), null, null, null);
        return new BundleEntry("kb-server", manifest, Path.of("/b/kb-server"), request);
    }

    @Test
    void documentLevelKeysReportChangedRemovedAndAddedDocumentsWithTitles() {
        Map<String, String> shipped = new LinkedHashMap<>();
        shipped.put("knowledge:search_kb/pipeline", "p");
        shipped.put("knowledge:search_kb/doc:Handbook", "x");
        shipped.put("knowledge:search_kb/doc:Old notes", "gone");
        shipped.put("tool:uuid", "t");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("knowledge:search_kb/pipeline", "p");
        current.put("knowledge:search_kb/doc:Handbook", "x2");
        current.put("knowledge:search_kb/doc:Policies", "new");
        current.put("tool:uuid", "t");
        when(exportService.fingerprints(any())).thenReturn(current);

        List<SourceChange> changes = service.changes(entry(shipped));

        assertThat(changes).containsExactlyInAnyOrder(
                new SourceChange("knowledge:search_kb/doc:Handbook", Change.CHANGED, "Handbook"),
                new SourceChange("knowledge:search_kb/doc:Old notes", Change.REMOVED, "Old notes"),
                new SourceChange("knowledge:search_kb/doc:Policies", Change.ADDED, "Policies"));
        assertThat(changes.getFirst().knowledge()).contains(new KnowledgeSourceKey("search_kb", "Handbook"));
        assertThat(KnowledgeSourceKey.parse("knowledge:search_kb/pipeline"))
                .contains(new KnowledgeSourceKey("search_kb", null));
        assertThat(KnowledgeSourceKey.parse("tool:uuid")).isEmpty();
        assertThat(KnowledgeSourceKey.parse("knowledge:search_kb")).isEmpty();
    }

    @Test
    void aBundleShippedWithAggregateKeysOnlyNeverReportsAddedDocuments() {
        Map<String, String> shipped = Map.of("knowledge:search_kb", "old");
        when(exportService.fingerprints(any())).thenReturn(Map.of(
                "knowledge:search_kb/pipeline", "p", "knowledge:search_kb/doc:Handbook", "x"));

        List<SourceChange> changes = service.changes(entry(shipped));

        assertThat(changes).containsExactly(new SourceChange("knowledge:search_kb", Change.REMOVED));
        assertThat(changes.getFirst().label()).isEqualTo("search_kb");
    }

    @Test
    void anUnchangedBundleHasNoChanges() {
        Map<String, String> shipped = Map.of("knowledge:search_kb/pipeline", "p", "tool:uuid", "t");
        when(exportService.fingerprints(any())).thenReturn(shipped);

        assertThat(service.changes(entry(shipped))).isEmpty();
    }

}
