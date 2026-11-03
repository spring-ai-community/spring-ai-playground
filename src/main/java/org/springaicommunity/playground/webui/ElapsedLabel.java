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

public class ElapsedLabel extends Span {

    private static final String TICK_SCRIPT = """
            const label = this;
            const startedAt = Date.now() - $0 * 1000;
            const prefix = $1;
            const tick = () => {
              const seconds = Math.floor((Date.now() - startedAt) / 1000);
              label.textContent = prefix + seconds + ' s';
              label.__elapsed = setTimeout(tick, 1000);
            };
            clearTimeout(label.__elapsed);
            tick();
            """;

    private final Instant startedAt = Instant.now();
    private final String prefix;

    public ElapsedLabel(String prefix) {
        this.prefix = prefix;
        setText(prefix + elapsedSeconds() + " s");
        getStyle().set("font-variant-numeric", "tabular-nums");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(TICK_SCRIPT, elapsedSeconds(), this.prefix);
    }

    private long elapsedSeconds() {
        return Math.max(0L, Duration.between(this.startedAt, Instant.now()).toSeconds());
    }
}
