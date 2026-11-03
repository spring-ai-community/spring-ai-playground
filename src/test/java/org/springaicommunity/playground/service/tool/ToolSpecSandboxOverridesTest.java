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
package org.springaicommunity.playground.service.tool;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolSpec.SandboxOverrides;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSpecSandboxOverridesTest {

    private static final Set<String> BASELINE_ALLOW = new LinkedHashSet<>(List.of("java.lang.*", "java.util.*"));
    private static final Set<String> BASELINE_DENY = new LinkedHashSet<>(List.of("java.lang.System", "java.lang.Thread"));

    @Test
    void fromEffectiveStoresOnlyTheDeltaAgainstTheBaseline() {
        Set<String> effectiveAllow = new LinkedHashSet<>(List.of("java.lang.*", "org.jsoup.*"));
        Set<String> effectiveDeny = new LinkedHashSet<>(List.of("java.lang.System", "java.io.File"));

        SandboxOverrides overrides = SandboxOverrides.fromEffective(BASELINE_ALLOW, BASELINE_DENY, effectiveAllow,
                effectiveDeny, "allowlist", Set.of("api.example.com"), true, false, "/tmp/base", null);

        assertThat(overrides.addAllowClasses()).containsExactly("org.jsoup.*");
        assertThat(overrides.removeAllowClasses()).containsExactly("java.util.*");
        assertThat(overrides.addDenyClasses()).containsExactly("java.io.File");
        assertThat(overrides.removeDenyClasses()).containsExactly("java.lang.Thread");
        assertThat(overrides.networkMode()).isEqualTo("allowlist");
        assertThat(overrides.hostsAllow()).containsExactly("api.example.com");
        assertThat(overrides.fileRead()).isTrue();
        assertThat(overrides.fileWrite()).isFalse();
        assertThat(overrides.fsBasePath()).isEqualTo("/tmp/base");
        assertThat(overrides.destructive()).isNull();
    }

    @Test
    void resolveMergesTheDeltaBackOntoTheBaseline() {
        SandboxOverrides overrides = new SandboxOverrides(Set.of("org.jsoup.*"), Set.of("java.util.*"),
                Set.of("java.io.File"), Set.of("java.lang.Thread"), null, Set.of(), null, null, null, null);

        assertThat(overrides.resolveAllowClasses(BASELINE_ALLOW)).containsExactly("java.lang.*", "org.jsoup.*");
        assertThat(overrides.resolveDenyClasses(BASELINE_DENY)).containsExactly("java.lang.System", "java.io.File");
    }

    @Test
    void roundTripAndNullSafety() {
        SandboxOverrides fromNulls = SandboxOverrides.fromEffective(null, null, null, null, null, null, null, null,
                null, true);
        assertThat(fromNulls.addAllowClasses()).isEmpty();
        assertThat(fromNulls.removeDenyClasses()).isEmpty();
        assertThat(fromNulls.destructive()).isTrue();
        assertThat(SandboxOverrides.empty().resolveAllowClasses(null)).isEmpty();

        Set<String> effectiveAllow = new LinkedHashSet<>(List.of("java.lang.*", "org.jsoup.*"));
        SandboxOverrides overrides = SandboxOverrides.fromEffective(BASELINE_ALLOW, BASELINE_DENY, effectiveAllow,
                BASELINE_DENY, "blocked", Set.of(), null, null, null, null);
        assertThat(overrides.resolveAllowClasses(BASELINE_ALLOW)).containsExactlyElementsOf(effectiveAllow);
        assertThat(overrides.resolveDenyClasses(BASELINE_DENY)).containsExactlyElementsOf(BASELINE_DENY);
        assertThat(overrides.addDenyClasses()).isEmpty();
        assertThat(overrides.removeDenyClasses()).isEmpty();
    }
}
