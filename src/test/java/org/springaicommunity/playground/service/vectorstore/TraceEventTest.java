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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceEventTest {

    @Test
    void infoFactoryReturnsInfoLevelWithCurrentTimestamp() {
        long before = System.currentTimeMillis();
        TraceEvent event = TraceEvent.info("retrieve", "done", "payload");
        long after = System.currentTimeMillis();
        assertEquals("retrieve", event.stage());
        assertEquals(TraceEvent.Level.INFO, event.level());
        assertEquals("done", event.message());
        assertEquals("payload", event.payload());
        assertTrue(event.timestampMs() >= before, "timestamp before window");
        assertTrue(event.timestampMs() <= after, "timestamp after window");
    }

    @Test
    void infoWithoutPayloadHasNullPayload() {
        TraceEvent event = TraceEvent.info("rewrite", "started");
        assertNull(event.payload());
        assertEquals(TraceEvent.Level.INFO, event.level());
    }

    @Test
    void warnFactoryReturnsWarnLevel() {
        TraceEvent event = TraceEvent.warn("rewrite", "skipped");
        assertEquals(TraceEvent.Level.WARN, event.level());
        assertEquals("skipped", event.message());
        assertNull(event.payload());
    }

    @Test
    void errorFactoryReturnsErrorLevel() {
        TraceEvent event = TraceEvent.error("retrieve", "boom");
        assertEquals(TraceEvent.Level.ERROR, event.level());
        assertEquals("boom", event.message());
        assertNull(event.payload());
    }
}
