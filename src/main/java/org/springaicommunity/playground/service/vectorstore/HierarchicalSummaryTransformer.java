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

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public class HierarchicalSummaryTransformer implements DocumentTransformer {

    public static final String LEVEL = "level";
    public static final String COVERS = "covers";
    public static final int DEFAULT_WINDOW_SIZE = 6;

    private static final String SECTION_PROMPT = """
            Summarize the key topics, entities, decisions, and figures of this document section in at most 5 \
            sentences. Keep concrete names and numbers.

            Section:
            %s

            Summary:""";
    private static final String DOCUMENT_PROMPT = """
            Write a compact overview of the document "%s" based on its section summaries: state its purpose, \
            structure, main topics, and notable facts in at most 8 sentences.

            Section summaries:
            %s

            Overview:""";

    private final ChatModel chatModel;
    private final int windowSize;

    public HierarchicalSummaryTransformer(ChatModel chatModel) {
        this(chatModel, DEFAULT_WINDOW_SIZE);
    }

    public HierarchicalSummaryTransformer(ChatModel chatModel, int windowSize) {
        this.chatModel = chatModel;
        this.windowSize = Math.max(2, windowSize);
    }

    public int plannedCalls(int chunkCount) {
        if (chunkCount <= this.windowSize) return 1;
        int calls = 0;
        int size = chunkCount;
        while (size > this.windowSize) {
            int windows = windowCount(size);
            calls += windows;
            size = windows;
        }
        return calls + 1;
    }

    @Override
    public List<Document> apply(List<Document> chunks) {
        List<Document> result = new ArrayList<>(chunks);
        List<String> texts = textsOf(chunks);
        if (texts.isEmpty()) return result;
        String source = chunks.getFirst().getMetadata().getOrDefault("source", "document").toString();
        if (texts.size() > this.windowSize) {
            List<String> sections = summarizeWindows(texts, () -> false, ignored -> {});
            for (int i = 0; i < sections.size(); i++) {
                int from = i * this.windowSize;
                int to = Math.min(texts.size(), from + this.windowSize) - 1;
                result.add(new Document(sections.get(i),
                        Map.of(LEVEL, 2, COVERS, from + "-" + to, "source", source)));
            }
            texts = sections;
        }
        String overview = summarizeTexts(source, texts, () -> false, ignored -> {});
        if (StringUtils.hasText(overview))
            result.add(new Document(overview, Map.of(LEVEL, 3, "source", source)));
        return result;
    }

    public String summarize(String fileName, List<Document> chunks, BooleanSupplier cancelled,
            IntConsumer progress) {
        return summarizeTexts(fileName, textsOf(chunks), cancelled, progress);
    }

    private String summarizeTexts(String fileName, List<String> texts, BooleanSupplier cancelled,
            IntConsumer progress) {
        if (texts.isEmpty()) return null;
        int done = 0;
        List<String> current = texts;
        while (current.size() > this.windowSize) {
            List<String> reduced = new ArrayList<>();
            for (int i = 0; i < current.size(); i += this.windowSize) {
                if (cancelled.getAsBoolean()) return null;
                String window = String.join("\n\n",
                        current.subList(i, Math.min(current.size(), i + this.windowSize)));
                reduced.add(call(SECTION_PROMPT.formatted(window)));
                progress.accept(++done);
            }
            current = reduced;
        }
        if (cancelled.getAsBoolean()) return null;
        String overview = call(DOCUMENT_PROMPT.formatted(fileName, String.join("\n\n", current)));
        progress.accept(done + 1);
        return overview;
    }

    private List<String> summarizeWindows(List<String> texts, BooleanSupplier cancelled, IntConsumer progress) {
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += this.windowSize) {
            if (cancelled.getAsBoolean()) return sections;
            String window = String.join("\n\n", texts.subList(i, Math.min(texts.size(), i + this.windowSize)));
            sections.add(call(SECTION_PROMPT.formatted(window)));
            progress.accept(i / this.windowSize + 1);
        }
        return sections;
    }

    private int windowCount(int size) {
        return (size + this.windowSize - 1) / this.windowSize;
    }

    private String call(String prompt) {
        return Optional.ofNullable(this.chatModel.call(new Prompt(prompt)).getResult())
                .map(generation -> generation.getOutput().getText()).map(String::trim).orElse("");
    }

    private static List<String> textsOf(List<Document> chunks) {
        return chunks.stream().map(Document::getText).filter(StringUtils::hasText).collect(Collectors.toList());
    }
}
