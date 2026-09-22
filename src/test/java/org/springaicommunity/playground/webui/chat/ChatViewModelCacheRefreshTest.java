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
package org.springaicommunity.playground.webui.chat;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.OllamaModelDownloadService;
import org.springaicommunity.playground.webui.common.WorkspaceSettingsDrawer;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.ai.playground.chat.tool-search.min-tools=0")
class ChatViewModelCacheRefreshTest extends SpringBrowserlessTest {

    @MockitoBean
    private OllamaChatModel chatModel;

    @MockitoBean
    private OllamaApi ollamaApi;

    @Autowired
    private OllamaModelDownloadService modelDownloadService;

    @BeforeEach
    void stubModelOptions() {
        lenient().when(chatModel.getOptions()).thenReturn(OllamaChatOptions.builder().build());
    }

    @Test
    void openingSettingsDrawerRefreshesDownloadedModelsCache() {
        when(ollamaApi.listModels()).thenReturn(new OllamaApi.ListModelResponse(
                List.of(new OllamaApi.Model("qwen3.8:27b-mlx", "qwen3.8:27b-mlx", null, null, null, null))));
        assertThat(modelDownloadService.isDownloaded("gemma4:31b-mlx")).isTrue();
        clearInvocations(ollamaApi);

        UI.getCurrent().navigate(ChatView.class);
        roundTrip();
        $(WorkspaceSettingsDrawer.class, (ChatView) getCurrentView()).single().open();
        roundTrip();

        verify(ollamaApi, timeout(2000)).listModels();
        awaitUntilCacheLoaded();
        assertThat(modelDownloadService.isDownloaded("qwen3.8:27b-mlx")).isTrue();
        assertThat(modelDownloadService.isDownloaded("gemma4:31b-mlx")).isFalse();
    }

    private void awaitUntilCacheLoaded() {
        long deadline = System.currentTimeMillis() + 2000;
        while (modelDownloadService.isDownloaded("gemma4:31b-mlx") && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

}
