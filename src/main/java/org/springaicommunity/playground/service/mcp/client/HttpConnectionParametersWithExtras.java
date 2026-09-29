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
import java.util.Locale;
import java.util.Map;

public final class HttpConnectionParametersWithExtras {

    private HttpConnectionParametersWithExtras() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamableHttp(
            String url,
            String endpoint,
            Map<String, String> headers,
            List<String> requiredEnv,
            OAuth oauth) {

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
            List<String> requiredEnv,
            OAuth oauth) {

        public Sse {
            headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuth(
            @JsonProperty("issuer-uri") String issuerUri,
            @JsonProperty("authorization-uri") String authorizationUri,
            @JsonProperty("token-uri") String tokenUri,
            @JsonProperty("client-id") String clientId,
            @JsonProperty("client-secret") String clientSecret,
            List<String> scopes,
            @JsonProperty("client-authentication-method") String clientAuthenticationMethod) {

        public OAuth {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public static final String CLIENT_ID_FIELD = "clientId";
        public static final String AUTHORIZATION_URI_FIELD = "authorizationUri";
        public static final String ISSUER_URI_FIELD = "issuerUri";

        public Map<String, String> validationErrors() {
            Map<String, String> errors = new LinkedHashMap<>();
            if (!hasText(this.clientId)) errors.put(CLIENT_ID_FIELD, "Client ID is required for OAuth.");
            boolean hasIssuer = hasText(this.issuerUri);
            boolean hasManualPair = hasText(this.authorizationUri) && hasText(this.tokenUri);
            if (!hasIssuer && !hasManualPair) {
                errors.put(AUTHORIZATION_URI_FIELD,
                        "Provide either Issuer URI, or both Authorization and Token URIs.");
            }
            if (hasIssuer && !this.issuerUri.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                errors.put(ISSUER_URI_FIELD, "Issuer URI must use HTTPS.");
            }
            return errors;
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
