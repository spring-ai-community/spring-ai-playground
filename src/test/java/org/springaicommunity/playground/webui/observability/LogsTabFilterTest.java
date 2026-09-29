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
package org.springaicommunity.playground.webui.observability;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.observability.logs.LogQueryService;
import org.springaicommunity.playground.webui.observability.components.ObservabilityGlobalSettings;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LogsTabFilterTest extends SpringBrowserlessTest {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String CONTEXT = " [user= sid= conv= msg= traceId= spanId=] - ";

    @TempDir
    Path home;

    private LogsTab tab;

    @BeforeEach
    void openTab() throws IOException {
        String time = LocalDateTime.now().minusSeconds(5).format(LOG_TIME);
        Path logs = Files.createDirectories(this.home.resolve("logs"));
        Files.write(logs.resolve("spring-ai-playground.log"), List.of(
                time + " [main] INFO  o.s.Application" + CONTEXT + "Started application (ok)",
                time + " [main] WARN  o.s.Catalog" + CONTEXT + "Catalog entry skipped",
                time + " [main] INFO  o.s.Server" + CONTEXT + "Server started on port 1"));
        this.tab = new LogsTab(new LogQueryService(this.home), new ObservabilityGlobalSettings());
        UI.getCurrent().add(this.tab);
        this.tab.refresh(null);
        roundTrip();
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelFilterNarrowsTheLinesThroughTheQueryService() {
        ComboBox<String> level = $(ComboBox.class, this.tab)
                .withCondition(combo -> "Level".equals(combo.getPlaceholder())).single();

        test(level).selectItem("WARN");
        roundTrip();

        assertThat(tabText()).contains("matched 1 / 3").contains("1 of 3 lines");
    }

    @Test
    void textFilterMatchesCaseInsensitivelyAndAnInvalidRegexFallsBackToLiteral() {
        TextField text = $(TextField.class, this.tab).single();

        test(text).setValue("STARTED");
        roundTrip();
        assertThat(tabText()).contains("matched 2 / 3");

        test($(Checkbox.class, this.tab).withCondition(box -> "Regex".equals(box.getLabel())).single()).click();
        test(text).setValue("(");
        roundTrip();
        assertThat(tabText()).contains("matched 1 / 3").contains("regex error");
    }

    @Test
    void aMissingLogFileIsReportedInsteadOfFailing() throws IOException {
        Files.delete(this.home.resolve("logs").resolve("spring-ai-playground.log"));

        this.tab.refresh(null);
        roundTrip();

        assertThat(tabText()).contains("Log file not found");
    }

    private String tabText() {
        return this.tab.getElement().getTextRecursively();
    }

}
