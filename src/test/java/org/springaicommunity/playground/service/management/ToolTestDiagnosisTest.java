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
package org.springaicommunity.playground.service.management;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.tool.ToolSpec.SandboxOverrides;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTestDiagnosisTest {

    private static final SandboxOverrides LOCKED = SandboxOverrides.empty();

    @Test
    void blockedFetchPointsToAnAllowlistOfHosts() {
        List<String> hints = ToolTestDiagnosis.hints("ReferenceError: fetch is not defined",
                "const r = await fetch('https://x.example');", LOCKED, List.of(), false);

        assertThat(hints).singleElement().asString().contains("ALLOWLIST", "hostsAllow", "L3");
    }

    @Test
    void aDeniedHostNamesTheHostToAdd() {
        List<String> hints = ToolTestDiagnosis.hints("[fetch] denied: host not in allowlist: api.other.org", "",
                new SandboxOverrides(Set.of(), Set.of(), Set.of(), Set.of(), "allowlist", Set.of("a.example.com"),
                        null, null, null, null), List.of(), false);

        assertThat(hints).singleElement().asString().contains("api.other.org", "not '*'");
    }

    @Test
    void anUndeclaredNameExplainsThatOnlyDeclaredInputsAreGlobals() {
        List<String> hints = ToolTestDiagnosis.hints("ReferenceError: currency is not defined", "return currency;",
                LOCKED, List.of(), false);

        assertThat(hints).singleElement().asString().contains("'currency' is not defined", "params");
    }

    @Test
    void fileHelpersWithoutFileAccessAreExplained() {
        List<String> hints = ToolTestDiagnosis.hints("TypeError: Cannot read property 'readText'",
                "return safety.fs.readText('a');", LOCKED, List.of(), false);

        assertThat(hints).anyMatch(hint -> hint.contains("fileRead") && hint.contains("L3"));
    }

    @Test
    void timeoutsAndSkippedParametersAndEmptyResultsGetTheirOwnHint() {
        assertThat(ToolTestDiagnosis.hints("Execution timed out after 30 seconds", "", LOCKED, List.of(), false))
                .singleElement().asString().contains("too long");
        assertThat(ToolTestDiagnosis.hints(null, "", LOCKED, List.of("rate"), false))
                .singleElement().asString().contains("No testValue for [rate]");
        assertThat(ToolTestDiagnosis.hints(null, "", LOCKED, List.of(), true))
                .singleElement().asString().contains("returned nothing");
        assertThat(ToolTestDiagnosis.hints(null, "", LOCKED, List.of(), false)).isEmpty();
    }

    @Test
    void aMissingEnvironmentVariableTellsTheAgentToStopAndAskInsteadOfMovingTheSecret() {
        List<String> hints = ToolTestDiagnosis.hints("apiKey env var not set: ${HTTPBIN_KEY}", "return apiKey;", LOCKED,
                List.of(), false);

        assertThat(hints).singleElement().asString().contains("HTTPBIN_KEY", "Do not turn the secret into a parameter",
                "ask the user");
    }

    @Test
    void anUnknownFailureStillGetsAPointerToTheGuide() {
        assertThat(ToolTestDiagnosis.hints("Error: boom", "throw new Error('boom')", LOCKED, List.of(), false))
                .singleElement().asString().contains("saip://guide/tool-authoring/mistakes");
    }

}
