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
package org.springaicommunity.playground.webui.observability.components;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.observability.pricing.CurrencyService;
import org.springaicommunity.playground.observability.pricing.CurrencyService.CurrencyRate;
import org.springaicommunity.playground.service.PersistenceExecutor;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyFlagsTest {

    private static final String WHITE_FLAG = "\uD83C\uDFF3";

    @TempDir
    Path home;

    @Test
    void knownCurrenciesMapToTheirFlags() {
        assertThat(CurrencyFlags.flagFor("KRW")).isEqualTo("\uD83C\uDDF0\uD83C\uDDF7");
        assertThat(CurrencyFlags.flagFor("USD")).isEqualTo("\uD83C\uDDFA\uD83C\uDDF8");
        assertThat(CurrencyFlags.flagFor("EUR")).isEqualTo("\uD83C\uDDEA\uD83C\uDDFA");
        assertThat(CurrencyFlags.flagFor("BHD")).isEqualTo("\uD83C\uDDE7\uD83C\uDDED");
    }

    @Test
    void everySeededCurrencyHasASpecificFlag() throws Exception {
        PersistenceExecutor executor = new PersistenceExecutor();
        CurrencyService service = new CurrencyService(this.home, executor);
        service.init();

        for (CurrencyRate rate : service.all())
            assertThat(CurrencyFlags.flagFor(rate.code())).isNotEqualTo(WHITE_FLAG);
        executor.awaitCompletion(Duration.ofSeconds(5));
    }

    @Test
    void unknownCodesDeriveRegionalIndicators() {
        assertThat(CurrencyFlags.flagFor("BND")).isEqualTo("\uD83C\uDDE7\uD83C\uDDF3");
    }

    @Test
    void invalidInputFallsBackToTheWhiteFlag() {
        assertThat(CurrencyFlags.flagFor(null)).isEqualTo(WHITE_FLAG);
        assertThat(CurrencyFlags.flagFor("US")).isEqualTo(WHITE_FLAG);
        assertThat(CurrencyFlags.flagFor("12X")).isEqualTo(WHITE_FLAG);
    }
}
