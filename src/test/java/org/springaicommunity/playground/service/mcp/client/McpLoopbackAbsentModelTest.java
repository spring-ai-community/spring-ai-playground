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
package org.springaicommunity.playground.service.mcp.client;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.config.AbsentModelFallbackConfig;
import org.springaicommunity.playground.service.mcp.McpServerInfo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "vaadin.productionMode=true", "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none" })
class McpLoopbackAbsentModelTest extends McpLoopbackClientTestSupport {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ChatClient chatClient;

    @Test
    void builtInMcpServerServesToolsWithoutModelProviders() throws Exception {
        McpServerInfo serverInfo = newServerInfo(McpTransportType.STREAMABLE_HTTP, "absent-model-loopback",
                new McpStreamableHttpClientProperties.ConnectionParameters(baseUrl(), null));
        this.mcpClientService.startMcpClient(serverInfo);
        try {
            List<McpSchema.Tool> tools = this.mcpClientService.getToolListAsOpt(serverInfo).orElseThrow();
            assertThat(tools).extracting(McpSchema.Tool::name).contains("evalExpression");

            McpSchema.CallToolResult result = this.mcpClientService
                    .callTool(serverInfo, "evalExpression", Map.of("expression", "7 * 6"), Map.of())
                    .orElseThrow();
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("42");
        } finally {
            this.mcpClientService.stopMcpClient(serverInfo);
        }
    }

    @Test
    void absentModelsFailFastWithConfigurationHint() {
        assertThat(this.chatClient).isNotNull();
        assertThatThrownBy(() -> this.chatModel.call(new Prompt("hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AbsentModelFallbackConfig.CHAT_MODEL_ABSENT);
        assertThatThrownBy(() -> this.embeddingModel.embed("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AbsentModelFallbackConfig.EMBEDDING_MODEL_ABSENT);
    }

}
