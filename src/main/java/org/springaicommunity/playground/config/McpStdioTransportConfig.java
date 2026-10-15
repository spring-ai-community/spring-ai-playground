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

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
public class McpStdioTransportConfig {

    private static final Logger logger = LoggerFactory.getLogger(McpStdioTransportConfig.class);

    private final ConfigurableApplicationContext context;
    private final CountDownLatch ready = new CountDownLatch(1);

    public McpStdioTransportConfig(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Bean
    public StdioServerTransportProvider stdioServerTransport(JsonMapper mcpServerJsonMapper) {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(mcpServerJsonMapper),
                new GatedInputStream(System.in, this.ready, this::exitOnStdinClosed), System.out);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void openStdioChannel() {
        this.ready.countDown();
        logger.info("stdio MCP channel open; every tool was registered before the first request is read");
    }

    private void exitOnStdinClosed() {
        Thread exit = new Thread(() -> {
            logger.info("stdin closed; shutting down the stdio MCP server");
            int code = SpringApplication.exit(this.context, () -> 0);
            System.exit(code);
        }, "mcp-stdio-exit");
        exit.start();
    }

    public static final class GatedInputStream extends InputStream {

        private final InputStream delegate;
        private final CountDownLatch gate;
        private final Runnable onEndOfStream;
        private final AtomicBoolean endReported = new AtomicBoolean();

        public GatedInputStream(InputStream delegate, CountDownLatch gate, Runnable onEndOfStream) {
            this.delegate = delegate;
            this.gate = gate;
            this.onEndOfStream = onEndOfStream;
        }

        @Override
        public int read() throws IOException {
            awaitGate();
            return report(this.delegate.read());
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            awaitGate();
            return report(this.delegate.read(buffer, offset, length));
        }

        @Override
        public int available() throws IOException {
            return this.gate.getCount() == 0 ? this.delegate.available() : 0;
        }

        @Override
        public void close() {
        }

        private void awaitGate() throws IOException {
            try {
                this.gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted before the stdio channel opened");
            }
        }

        private int report(int result) {
            if (result < 0 && this.endReported.compareAndSet(false, true)) {
                this.onEndOfStream.run();
            }
            return result;
        }
    }

}
