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
package org.springaicommunity.playground.config;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.config.McpStdioTransportConfig.GatedInputStream;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpStdioTransportConfigTest {

    @Test
    void nothingIsReadBeforeTheGateOpens() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GatedInputStream stream = new GatedInputStream(
                new ByteArrayInputStream("{\"jsonrpc\":\"2.0\"}\n".getBytes(StandardCharsets.UTF_8)), gate, () -> { });
        CompletableFuture<String> firstLine = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(stream.available()).isZero();
        assertThat(firstLine.isDone()).isFalse();
        Thread.sleep(200);
        assertThat(firstLine.isDone()).isFalse();

        gate.countDown();

        assertThat(firstLine.get(5, TimeUnit.SECONDS)).isEqualTo("{\"jsonrpc\":\"2.0\"}\n");
    }

    @Test
    void theEndOfStdinIsReportedOnce() throws Exception {
        CountDownLatch open = new CountDownLatch(0);
        AtomicInteger closed = new AtomicInteger();
        GatedInputStream stream = new GatedInputStream(new ByteArrayInputStream(new byte[0]), open,
                closed::incrementAndGet);

        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.read(new byte[8], 0, 8)).isEqualTo(-1);

        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void theTransportIsBuiltOnTheGatedStream() {
        assertThat(new McpStdioTransportConfig(null).stdioServerTransport(JsonMapper.builder().build())).isNotNull();
    }

}
