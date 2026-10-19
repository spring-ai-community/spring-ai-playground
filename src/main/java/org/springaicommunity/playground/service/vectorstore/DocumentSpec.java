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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.EnrichOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ExtractOptions;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.ReaderType;
import org.springaicommunity.playground.service.vectorstore.OfflineEtlPipelineService.TokenTextSplitInfo;

import org.springframework.lang.Nullable;

import java.util.Objects;

public record DocumentSpec(
        @JsonPropertyDescription("Where the document comes from: exactly one of path, url or text.") Source source,
        @Nullable @JsonPropertyDescription("Document title, the key the app matches on; default the file name, "
                + "required for an inline text source.") String title,
        @Nullable @JsonPropertyDescription("What the document is about; shown to models that search it.")
        String description,
        @Nullable @JsonPropertyDescription("Reader settings; unset fields use the upload dialog defaults.")
        Extract extract,
        @Nullable @JsonPropertyDescription("Token splitter settings; unset fields use the defaults 800/350/5/10000.")
        Split split,
        @Nullable @JsonPropertyDescription("Metadata enrichment; each enabled enricher is one chat-model call per "
                + "chunk and needs a chat model.") Enrich enrich) {

    public DocumentSpec {
        extract = extract == null ? Extract.defaults() : extract;
        split = split == null ? Split.defaults() : split;
        enrich = enrich == null ? Enrich.defaults() : enrich;
    }

    public static DocumentSpec of(Source source, String title, String description, ExtractOptions extractOptions,
            TokenTextSplitInfo splitInfo, EnrichOptions enrichOptions) {
        return new DocumentSpec(source, title, description, Extract.of(extractOptions), Split.of(splitInfo),
                Enrich.of(enrichOptions));
    }

    public DocumentSpec withoutEnrichment() {
        return new DocumentSpec(source, title, description, extract, split, Enrich.defaults());
    }

    public String resolvedTitle(String fileName) {
        return title == null || title.isBlank() ? fileName : title.trim();
    }

    public String resolvedDescription() {
        return description == null || description.isBlank() ? null : description.trim();
    }

    public record Source(
            @Nullable @JsonPropertyDescription("Absolute path of a local file inside spring.ai.playground.rag.ingest-roots.") String path,
            @Nullable @JsonPropertyDescription("http or https URL to download, up to the upload size limit.")
            String url,
            @Nullable @JsonPropertyDescription("Inline document body; ingested as a text file.") String text) {

        public enum Kind { PATH, URL, TEXT }

        public static Source path(String path) {
            return new Source(path, null, null);
        }

        public static Source url(String url) {
            return new Source(null, url, null);
        }

        public static Source text(String text) {
            return new Source(null, null, text);
        }

        public Kind kind() {
            int declared = (present(path) ? 1 : 0) + (present(url) ? 1 : 0) + (present(text) ? 1 : 0);
            if (declared != 1) {
                throw new IllegalArgumentException("source needs exactly one of path, url or text");
            }
            if (present(path)) return Kind.PATH;
            return present(url) ? Kind.URL : Kind.TEXT;
        }

        public String describe() {
            return switch (kind()) {
                case PATH -> path.trim();
                case URL -> url.trim();
                case TEXT -> "inline text (" + text.length() + " chars)";
            };
        }

        private static boolean present(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Extract(
            @Nullable @JsonPropertyDescription("TIKA, TEXT, JSON, MARKDOWN, HTML, PDF_PAGE or PDF_PARAGRAPH; default "
                    + "by file extension.") ReaderType reader,
            @Nullable @JsonPropertyDescription("Charset for TEXT and HTML, default UTF-8.") String charset,
            @Nullable @JsonPropertyDescription("Comma separated JSON keys to read for JSON, default all.")
            String jsonKeys,
            @Nullable @JsonPropertyDescription("CSS selector for HTML, default body.") String htmlSelector,
            @Nullable Boolean markdownIncludeCodeBlock,
            @Nullable Boolean markdownIncludeBlockquote,
            @Nullable @JsonPropertyDescription("Start a new document at every horizontal rule, default false.")
            Boolean markdownHorizontalRuleCreateDocument,
            @Nullable @JsonPropertyDescription("Pages per document for PDF_PAGE, default 1.")
            Integer pdfPagesPerDocument) {

        public static Extract defaults() {
            return new Extract(null, null, null, null, null, null, null, null);
        }

        public static Extract of(ExtractOptions options) {
            if (options == null) return defaults();
            return new Extract(options.readerType(), options.charset(), options.jsonKeys(), options.htmlSelector(),
                    options.markdownIncludeCodeBlock(), options.markdownIncludeBlockquote(),
                    options.markdownHorizontalRuleCreateDocument(), options.pdfPagesPerDocument());
        }

        public ExtractOptions toOptions(ReaderType recommended) {
            ExtractOptions base = ExtractOptions.defaults(reader == null ? recommended : reader);
            return new ExtractOptions(base.readerType(), orDefault(charset, base.charset()),
                    orDefault(jsonKeys, base.jsonKeys()), orDefault(htmlSelector, base.htmlSelector()),
                    orDefault(markdownIncludeCodeBlock, base.markdownIncludeCodeBlock()),
                    orDefault(markdownIncludeBlockquote, base.markdownIncludeBlockquote()),
                    orDefault(markdownHorizontalRuleCreateDocument, base.markdownHorizontalRuleCreateDocument()),
                    orDefault(pdfPagesPerDocument, base.pdfPagesPerDocument()));
        }
    }

    public record Split(
            @Nullable @JsonPropertyDescription("Target chunk size in tokens, default 800.") Integer chunkSize,
            @Nullable @JsonPropertyDescription("Minimum chunk size in characters, default 350.")
            Integer minChunkSizeChars,
            @Nullable @JsonPropertyDescription("Chunks shorter than this are dropped, default 5.")
            Integer minChunkLengthToEmbed,
            @Nullable @JsonPropertyDescription("Upper bound on chunks per document, default 10000.")
            Integer maxNumChunks,
            @Nullable @JsonPropertyDescription("Keep separators inside chunks, default true.") Boolean keepSeparator,
            @Nullable @JsonPropertyDescription("Tokenizer encoding, default CL100K_BASE.") String encodingType) {

        public static Split defaults() {
            return new Split(null, null, null, null, null, null);
        }

        public static Split of(TokenTextSplitInfo info) {
            if (info == null) return defaults();
            return new Split(info.chunkSize(), info.minChunkSizeChars(), info.minChunkLengthToEmbed(),
                    info.maxNumChunks(), info.keepSeparator(), info.encodingType());
        }

        public TokenTextSplitInfo toInfo() {
            TokenTextSplitInfo base = OfflineEtlPipelineService.DEFAULT_TOKEN_TEXT_SPLIT_INFO;
            return new TokenTextSplitInfo(orDefault(chunkSize, base.chunkSize()),
                    orDefault(minChunkSizeChars, base.minChunkSizeChars()),
                    orDefault(minChunkLengthToEmbed, base.minChunkLengthToEmbed()),
                    orDefault(maxNumChunks, base.maxNumChunks()), orDefault(keepSeparator, base.keepSeparator()),
                    orDefault(encodingType, base.encodingType()));
        }
    }

    public record Enrich(
            @Nullable @JsonPropertyDescription("Extract keywords per chunk with the chat model, default false.")
            Boolean keyword,
            @Nullable @JsonPropertyDescription("Keywords per chunk, default 5.") Integer keywordCount,
            @Nullable @JsonPropertyDescription("Summarize each chunk with the chat model, default false.")
            Boolean summary,
            @Nullable @JsonPropertyDescription("Also keep the previous chunk's summary, default true.")
            Boolean summaryPrevious,
            @Nullable @JsonPropertyDescription("Also keep the next chunk's summary, default true.")
            Boolean summaryNext) {

        public static Enrich defaults() {
            return new Enrich(null, null, null, null, null);
        }

        public static Enrich of(EnrichOptions options) {
            if (options == null) return defaults();
            return new Enrich(options.keywordEnabled(), options.keywordCount(), options.summaryEnabled(),
                    options.summaryPrevious(), options.summaryNext());
        }

        public EnrichOptions toOptions() {
            return new EnrichOptions(orDefault(keyword, false), orDefault(keywordCount, 5),
                    orDefault(summary, false), orDefault(summaryPrevious, true), orDefault(summaryNext, true));
        }
    }

    private static <T> T orDefault(T value, T fallback) {
        return Objects.requireNonNullElse(value, fallback);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

}
