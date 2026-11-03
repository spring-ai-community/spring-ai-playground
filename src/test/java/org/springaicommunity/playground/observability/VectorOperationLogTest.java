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

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.observability.VectorOperationLog.RecentQuery;
import org.springaicommunity.playground.observability.VectorOperationLog.VectorOperation;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

import static org.assertj.core.api.Assertions.assertThat;

class VectorOperationLogTest {

    private static VectorOperation query(long startEpochMs, String conversationId) {
        return new VectorOperation(startEpochMs, 12, VectorOperationLog.QUERY, "simple", 4L, 0.5, 2,
                conversationId, false);
    }

    @Test
    void keepsOnlyTheLatestQueryTextsNewestFirstAndShortensLongOnes() {
        VectorOperationLog log = new VectorOperationLog();
        for (int index = 0; index < VectorOperationLog.RECENT_QUERY_LIMIT + 5; index++) {
            log.record(query(index, null), "question " + index);
        }
        log.record(query(1000, null), "spaced\n  out " + "x".repeat(300));

        assertThat(log.recentQueries()).hasSize(VectorOperationLog.RECENT_QUERY_LIMIT);
        RecentQuery newest = log.recentQueries().getFirst();
        assertThat(newest.text()).startsWith("spaced out xxx").endsWith("...").hasSize(203);
        assertThat(log.recentQueries()).extracting(RecentQuery::text).doesNotContain("question 0");
        assertThat(log.snapshot()).hasSize(VectorOperationLog.RECENT_QUERY_LIMIT + 6);
    }

    @Test
    void addAndDeleteOperationsAreCountedButLeaveNoQueryText() {
        VectorOperationLog log = new VectorOperationLog();

        log.record(new VectorOperation(1, 3, "add", "simple", null, null, null, null, false), "ignored");
        log.record(query(2, null), "  ");

        assertThat(log.snapshot()).hasSize(2);
        assertThat(log.recentQueries()).isEmpty();
    }

    @Test
    void aQueryIsAttributedOnlyToItsOwnConversationInsideTheTimeRange() {
        VectorOperationLog log = new VectorOperationLog();
        log.record(query(500, "Chat-a"), "wifi");
        log.record(new VectorOperation(500, 3, "add", "simple", null, null, null, "Chat-b", false), null);

        assertThat(log.hasQueryFor("Chat-a", 400, 600)).isTrue();
        assertThat(log.hasQueryFor("Chat-a", 501, 600)).isFalse();
        assertThat(log.hasQueryFor("Chat-b", 400, 600)).isFalse();
        assertThat(log.hasQueryFor(null, 400, 600)).isFalse();
    }

    @Test
    void toolIndexConventionTagsTheStoreRoleOnTopOfTheStandardKeys() {
        VectorStoreObservationContext context = VectorStoreObservationContext
                .builder("simple", VectorStoreObservationContext.Operation.QUERY).build();

        assertThat(new ToolIndexVectorObservationConvention().getLowCardinalityKeyValues(context))
                .contains(KeyValue.of(ToolIndexVectorObservationConvention.STORE_ROLE_KEY,
                        ToolIndexVectorObservationConvention.TOOL_INDEX_ROLE))
                .anyMatch(keyValue -> "db.system".equals(keyValue.getKey()));
    }
}
