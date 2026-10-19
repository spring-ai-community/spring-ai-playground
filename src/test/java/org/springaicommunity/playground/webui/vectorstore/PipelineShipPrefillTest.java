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
package org.springaicommunity.playground.webui.vectorstore;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.vectorstore.KnowledgeSearchService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.VectorStoreDocumentInfo;
import org.springaicommunity.playground.webui.shipyard.ShipFlowPrefill;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineShipPrefillTest {

    @Test
    void aStoredPipelineShipsUnderItsIdAndADocumentPipelineUnderItsDocumentSource() {
        RagPipeline stored = new RagPipeline("p-1", "KB", null, List.of(), null, null, null, null, 0L, 0L);
        RagPipeline document = KnowledgeSearchService.documentPipeline(new VectorStoreDocumentInfo("d-1",
                "Handbook", 0L, 0L, "handbook.pdf", "/x/handbook.pdf", List::of));

        assertThat(VectorStorePipelineRunView.knowledgeSourceId(stored)).isEqualTo("p-1");
        assertThat(VectorStorePipelineRunView.knowledgeSourceId(document)).isEqualTo("doc:d-1");
    }

    @Test
    void theShipPrefillCarriesTheSourceAndTheLastQuery() {
        ShipFlowPrefill prefill = ShipFlowPrefill.knowledge("p-1", "When is the dry dock closed?");

        assertThat(prefill.request().knowledge()).singleElement()
                .satisfies(knowledge -> assertThat(knowledge.sourceId()).isEqualTo("p-1"));
        assertThat(prefill.request().toolIds()).isEmpty();
        assertThat(prefill.tryQueries()).isEqualTo(Map.of("p-1", "When is the dry dock closed?"));
        assertThat(ShipFlowPrefill.knowledge("p-1", " ").tryQueries()).isEmpty();
    }

}
