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

import org.springaicommunity.playground.service.PersistenceServiceInterface;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OllamaModelProbe {

    public interface TagsFetcher {

        Optional<String> fetch(String baseUrl);
    }

    public record Result(boolean reachable, List<String> installed) {

        public boolean has(String model) {
            return installed.stream().anyMatch(name -> name.equals(model) || name.equals(model + ":latest"));
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final TagsFetcher tagsFetcher;

    public OllamaModelProbe() {
        this(OllamaModelProbe::fetchTags);
    }

    OllamaModelProbe(TagsFetcher tagsFetcher) {
        this.tagsFetcher = tagsFetcher;
    }

    public Result probe(String baseUrl) {
        Optional<String> body = this.tagsFetcher.fetch(baseUrl);
        if (body.isEmpty()) return new Result(false, List.of());
        try {
            Map<String, Object> tags = PersistenceServiceInterface.OBJECT_MAPPER.readValue(body.get(),
                    PersistenceServiceInterface.MAP_TYPE_REFERENCE);
            List<String> names = tags.get("models") instanceof List<?> models ? models.stream()
                    .filter(Map.class::isInstance).map(model -> String.valueOf(((Map<?, ?>) model).get("name")))
                    .toList() : List.of();
            return new Result(true, names);
        } catch (RuntimeException e) {
            return new Result(false, List.of());
        }
    }

    private static Optional<String> fetchTags(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(trimmed + "/api/tags")).timeout(TIMEOUT)
                .GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

}
