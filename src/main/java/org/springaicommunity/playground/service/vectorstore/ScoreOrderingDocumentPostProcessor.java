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

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Post-Retrieval stage of Spring AI's Modular RAG: re-ranks by similarity score and truncates to
 * top-N. Re-ranking matters most after Multi-Query expansion, where results joined from several
 * variants arrive unordered; truncation trims the context window and mitigates lost-in-the-middle.
 */
public class ScoreOrderingDocumentPostProcessor implements DocumentPostProcessor {

    private final boolean reRankByScore;
    private final Integer topNTruncate;

    public ScoreOrderingDocumentPostProcessor(boolean reRankByScore, Integer topNTruncate) {
        this.reRankByScore = reRankByScore;
        this.topNTruncate = topNTruncate;
    }

    public boolean isNoOp() {
        return !this.reRankByScore && !truncates();
    }

    private boolean truncates() {
        return Objects.nonNull(this.topNTruncate) && this.topNTruncate > 0;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        List<Document> result = new ArrayList<>(documents);
        if (this.reRankByScore)
            result.sort((a, b) -> Double.compare(scoreOrZero(b), scoreOrZero(a)));
        if (truncates() && result.size() > this.topNTruncate)
            result = new ArrayList<>(result.subList(0, this.topNTruncate));
        return result;
    }

    private static double scoreOrZero(Document document) {
        return Objects.nonNull(document.getScore()) ? document.getScore() : 0d;
    }
}
