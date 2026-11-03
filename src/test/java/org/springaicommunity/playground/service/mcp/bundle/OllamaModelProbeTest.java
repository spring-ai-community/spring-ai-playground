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
package org.springaicommunity.playground.service.mcp.bundle;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaModelProbeTest {

    @Test
    void readsInstalledModelsAndMatchesTheImplicitLatestTag() {
        OllamaModelProbe probe = new OllamaModelProbe(baseUrl -> Optional.of(
                "{\"models\":[{\"name\":\"qwen3-embedding:0.6b\"},{\"name\":\"mxbai-embed-large:latest\"}]}"));

        OllamaModelProbe.Result result = probe.probe("http://localhost:11434");

        assertThat(result.reachable()).isTrue();
        assertThat(result.has("qwen3-embedding:0.6b")).isTrue();
        assertThat(result.has("mxbai-embed-large")).isTrue();
        assertThat(result.has("qwen3.5:4b")).isFalse();
    }

    @Test
    void anUnansweredOrUnreadableEndpointIsUnreachable() {
        assertThat(new OllamaModelProbe(baseUrl -> Optional.empty()).probe("http://nowhere").reachable())
                .isFalse();
        assertThat(new OllamaModelProbe(baseUrl -> Optional.of("not json")).probe("http://garbled").reachable())
                .isFalse();
    }

}
