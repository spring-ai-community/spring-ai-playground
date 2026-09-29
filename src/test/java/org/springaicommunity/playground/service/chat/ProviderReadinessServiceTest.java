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
package org.springaicommunity.playground.service.chat;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.config.AbsentModelFallbackConfig;
import org.springaicommunity.playground.service.chat.ProviderReadinessService.ChatProviderStatus;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ProviderReadinessServiceTest {

    static class OllamaChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().model("qwen3").build();
        }
    }

    static class OpenAiChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }
    }

    static class OllamaEmbeddingModelStub implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return null;
        }

        @Override
        public float[] embed(Document document) {
            return new float[0];
        }

        @Override
        public int dimensions() {
            return 7;
        }
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static ProviderReadinessService service(ChatModel chatModel, EmbeddingModel embeddingModel,
            EmbeddingOptions embeddingOptions, MockEnvironment environment) {
        return new ProviderReadinessService(provider(chatModel), provider(embeddingModel),
                Optional.ofNullable(embeddingOptions), environment);
    }

    @Test
    void chatProviderReportsConfigurationAndReadinessPerProvider() {
        MockEnvironment noKey = new MockEnvironment().withProperty("spring.ai.ollama.base-url", "http://127.0.0.1:1");
        ProviderReadinessService ollama = service(new OllamaChatModel(), null, null, noKey);
        ChatProviderStatus status = ollama.chatProvider();
        assertThat(status.configured()).isTrue();
        assertThat(status.provider()).isEqualTo("Ollama");
        assertThat(status.model()).isEqualTo("qwen3");
        assertThat(ollama.isChatProviderLikelyReady()).isTrue();
        assertThat(ollama.isChatProviderReady()).as("nothing listens on port 1").isFalse();

        ProviderReadinessService openAiNoKey = service(new OpenAiChatModel(), null, null, noKey);
        assertThat(openAiNoKey.chatProvider().provider()).isEqualTo("OpenAI");
        assertThat(openAiNoKey.isChatProviderLikelyReady()).isFalse();
        assertThat(openAiNoKey.isChatProviderReady()).isFalse();
        ProviderReadinessService openAiKey = service(new OpenAiChatModel(), null, null,
                new MockEnvironment().withProperty("spring.ai.openai.api-key", "sk-test"));
        assertThat(openAiKey.isChatProviderLikelyReady()).isTrue();
        assertThat(openAiKey.isChatProviderReady()).isTrue();

        ProviderReadinessService absent = service(new AbsentModelFallbackConfig.AbsentChatModel(), null, null, noKey);
        assertThat(absent.chatProvider()).isEqualTo(new ChatProviderStatus(false, null, null));
        assertThat(absent.isChatProviderLikelyReady()).isFalse();
        assertThat(service(null, null, null, noKey).isChatProviderReady()).isFalse();
    }

    @Test
    void embeddingProviderResolvesTheModelNameAndProbesDimensionsOnce() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.ollama.embedding.options.model", "qwen3-embedding:0.6b");
        ProviderReadinessService service = service(null, new OllamaEmbeddingModelStub(), null, environment);

        assertThat(service.embeddingProvider()).map(status -> status.model()).contains("qwen3-embedding:0.6b");
        assertThat(service.cachedEmbeddingDimensions()).isEmpty();
        assertThat(service.embeddingProbeAttempted()).isFalse();
        assertThat(service.probeEmbeddingDimensions()).contains(7);
        assertThat(service.embeddingProbeAttempted()).isTrue();
        assertThat(service.cachedEmbeddingDimensions()).contains(7);

        EmbeddingOptions options = EmbeddingOptions.builder().model("text-embedding-3-small").build();
        assertThat(service(null, null, options, environment).embeddingProvider())
                .map(status -> status.model()).contains("text-embedding-3-small");
        assertThat(service(null, null, null, environment).embeddingProvider()).isEmpty();
        assertThat(service(null, new AbsentModelFallbackConfig.AbsentEmbeddingModel(), options, environment)
                .embeddingProvider()).isEmpty();
        assertThat(service(null, null, null, environment).probeEmbeddingDimensions()).isEmpty();
    }
}
