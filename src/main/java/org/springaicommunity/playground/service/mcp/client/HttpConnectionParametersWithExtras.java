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
package org.springaicommunity.playground.service.mcp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpConnectionParametersWithExtras {

    private HttpConnectionParametersWithExtras() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamableHttp(
            String url,
            String endpoint,
            Map<String, String> headers,
            List<String> requiredEnv) {

        public StreamableHttp {
            headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sse(
            String url,
            @JsonProperty("sse-endpoint") String sseEndpoint,
            Map<String, String> headers,
            List<String> requiredEnv) {

        public Sse {
            headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
        }
    }
}
