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
package org.springaicommunity.playground.service.mcp.risk;

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.catalog.McpCatalogEntry.OAuthDefaults;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAuthClassifierTest {

    @Test
    void noneAndBlankResolveToNone() {
        assertEquals(McpAuthMode.NONE, McpAuthClassifier.fromCatalogAuth(null, null));
        assertEquals(McpAuthMode.NONE, McpAuthClassifier.fromCatalogAuth("", null));
        assertEquals(McpAuthMode.NONE, McpAuthClassifier.fromCatalogAuth("NONE", null));
    }

    @Test
    void apiKeyAndBearerVariants() {
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromCatalogAuth("API_KEY", null));
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromCatalogAuth("api-key", null));
        assertEquals(McpAuthMode.BEARER, McpAuthClassifier.fromCatalogAuth("BEARER", null));
    }

    @Test
    void oauthStandardWhenIssuerIsTrustedHost() {
        OAuthDefaults std = new OAuthDefaults("https://accounts.google.com", List.of());
        assertEquals(McpAuthMode.OAUTH_STANDARD, McpAuthClassifier.fromCatalogAuth("OAUTH", std));
    }

    @Test
    void oauthCustomWhenIssuerHasCustomMarkers() {
        OAuthDefaults oob = new OAuthDefaults("https://example.com/oob", List.of());
        assertEquals(McpAuthMode.OAUTH_CUSTOM, McpAuthClassifier.fromCatalogAuth("OAUTH", oob));
        OAuthDefaults manual = new OAuthDefaults("https://example.com/manual-paste", List.of());
        assertEquals(McpAuthMode.OAUTH_CUSTOM, McpAuthClassifier.fromCatalogAuth("OAUTH", manual));
    }

    @Test
    void oauthOrPatTreatedAsStandard() {
        assertEquals(McpAuthMode.OAUTH_STANDARD, McpAuthClassifier.fromCatalogAuth("OAUTH_OR_PAT", null));
    }

    @Test
    void explicitOauthCustomLabel() {
        assertEquals(McpAuthMode.OAUTH_CUSTOM, McpAuthClassifier.fromCatalogAuth("OAUTH_CUSTOM", null));
    }

    @Test
    void headersClassifyBearerApiKeyAndNone() {
        assertEquals(McpAuthMode.OAUTH_STANDARD, McpAuthClassifier.fromHeaders(Map.of(), true));
        assertEquals(McpAuthMode.NONE, McpAuthClassifier.fromHeaders(null, false));
        assertEquals(McpAuthMode.NONE, McpAuthClassifier.fromHeaders(Map.of("Accept", "text/event-stream"), false));
        assertEquals(McpAuthMode.BEARER, McpAuthClassifier.fromHeaders(Map.of("Authorization", "bearer x"), false));
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromHeaders(Map.of("authorization", "Basic x"), false));
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromHeaders(Map.of("X-API-Key", "k"), false));
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromHeaders(Map.of("ApiKey", "k"), false));
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("Authorization", null);
        assertEquals(McpAuthMode.API_KEY, McpAuthClassifier.fromHeaders(nullValue, false));
    }
}
