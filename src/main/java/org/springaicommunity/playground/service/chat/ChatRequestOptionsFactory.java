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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openaisdk.OpenAiSdkChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class ChatRequestOptionsFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChatRequestOptionsFactory.class);

    private final ObjectMapper overlayMapper;

    public ChatRequestOptionsFactory(ObjectMapper objectMapper) {
        // Connection fields (proxy/credential/apiKey...) are JPMS-inaccessible or security-sensitive, so the overlay
        // mapper ignores them: a free-form provider-options override can tweak request params but cannot touch the
        // connection or inject credentials.
        this.overlayMapper = objectMapper.copy()
                .addMixIn(OpenAiSdkChatOptions.class, OpenAiConnectionFieldsMixin.class)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ToolCallingChatOptions build(ChatModel chatModel, DefaultChatOptions base, ChatExtraOptions extra,
            ReasoningEffort reasoning) {
        ChatExtraOptions effective = extra == null ? ChatExtraOptions.defaults() : extra;
        ToolCallingChatOptions options = buildForProvider(ChatProvider.from(chatModel), base, effective, reasoning);
        return applyJsonOverride(options, effective.providerOptionsJson());
    }

    private ToolCallingChatOptions buildForProvider(ChatProvider provider, DefaultChatOptions base,
            ChatExtraOptions extra, ReasoningEffort reasoning) {
        return switch (provider) {
            case OPENAI_SDK -> buildOpenAi(base, extra, reasoning);
            case OLLAMA -> buildOllama(base, extra, reasoning);
            case GENERIC -> buildGeneric(base);
        };
    }

    private ToolCallingChatOptions buildOpenAi(DefaultChatOptions base, ChatExtraOptions extra,
            ReasoningEffort reasoning) {
        OpenAiSdkChatOptions.Builder builder = OpenAiSdkChatOptions.builder()
                .model(base.getModel())
                .maxTokens(base.getMaxTokens())
                .temperature(base.getTemperature())
                .topP(base.getTopP())
                .frequencyPenalty(base.getFrequencyPenalty())
                .presencePenalty(base.getPresencePenalty())
                // Without include_usage the streaming response carries no token counts, so the chat footer would be empty.
                .streamUsage(true);
        if (extra.seed() != null) builder.seed(extra.seed());
        if (!CollectionUtils.isEmpty(extra.stop())) builder.stop(extra.stop());
        String effort = openAiReasoningEffort(reasoning);
        if (effort != null) builder.reasoningEffort(effort);
        return builder.build();
    }

    private ToolCallingChatOptions buildOllama(DefaultChatOptions base, ChatExtraOptions extra,
            ReasoningEffort reasoning) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(base.getModel())
                .temperature(base.getTemperature())
                .topP(base.getTopP())
                .frequencyPenalty(base.getFrequencyPenalty())
                .presencePenalty(base.getPresencePenalty());
        if (base.getTopK() != null) builder.topK(base.getTopK());
        if (base.getMaxTokens() != null) builder.numPredict(base.getMaxTokens());
        if (extra.seed() != null) builder.seed(extra.seed());
        if (!CollectionUtils.isEmpty(extra.stop())) builder.stop(extra.stop());
        applyOllamaThinking(builder, reasoning);
        return builder.build();
    }

    private ToolCallingChatOptions buildGeneric(DefaultChatOptions base) {
        return DefaultToolCallingChatOptions.builder()
                .model(base.getModel())
                .maxTokens(base.getMaxTokens())
                .temperature(base.getTemperature())
                .topP(base.getTopP())
                .frequencyPenalty(base.getFrequencyPenalty())
                .presencePenalty(base.getPresencePenalty())
                .build();
    }

    private String openAiReasoningEffort(ReasoningEffort effort) {
        if (effort == null) return null;
        // OpenAI reasoning models cannot be fully switched off, so OFF maps to "no override" rather than minimal.
        return switch (effort) {
            case OFF -> null;
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
        };
    }

    private void applyOllamaThinking(OllamaChatOptions.Builder builder, ReasoningEffort effort) {
        if (effort == null) return;
        switch (effort) {
            case OFF -> builder.disableThinking();
            case LOW -> builder.thinkLow();
            case MEDIUM -> builder.thinkMedium();
            case HIGH -> builder.thinkHigh();
        }
    }

    // Overlay the user's free-form provider-options JSON onto the structured options (override wins). Connection
    // fields are ignored via the mixin; a malformed override is logged and skipped rather than breaking the chat.
    private ToolCallingChatOptions applyJsonOverride(ToolCallingChatOptions options, String json) {
        if (!StringUtils.hasText(json)) return options;
        try {
            this.overlayMapper.readerForUpdating(options).readValue(json);
        } catch (JsonProcessingException | RuntimeException e) {
            logger.warn("chat.options.override-failed error={}", e.getMessage());
        }
        return options;
    }

    @JsonIgnoreProperties({"baseUrl", "apiKey", "credential", "azureOpenAIServiceVersion", "organizationId",
            "azure", "gitHubModels", "timeout", "maxRetries", "proxy", "customHeaders", "deploymentName"})
    private abstract static class OpenAiConnectionFieldsMixin {
    }
}
