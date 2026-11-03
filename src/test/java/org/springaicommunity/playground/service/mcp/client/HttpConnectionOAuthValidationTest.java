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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.client.HttpConnectionParametersWithExtras.OAuth;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpConnectionOAuthValidationTest {

    @Test
    void issuerWithClientIdIsValid() {
        OAuth oauth = new OAuth("https://issuer.example.com", null, null, "cid", null, List.of(), null);

        assertThat(oauth.validationErrors()).isEmpty();
    }

    @Test
    void manualAuthorizationAndTokenPairIsAnAlternativeToTheIssuer() {
        OAuth oauth = new OAuth(null, "https://a.example.com/auth", "https://a.example.com/token", "${CID}", null,
                List.of(), null);

        assertThat(oauth.validationErrors()).isEmpty();
    }

    @Test
    void missingClientIdMissingEndpointsAndPlainHttpIssuerAreReported() {
        Map<String, String> blank = new OAuth(null, null, null, " ", null, List.of(), null).validationErrors();
        assertThat(blank).containsEntry(OAuth.CLIENT_ID_FIELD, "Client ID is required for OAuth.")
                .containsEntry(OAuth.AUTHORIZATION_URI_FIELD,
                        "Provide either Issuer URI, or both Authorization and Token URIs.")
                .doesNotContainKey(OAuth.ISSUER_URI_FIELD);

        Map<String, String> plainHttp = new OAuth("HTTP://issuer.example.com", null, null, "cid", null, List.of(),
                null).validationErrors();
        assertThat(plainHttp).containsOnlyKeys(OAuth.ISSUER_URI_FIELD)
                .containsEntry(OAuth.ISSUER_URI_FIELD, "Issuer URI must use HTTPS.");

        Map<String, String> halfPair = new OAuth(null, "https://a", null, "cid", null, List.of(), null)
                .validationErrors();
        assertThat(halfPair).containsOnlyKeys(OAuth.AUTHORIZATION_URI_FIELD);
    }
}
