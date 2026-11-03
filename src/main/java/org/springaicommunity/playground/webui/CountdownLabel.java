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
package org.springaicommunity.playground.webui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.html.Span;

import java.time.Duration;
import java.time.Instant;

public class CountdownLabel extends Span {

    private static final String TICK_SCRIPT = """
            const label = this;
            const end = Date.now() + $0 * 1000;
            const suffix = $1;
            const render = (left) => {
              const minutes = Math.floor(left / 60);
              const seconds = String(left % 60).padStart(2, '0');
              label.textContent = minutes + ':' + seconds + suffix;
            };
            const tick = () => {
              const left = Math.max(0, Math.ceil((end - Date.now()) / 1000));
              render(left);
              if (left > 0) label.__countdown = setTimeout(tick, 1000);
            };
            clearTimeout(label.__countdown);
            tick();
            """;

    private final Instant deadline;
    private final String suffix;

    public CountdownLabel(Duration remaining, String suffix) {
        this(Instant.now().plus(remaining), suffix);
    }

    public CountdownLabel(Instant deadline, String suffix) {
        this.deadline = deadline;
        this.suffix = suffix;
        setText(format(remainingSeconds()) + suffix);
        getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("font-variant-numeric", "tabular-nums")
                .set("white-space", "nowrap");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(TICK_SCRIPT, remainingSeconds(), this.suffix);
    }

    private long remainingSeconds() {
        long remainingMillis = Duration.between(Instant.now(), this.deadline).toMillis();
        return Math.max(0L, (remainingMillis + 999L) / 1000L);
    }

    public static String format(long seconds) {
        long clamped = Math.max(0L, seconds);
        return clamped / 60 + ":" + String.format("%02d", clamped % 60);
    }
}
