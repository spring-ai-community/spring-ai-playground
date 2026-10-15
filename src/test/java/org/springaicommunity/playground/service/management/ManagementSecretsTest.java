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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementSecretsTest {

    @Test
    void sensitiveNamesAreRecognised() {
        assertThat(ManagementSecrets.isSensitiveName("Authorization")).isTrue();
        assertThat(ManagementSecrets.isSensitiveName("X-API-Key")).isTrue();
        assertThat(ManagementSecrets.isSensitiveName("GITHUB_TOKEN")).isTrue();
        assertThat(ManagementSecrets.isSensitiveName("Accept")).isFalse();
    }

    @Test
    void literalSecretsAreRejectedAndReferencesAreAccepted() {
        assertThatThrownBy(() -> ManagementSecrets.requireReference("Header", "Authorization", "Bearer abc123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${ENV_NAME}");
        assertThatCode(() -> ManagementSecrets.requireReference("Header", "Authorization", "Bearer ${MY_TOKEN}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ManagementSecrets.requireReference("Header", "Accept", "application/json"))
                .doesNotThrowAnyException();
    }

    @Test
    void argumentsAreMaskedRecursively() {
        Map<String, Object> masked = ManagementSecrets.maskArguments(Map.of(
                "name", "server",
                "headers", Map.of("Authorization", "Bearer abcdefghij", "Accept", "json"),
                "env", List.of(Map.of("API_KEY", "value-1234"))));

        assertThat(masked.toString()).doesNotContain("abcdefghij", "value-1234").contains("server", "json");
    }

    @Test
    void envReferencesAreKeptInMaskedArguments() {
        Map<String, Object> masked = ManagementSecrets.maskArguments(Map.of("token", "${MY_TOKEN}"));

        assertThat(masked.get("token")).isEqualTo("${MY_TOKEN}");
    }

}
