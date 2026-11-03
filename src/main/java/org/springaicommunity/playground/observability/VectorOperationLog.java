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
package org.springaicommunity.playground.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class VectorOperationLog extends BoundedRingBuffer<VectorOperationLog.VectorOperation> {

    public static final String QUERY = "query";
    public static final int RECENT_QUERY_LIMIT = 20;

    private static final int OPERATION_CAPACITY = 5000;
    private static final int QUERY_TEXT_LIMIT = 200;

    public record VectorOperation(long startEpochMs, long durationMs, String operation, String dbSystem, Long topK,
            Double similarityThreshold, Integer returnedDocuments, String conversationId, boolean failed) {

        public boolean isQuery() {
            return QUERY.equals(this.operation);
        }
    }

    public record RecentQuery(long epochMs, String text, Integer returnedDocuments, long durationMs,
            String conversationId) {}

    private final Deque<RecentQuery> recentQueries = new ConcurrentLinkedDeque<>();

    public VectorOperationLog() {
        super(OPERATION_CAPACITY, 100);
    }

    public void record(VectorOperation operation, String queryText) {
        addWithEviction(operation);
        if (!operation.isQuery() || queryText == null || queryText.isBlank()) return;
        while (this.recentQueries.size() >= RECENT_QUERY_LIMIT) {
            this.recentQueries.pollFirst();
        }
        this.recentQueries.addLast(new RecentQuery(operation.startEpochMs(), shorten(queryText),
                operation.returnedDocuments(), operation.durationMs(), operation.conversationId()));
    }

    public List<RecentQuery> recentQueries() {
        List<RecentQuery> newestFirst = new ArrayList<>(this.recentQueries);
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    public boolean hasQueryFor(String conversationId, long fromEpochMs, long toEpochMs) {
        if (conversationId == null || conversationId.isBlank()) return false;
        return this.buffer.stream().anyMatch(operation -> operation.isQuery()
                && conversationId.equals(operation.conversationId())
                && operation.startEpochMs() >= fromEpochMs && operation.startEpochMs() <= toEpochMs);
    }

    private static String shorten(String queryText) {
        String singleLine = queryText.strip().replaceAll("\\s+", " ");
        return singleLine.length() <= QUERY_TEXT_LIMIT ? singleLine
                : singleLine.substring(0, QUERY_TEXT_LIMIT) + "...";
    }
}
