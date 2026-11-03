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
import org.springaicommunity.playground.service.mcp.client.McpClientService;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.playground.hitl.approval-wait-seconds=42")
class McpServerRequestTimeoutAlignerTest {

    @Autowired
    private McpServerProperties serverProperties;

    @Autowired
    private McpClientService mcpClientService;

    @Test
    void serverRequestTimeoutFollowsTheApprovalWaitPlusMargin() {
        assertThat(this.serverProperties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(72));
        assertThat(this.mcpClientService.approvalWait()).isEqualTo(Duration.ofSeconds(42));
    }
}
