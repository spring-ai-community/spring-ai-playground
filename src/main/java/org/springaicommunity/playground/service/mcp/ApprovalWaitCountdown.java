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

import org.springaicommunity.playground.SpringAiPlaygroundOptions.Hitl;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class ApprovalWaitCountdown {

    private static final Duration DEFAULT_TICK = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface Ticker {

        Ticker NONE = (elapsedSeconds, waitSeconds, message) -> { };

        void tick(int elapsedSeconds, int waitSeconds, String message);

    }

    private final int waitSeconds;
    private final Duration tick;

    public ApprovalWaitCountdown(int waitSeconds, Duration tick) {
        this.waitSeconds = waitSeconds;
        this.tick = tick;
    }

    public static ApprovalWaitCountdown of(Hitl hitl) {
        return new ApprovalWaitCountdown(hitl.approvalWaitSeconds(), DEFAULT_TICK);
    }

    public int waitSeconds() {
        return this.waitSeconds;
    }

    public String notice() {
        return "Answer within " + this.waitSeconds + " s, or the call is refused.";
    }

    public <T> T await(Supplier<T> decision, Ticker ticker) {
        Disposable ticks = startTicking(ticker);
        CompletableFuture<T> pending = new CompletableFuture<>();
        Thread.ofVirtual().name("approval-wait").start(() -> {
            try {
                pending.complete(decision.get());
            } catch (Throwable error) {
                pending.completeExceptionally(error);
            }
        });
        try {
            return pending.get(this.waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException expired) {
            throw new IllegalStateException("no approval within " + this.waitSeconds + " s", expired);
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for approval", interrupted);
        } finally {
            ticks.dispose();
        }
    }

    public <T> Mono<T> await(Mono<T> decision, Ticker ticker) {
        return Mono.using(() -> startTicking(ticker),
                ticks -> decision.timeout(Duration.ofSeconds(this.waitSeconds)), Disposable::dispose);
    }

    private Disposable startTicking(Ticker ticker) {
        return Flux.interval(this.tick)
                .map(index -> (int) ((index + 1) * this.tick.toMillis() / 1000))
                .takeWhile(elapsed -> elapsed < this.waitSeconds)
                .subscribe(elapsed -> ticker.tick(elapsed, this.waitSeconds, remainingMessage(elapsed)));
    }

    private String remainingMessage(int elapsedSeconds) {
        return "Waiting for approval: " + (this.waitSeconds - elapsedSeconds) + " s left";
    }

}
