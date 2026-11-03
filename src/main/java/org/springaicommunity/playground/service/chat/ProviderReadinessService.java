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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.playground.config.AbsentModelFallbackConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Service
public class ProviderReadinessService {

    public record ChatProviderStatus(boolean configured, String provider, String model) {}

    public record EmbeddingStatus(String model) {}

    private static final Logger logger = LoggerFactory.getLogger(ProviderReadinessService.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final Optional<EmbeddingOptions> embeddingOptions;
    private final Environment environment;
    private volatile Integer cachedEmbeddingDimensions;
    private volatile boolean embeddingProbeAttempted;

    public ProviderReadinessService(ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider, Optional<EmbeddingOptions> embeddingOptions,
            Environment environment) {
        this.chatModelProvider = chatModelProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingOptions = embeddingOptions;
        this.environment = environment;
    }

    public ChatProviderStatus chatProvider() {
        ChatModel chatModel = configuredChatModel();
        if (chatModel == null) return new ChatProviderStatus(false, null, null);
        return new ChatProviderStatus(true, humanProvider(chatModel.getClass().getSimpleName(), "ChatModel"),
                safeGetChatModel(chatModel));
    }

    public boolean isChatProviderReady() {
        ChatModel chatModel = configuredChatModel();
        if (chatModel == null) return false;
        String className = chatModel.getClass().getSimpleName().toLowerCase();
        try {
            if (className.contains("ollama")) {
                return ping(this.environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
            }
            if (className.contains("openai")) return hasOpenAiKey();
            return true;
        } catch (RuntimeException e) {
            logger.debug("chat readiness probe failed", e);
            return false;
        }
    }

    public boolean isChatProviderLikelyReady() {
        ChatModel chatModel = configuredChatModel();
        if (chatModel == null) return false;
        if (chatModel.getClass().getSimpleName().toLowerCase().contains("openai")) return hasOpenAiKey();
        return true;
    }

    public Optional<EmbeddingStatus> embeddingProvider() {
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        if (embeddingModel instanceof AbsentModelFallbackConfig.AbsentEmbeddingModel) return Optional.empty();
        if (embeddingModel == null && this.embeddingOptions.isEmpty()) return Optional.empty();
        return Optional.of(new EmbeddingStatus(resolveEmbeddingModel(embeddingModel)));
    }

    public Optional<Integer> cachedEmbeddingDimensions() {
        return Optional.ofNullable(this.cachedEmbeddingDimensions);
    }

    public boolean embeddingProbeAttempted() {
        return this.embeddingProbeAttempted;
    }

    public Optional<Integer> probeEmbeddingDimensions() {
        if (this.cachedEmbeddingDimensions != null) return Optional.of(this.cachedEmbeddingDimensions);
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || embeddingModel instanceof AbsentModelFallbackConfig.AbsentEmbeddingModel)
            return Optional.empty();
        this.embeddingProbeAttempted = true;
        try {
            int dimensions = embeddingModel.dimensions();
            this.cachedEmbeddingDimensions = dimensions;
            return Optional.of(dimensions);
        } catch (RuntimeException e) {
            logger.debug("embedding dimensions probe failed", e);
            return Optional.empty();
        }
    }

    private ChatModel configuredChatModel() {
        ChatModel chatModel = this.chatModelProvider.getIfAvailable();
        return chatModel instanceof AbsentModelFallbackConfig.AbsentChatModel ? null : chatModel;
    }

    private boolean hasOpenAiKey() {
        String apiKey = this.environment.getProperty("spring.ai.openai.api-key", "");
        return apiKey != null && !apiKey.isBlank();
    }

    private static String modelOfDefaultOptions(EmbeddingModel embeddingModel) {
        try {
            Object options = embeddingModel.getClass().getMethod("getDefaultOptions").invoke(embeddingModel);
            if (options == null) return null;
            Object model = options.getClass().getMethod("getModel").invoke(options);
            return model == null || model.toString().isBlank() ? null : model.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private String resolveEmbeddingModel(EmbeddingModel embeddingModel) {
        String name = this.embeddingOptions.map(EmbeddingOptions::getModel).orElse(null);
        if (name != null && !name.isBlank()) return name;
        if (embeddingModel == null) return null;
        String defaultOptionsModel = modelOfDefaultOptions(embeddingModel);
        if (defaultOptionsModel != null) return defaultOptionsModel;
        String className = embeddingModel.getClass().getSimpleName().toLowerCase();
        String key = className.contains("ollama") ? "spring.ai.ollama.embedding.options.model"
                : className.contains("openai") ? "spring.ai.openai.embedding.options.model" : null;
        if (key == null) return null;
        String value = this.environment.getProperty(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean ping(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl)).timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    static String humanProvider(String simpleName, String suffix) {
        String base = simpleName.replace(suffix, "");
        if (base.equalsIgnoreCase("OpenAi")) return "OpenAI";
        return base;
    }

    private static String safeGetChatModel(ChatModel chatModel) {
        try {
            var options = chatModel.getOptions();
            return options != null ? options.getModel() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
