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
package org.springaicommunity.playground.service.home;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseCheckServiceTest {

    @Test
    void newerMilestoneIsAnUpdateButOlderIsNot() {
        assertThat(ReleaseCheckService.isNewer("0.2.0-M9", "0.2.0-M8")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0-M7", "0.2.0-M8")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.2.0-M8", "0.2.0-M8")).isFalse();
    }

    @Test
    void gaOutranksAnyMilestoneOfSameVersion() {
        assertThat(ReleaseCheckService.isNewer("0.2.0", "0.2.0-M8")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0-M8", "0.2.0")).isFalse();
    }

    @Test
    void rcOutranksMilestoneAndGaOutranksRc() {
        assertThat(ReleaseCheckService.isNewer("0.2.0-RC1", "0.2.0-M11")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0-RC2", "0.2.0-RC1")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0", "0.2.0-RC2")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0-M12", "0.2.0-RC1")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.2.0-RC1", "0.2.0-RC1")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.2.0-RC1", "0.2.0")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.3.0-M1", "0.2.0")).isTrue();
    }

    @Test
    void higherCoreVersionWins() {
        assertThat(ReleaseCheckService.isNewer("0.3.0-M1", "0.2.0-M8")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.1.9", "0.2.0-M8")).isFalse();
        assertThat(ReleaseCheckService.isNewer("1.0.0-M1", "0.9.9")).isTrue();
    }

    @Test
    void patchReleaseOutranksLowerPatchAtAnyStage() {
        assertThat(ReleaseCheckService.isNewer("0.2.1", "0.2.0")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.1-M1", "0.2.0")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.1-RC1", "0.2.0")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.0", "0.2.1-M1")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.2.10", "0.2.9")).isTrue();
        assertThat(ReleaseCheckService.isNewer("0.2.1", "0.2.1")).isFalse();
    }

    @Test
    void leadingVPrefixAndUnparseableAreHandled() {
        assertThat(ReleaseCheckService.isNewer("v0.2.0-M9", "0.2.0-M8")).isTrue();
        assertThat(ReleaseCheckService.isNewer("garbage", "0.2.0-M8")).isFalse();
        assertThat(ReleaseCheckService.isNewer("0.2.0-M8", null)).isFalse();
    }

    @Test
    void parseVersionMapsStageAndNumber() {
        assertThat(ReleaseCheckService.parseVersion("0.2.0-M8")).containsExactly(0, 2, 0, 1, 8);
        assertThat(ReleaseCheckService.parseVersion("0.2.0-RC1")).containsExactly(0, 2, 0, 2, 1);
        assertThat(ReleaseCheckService.parseVersion("0.2.0")).containsExactly(0, 2, 0, 3, 0);
        assertThat(ReleaseCheckService.parseVersion("1.4.2-SNAPSHOT")).containsExactly(1, 4, 2, 0, 0);
    }

    @Test
    void availableUpdateHonoursDevBuildsDesktopManagementAndTheCache() {
        AtomicInteger fetches = new AtomicInteger();
        Supplier<String> body = () -> {
            fetches.incrementAndGet();
            return "{\"tag_name\":\"0.2.0-M14\",\"html_url\":\"https://x/r\",\"name\":\"M14\","
                    + "\"published_at\":\"2026-09-01T00:00:00Z\"}";
        };
        ReleaseCheckService older = new ReleaseCheckService("0.2.0-M13", false, body);
        ReleaseCheckService.LatestRelease update = older.availableUpdate().orElseThrow();
        assertThat(update.tagName()).isEqualTo("0.2.0-M14");
        assertThat(update.htmlUrl()).isEqualTo("https://x/r");
        assertThat(update.name()).isEqualTo("M14");
        assertThat(update.publishedAtEpochMs()).isEqualTo(1_788_220_800_000L);
        older.availableUpdate();
        assertThat(fetches.get()).as("second call is served from the cache").isEqualTo(1);

        assertThat(new ReleaseCheckService("0.2.0-M14", false, body).availableUpdate()).isEmpty();
        assertThat(new ReleaseCheckService("dev", false, body).availableUpdate()).isEmpty();
        assertThat(new ReleaseCheckService("0.1.0", true, body).availableUpdate()).isEmpty();
        assertThat(new ReleaseCheckService("0.1.0", false, () -> null).availableUpdate()).isEmpty();
        assertThat(new ReleaseCheckService("0.1.0", false, () -> "{}").latestRelease()).isEmpty();
        assertThat(ReleaseCheckService.parseRelease("{\"tag_name\":\"v1\"}").htmlUrl())
                .isEqualTo(ReleaseCheckService.RELEASES_PAGE);
    }
}
