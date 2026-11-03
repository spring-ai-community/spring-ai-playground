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
package org.springaicommunity.playground.service.mcp;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalWaitCountdownTest {

    private final ApprovalWaitCountdown countdown = new ApprovalWaitCountdown(3, Duration.ofMillis(20));
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final ApprovalWaitCountdown.Ticker ticker =
            (elapsed, wait, message) -> messages.add(elapsed + "/" + wait + " " + message);

    @Test
    void noticeNamesTheWait() {
        assertEquals("Answer within 3 s, or the call is refused.", countdown.notice());
        assertEquals(3, countdown.waitSeconds());
    }

    @Test
    void syncAwaitTicksUntilTheDecisionArrivesThenStops() throws InterruptedException {
        String result = countdown.await(() -> {
            pause(120);
            return "ok";
        }, ticker);

        assertEquals("ok", result);
        assertFalse(messages.isEmpty());
        assertEquals("0/3 Waiting for approval: 3 s left", messages.getFirst());
        int seen = messages.size();
        Thread.sleep(100);
        assertEquals(seen, messages.size());
    }

    @Test
    void asyncAwaitTicksWhileTheDecisionIsPendingThenStops() throws InterruptedException {
        String result = countdown.await(Mono.delay(Duration.ofMillis(120)).thenReturn("ok"), ticker).block();

        assertEquals("ok", result);
        assertFalse(messages.isEmpty());
        assertTrue(messages.getFirst().endsWith("Waiting for approval: 3 s left"));
        int seen = messages.size();
        Thread.sleep(100);
        assertEquals(seen, messages.size());
    }

    @Test
    void ticksStopOnceTheWaitHasElapsed() {
        ApprovalWaitCountdown oneSecond = new ApprovalWaitCountdown(1, Duration.ofMillis(500));
        List<Integer> elapsedSeconds = new CopyOnWriteArrayList<>();

        assertThrows(IllegalStateException.class, () -> oneSecond.await(() -> {
            pause(1400);
            return "late";
        }, (elapsed, wait, message) -> elapsedSeconds.add(elapsed)));

        assertEquals(List.of(0), elapsedSeconds);
    }

    @Test
    void aDecisionThatArrivesAfterTheWaitIsRefusedAsATimeout() {
        ApprovalWaitCountdown oneSecond = new ApprovalWaitCountdown(1, Duration.ofMillis(200));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> oneSecond.await(() -> {
            pause(1500);
            return "late";
        }, ticker));

        assertEquals("no approval within 1 s", refused.getMessage());
        assertInstanceOf(TimeoutException.class, refused.getCause());
    }

    @Test
    void anAsyncDecisionThatArrivesAfterTheWaitIsRefusedAsATimeout() {
        ApprovalWaitCountdown oneSecond = new ApprovalWaitCountdown(1, Duration.ofMillis(200));

        Throwable refused = assertThrows(Throwable.class,
                () -> oneSecond.await(Mono.delay(Duration.ofMillis(1500)).thenReturn("late"), ticker).block());

        assertTrue(refused instanceof TimeoutException || refused.getCause() instanceof TimeoutException,
                String.valueOf(refused));
    }

    @Test
    void aFailingDecisionSurfacesItsOwnError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> countdown.await(() -> {
            throw new IllegalArgumentException("transport broke");
        }, ticker));

        assertEquals("transport broke", failure.getMessage());
    }

    @Test
    void noTickerIsSilent() {
        assertEquals("ok", countdown.await(() -> "ok", ApprovalWaitCountdown.Ticker.NONE));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
