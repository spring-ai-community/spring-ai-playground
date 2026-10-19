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
package org.springaicommunity.playground.service.vectorstore;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ReaderType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagOptionsBindingTest {

    @Test
    void ragKeysBindFromProperties() {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("spring.ai.playground.rag.ingest-roots[0]", "~/saip-inbox"),
                Map.entry("spring.ai.playground.rag.documents[0].source.path", "~/saip-inbox/handbook.pdf"),
                Map.entry("spring.ai.playground.rag.documents[0].title", "Field Service Handbook"),
                Map.entry("spring.ai.playground.rag.documents[0].extract.reader", "PDF_PAGE"),
                Map.entry("spring.ai.playground.rag.documents[0].split.chunk-size", "600"),
                Map.entry("spring.ai.playground.rag.documents[0].enrich.summary", "true"),
                Map.entry("spring.ai.playground.rag.documents[1].source.text", "Berths are allocated on Monday."),
                Map.entry("spring.ai.playground.rag.documents[1].title", "Berth rule"),
                Map.entry("spring.ai.playground.rag.pipelines[0].name", "Advanced RAG"),
                Map.entry("spring.ai.playground.rag.pipelines[0].documents[0]", "Field Service Handbook"),
                Map.entry("spring.ai.playground.rag.pipelines[0].pre-retrieval.rewrite", "true"),
                Map.entry("spring.ai.playground.rag.pipelines[0].retrieval.top-k", "6"),
                Map.entry("spring.ai.playground.rag.pipelines[0].generation.document-format", "TEXT_WITH_SOURCE"));

        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);

        assertThat(options.rag().ingestRoots()).containsExactly("~/saip-inbox");
        assertThat(options.rag().documents()).hasSize(2);
        DocumentSpec handbook = options.rag().documents().getFirst();
        assertThat(handbook.source().kind()).isEqualTo(DocumentSpec.Source.Kind.PATH);
        assertThat(handbook.title()).isEqualTo("Field Service Handbook");
        assertThat(handbook.extract().toOptions(ReaderType.TIKA).readerType()).isEqualTo(ReaderType.PDF_PAGE);
        assertThat(handbook.split().toInfo().chunkSize()).isEqualTo(600);
        assertThat(handbook.enrich().toOptions().summaryEnabled()).isTrue();
        assertThat(handbook.enrich().toOptions().llmCallsPerChunk()).isEqualTo(1);
        assertThat(options.rag().documents().get(1).source().kind()).isEqualTo(DocumentSpec.Source.Kind.TEXT);
        PipelineSpec pipeline = options.rag().pipelines().getFirst();
        assertThat(pipeline.name()).isEqualTo("Advanced RAG");
        assertThat(pipeline.documents()).containsExactly("Field Service Handbook");
        assertThat(pipeline.preRetrieval().toConfig().rewrite()).isTrue();
        assertThat(pipeline.retrieval().toConfig().topK()).isEqualTo(6);
        assertThat(pipeline.generation().toConfig().documentFormat())
                .isEqualTo(RagPipeline.DocumentFormat.TEXT_WITH_SOURCE);
    }

    @Test
    void ragKeysBindFromEnvironmentVariables() {
        SystemEnvironmentPropertySource environment = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "SPRING_AI_PLAYGROUND_RAG_INGESTROOTS_0", "/srv/inbox",
                "SPRING_AI_PLAYGROUND_RAG_DOCUMENTS_0_SOURCE_URL", "https://example.com/notes.md",
                "SPRING_AI_PLAYGROUND_RAG_DOCUMENTS_0_SPLIT_CHUNKSIZE", "500",
                "SPRING_AI_PLAYGROUND_RAG_DOCUMENTS_0_EXTRACT_MARKDOWNINCLUDECODEBLOCK", "false",
                "SPRING_AI_PLAYGROUND_RAG_PIPELINES_0_NAME", "Notes",
                "SPRING_AI_PLAYGROUND_RAG_PIPELINES_0_PRERETRIEVAL_MULTIQUERYCOUNT", "5",
                "SPRING_AI_PLAYGROUND_RAG_PIPELINES_0_RETRIEVAL_SIMILARITYTHRESHOLD", "0.4"));

        SpringAiPlaygroundOptions options = new Binder(ConfigurationPropertySources.from(environment))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);

        assertThat(options.rag().ingestRoots()).containsExactly("/srv/inbox");
        DocumentSpec notes = options.rag().documents().getFirst();
        assertThat(notes.source().kind()).isEqualTo(DocumentSpec.Source.Kind.URL);
        assertThat(notes.split().toInfo().chunkSize()).isEqualTo(500);
        assertThat(notes.extract().toOptions(ReaderType.MARKDOWN).markdownIncludeCodeBlock()).isFalse();
        PipelineSpec pipeline = options.rag().pipelines().getFirst();
        assertThat(pipeline.name()).isEqualTo("Notes");
        assertThat(pipeline.preRetrieval().toConfig().multiQueryCount()).isEqualTo(5);
        assertThat(pipeline.retrieval().toConfig().similarityThreshold()).isEqualTo(0.4);
    }

    @Test
    void anAbsentRagBlockYieldsEmptyLists() {
        SpringAiPlaygroundOptions options = new SpringAiPlaygroundOptions(null, false, null, null, null, null, null,
                null, null, null);

        assertThat(options.rag().ingestRoots()).isEqualTo(List.of());
        assertThat(options.rag().documents()).isEmpty();
        assertThat(options.rag().pipelines()).isEmpty();
    }

}
