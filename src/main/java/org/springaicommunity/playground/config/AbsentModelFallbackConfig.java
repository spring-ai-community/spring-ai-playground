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

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AbsentModelFallbackConfig {

    public static final String CHAT_MODEL_ABSENT =
            "No chat model is configured (spring.ai.model.chat=none); chat features are disabled in this deployment";

    public static final String EMBEDDING_MODEL_ABSENT =
            "No embedding model is configured (spring.ai.model.embedding=none); "
                    + "vector search features are disabled in this deployment";

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "none")
    public ChatModel absentChatModel() {
        return prompt -> {
            throw new IllegalStateException(CHAT_MODEL_ABSENT);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "none")
    public EmbeddingModel absentEmbeddingModel() {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException(EMBEDDING_MODEL_ABSENT);
            }

            @Override
            public float[] embed(Document document) {
                throw new IllegalStateException(EMBEDDING_MODEL_ABSENT);
            }
        };
    }

}
