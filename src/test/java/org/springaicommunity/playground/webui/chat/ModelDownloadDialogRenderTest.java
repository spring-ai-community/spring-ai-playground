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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.chat.OllamaModelDownloadService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class ModelDownloadDialogRenderTest extends SpringBrowserlessTest {

    @MockitoBean
    private OllamaModelDownloadService downloadService;

    @Test
    void clickingDownloadStartsThePullAndDisablesTheButton() {
        when(this.downloadService.pull(anyString())).thenReturn(Flux.never());
        when(this.downloadService.isDownloaded(anyString())).thenReturn(false);
        AtomicBoolean downloaded = new AtomicBoolean(false);
        ModelDownloadDialog dialog = new ModelDownloadDialog("smollm2:135m", this.downloadService,
                () -> downloaded.set(true));
        dialog.open();
        roundTrip();

        Button download = $(Button.class, dialog)
                .withCondition(button -> "Download".equals(button.getText()))
                .first();
        test(download).click();

        assertThat(download.isEnabled()).isFalse();
        assertThat($(Span.class, dialog).all().stream()
                .anyMatch(span -> "Starting download...".equals(span.getText()))).isTrue();
        assertThat(dialog.isOpened()).isTrue();
        assertThat(downloaded).isFalse();
    }

}
