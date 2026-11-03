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
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.EnrichOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ExtractOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ReaderType;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.TokenTextSplitInfo;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSpecRoundTripTest {

    @Test
    void anEmptySpecResolvesToTheUploadDialogDefaults() {
        DocumentSpec spec = new DocumentSpec(DocumentSpec.Source.text("hello"), null, null, null, null, null);

        assertThat(spec.extract().toOptions(ReaderType.MARKDOWN)).isEqualTo(ExtractOptions.defaults(ReaderType.MARKDOWN));
        assertThat(spec.split().toInfo()).isEqualTo(OfflineEtlPipelineService.DEFAULT_TOKEN_TEXT_SPLIT_INFO);
        assertThat(spec.enrich().toOptions().llmCallsPerChunk()).isZero();
        assertThat(spec.enrich().toOptions().keywordCount()).isEqualTo(5);
        assertThat(spec.resolvedTitle("notes.txt")).isEqualTo("notes.txt");
    }

    @Test
    void explicitOptionsSurviveTheRoundTrip() {
        ExtractOptions extract = new ExtractOptions(ReaderType.PDF_PAGE, "EUC-KR", "a,b", "main", false, false, true, 3);
        TokenTextSplitInfo split = new TokenTextSplitInfo(600, 200, 10, 500, false, "O200K_BASE");
        EnrichOptions enrich = new EnrichOptions(true, 7, true, false, true);

        DocumentSpec spec = DocumentSpec.of(DocumentSpec.Source.path("~/inbox/a.pdf"), " Title ", " ", extract, split,
                enrich);

        assertThat(spec.extract().toOptions(ReaderType.TIKA)).isEqualTo(extract);
        assertThat(spec.split().toInfo()).isEqualTo(split);
        assertThat(spec.enrich().toOptions()).isEqualTo(enrich);
        assertThat(spec.resolvedTitle("a.pdf")).isEqualTo("Title");
        assertThat(spec.resolvedDescription()).isNull();
        assertThat(spec.withoutEnrichment().enrich().toOptions().llmCallsPerChunk()).isZero();
    }

    @Test
    void aSourceNeedsExactlyOneOfPathUrlOrText() {
        assertThatThrownBy(() -> new DocumentSpec.Source(null, "", " ").kind())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentSpec.Source("/a", "http://b", null).kind())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentSpec.Source.url("https://x/y.md").kind()).isEqualTo(DocumentSpec.Source.Kind.URL);
        assertThat(DocumentSpec.Source.text("abc").describe()).isEqualTo("inline text (3 chars)");
    }

    @Test
    void theSpecBindsFromKebabCaseConfigurationKeys() {
        Map<String, Object> properties = Map.of(
                "doc.source.path", "~/inbox/handbook.pdf",
                "doc.title", "Field Service Handbook",
                "doc.extract.reader", "PDF_PAGE",
                "doc.extract.pdf-pages-per-document", "2",
                "doc.split.chunk-size", "600",
                "doc.split.encoding-type", "O200K_BASE",
                "doc.enrich.keyword", "true",
                "doc.enrich.keyword-count", "3");
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));

        DocumentSpec spec = binder.bind("doc", Bindable.of(DocumentSpec.class)).get();

        assertThat(spec.source().kind()).isEqualTo(DocumentSpec.Source.Kind.PATH);
        assertThat(spec.title()).isEqualTo("Field Service Handbook");
        ExtractOptions extract = spec.extract().toOptions(ReaderType.TIKA);
        assertThat(extract.readerType()).isEqualTo(ReaderType.PDF_PAGE);
        assertThat(extract.pdfPagesPerDocument()).isEqualTo(2);
        assertThat(extract.charset()).isEqualTo("UTF-8");
        TokenTextSplitInfo split = spec.split().toInfo();
        assertThat(split.chunkSize()).isEqualTo(600);
        assertThat(split.minChunkSizeChars()).isEqualTo(350);
        assertThat(split.encodingType()).isEqualTo("O200K_BASE");
        assertThat(spec.enrich().toOptions()).isEqualTo(new EnrichOptions(true, 3, false, true, true));
    }

    @Test
    void thePipelineSpecBindsFromKebabCaseConfigurationKeysAndFillsDefaults() {
        Map<String, Object> properties = Map.of(
                "p.name", "Advanced RAG",
                "p.documents[0]", "Field Service Handbook",
                "p.pre-retrieval.rewrite", "true",
                "p.pre-retrieval.multi-query-count", "5",
                "p.retrieval.top-k", "6",
                "p.generation.run-augment-llm", "true",
                "p.generation.document-format", "TEXT_WITH_SOURCE");
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));

        PipelineSpec spec = binder.bind("p", Bindable.of(PipelineSpec.class)).get();

        assertThat(spec.documents()).isEqualTo(List.of("Field Service Handbook"));
        RagPipeline.PreRetrievalConfig pre = spec.preRetrieval().toConfig();
        assertThat(pre.rewrite()).isTrue();
        assertThat(pre.multiQuery()).isFalse();
        assertThat(pre.multiQueryCount()).isEqualTo(5);
        assertThat(pre.multiQueryIncludeOriginal()).isTrue();
        assertThat(pre.translationTargetLanguage()).isEqualTo("english");
        assertThat(spec.retrieval().toConfig().topK()).isEqualTo(6);
        assertThat(spec.postRetrieval().toConfig()).isEqualTo(RagPipeline.PostRetrievalConfig.defaults());
        RagPipeline.GenerationConfig generation = spec.generation().toConfig();
        assertThat(generation.runAugmentLLM()).isTrue();
        assertThat(generation.documentFormat()).isEqualTo(RagPipeline.DocumentFormat.TEXT_WITH_SOURCE);
        assertThat(generation.allowEmptyContext()).isFalse();
    }

    @Test
    void aPipelineSpecRoundTripsThroughAStoredPipeline() {
        RagPipeline pipeline = new RagPipeline("id-1", "Advanced RAG", "desc", List.of("d1"),
                new RagPipeline.PreRetrievalConfig(true, "custom {query} {target}", false, null, true, "korean", null,
                        true, 4, false, null, "knowledge base"),
                new RagPipeline.RetrievalConfig("type == 'a'", 7, 0.5),
                new RagPipeline.PostRetrievalConfig(false, 3),
                new RagPipeline.GenerationConfig(true, "ctx {context} {query}", null, true,
                        RagPipeline.DocumentFormat.TEXT_WITH_SOURCE), 1L, 2L);

        PipelineSpec spec = PipelineSpec.of(pipeline, List.of("Handbook"));

        assertThat(spec.documents()).containsExactly("Handbook");
        assertThat(spec.preRetrieval().toConfig()).isEqualTo(pipeline.preRetrieval());
        assertThat(spec.retrieval().toConfig()).isEqualTo(pipeline.retrieval());
        assertThat(spec.postRetrieval().toConfig()).isEqualTo(pipeline.postRetrieval());
        assertThat(spec.generation().toConfig()).isEqualTo(pipeline.generation());
    }

}
