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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReleaseCheckService {

    public record LatestRelease(String tagName, String htmlUrl, String name, Long publishedAtEpochMs) {}

    public static final String DEV_VERSION = "dev";
    public static final String RELEASES_API =
            "https://api.github.com/repos/spring-ai-community/spring-ai-playground/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/spring-ai-community/spring-ai-playground/releases/latest";
    public static final String DOWNLOAD_GUIDE =
            "https://spring-ai-community.github.io/spring-ai-playground/#1-download-the-desktop-app";

    private static final Logger logger = LoggerFactory.getLogger(ReleaseCheckService.class);
    private static final Pattern MILESTONE_OR_RC_SUFFIX_PATTERN = Pattern.compile("(?i)(M|RC)(\\d+)");
    private static final int STAGE_UNKNOWN = 0;
    private static final int STAGE_MILESTONE = 1;
    private static final int STAGE_RC = 2;
    private static final int STAGE_GA = 3;
    private static final Duration LATEST_RELEASE_CACHE_DURATION = Duration.ofHours(6);

    private final String currentVersion;
    private final boolean managedByDesktop;
    private final Supplier<String> releaseBodySupplier;
    private volatile LatestRelease cachedRelease;
    private volatile Instant cachedAt;

    @Autowired
    public ReleaseCheckService(Environment environment) {
        this(loadCurrentVersion(),
                environment.getProperty("spring.ai.playground.desktop.managed-updates", Boolean.class, false),
                ReleaseCheckService::fetchReleaseBody);
    }

    ReleaseCheckService(String currentVersion, boolean managedByDesktop, Supplier<String> releaseBodySupplier) {
        this.currentVersion = currentVersion;
        this.managedByDesktop = managedByDesktop;
        this.releaseBodySupplier = releaseBodySupplier;
    }

    public String currentVersion() {
        return this.currentVersion;
    }

    public boolean isDevBuild() {
        return DEV_VERSION.equals(this.currentVersion);
    }

    public boolean updatesManagedByDesktop() {
        return this.managedByDesktop;
    }

    public Optional<LatestRelease> availableUpdate() {
        if (this.managedByDesktop || isDevBuild()) return Optional.empty();
        Optional<LatestRelease> latest = latestRelease();
        if (latest.isEmpty()) {
            logger.info("Update check: could not fetch latest release");
            return Optional.empty();
        }
        if (!isNewer(latest.get().tagName(), this.currentVersion)) {
            logger.info("Update up-to-date: current={} latest={}", this.currentVersion, latest.get().tagName());
            return Optional.empty();
        }
        logger.info("Update available: current={} latest={}", this.currentVersion, latest.get().tagName());
        return latest;
    }

    public Optional<LatestRelease> latestRelease() {
        LatestRelease cached = this.cachedRelease;
        Instant fetchedAt = this.cachedAt;
        if (cached != null && fetchedAt != null && Duration.between(fetchedAt, Instant.now()).compareTo(LATEST_RELEASE_CACHE_DURATION) < 0)
            return Optional.of(cached);
        String body = this.releaseBodySupplier.get();
        if (body == null) return Optional.empty();
        LatestRelease release = parseRelease(body);
        if (release == null) return Optional.empty();
        this.cachedRelease = release;
        this.cachedAt = Instant.now();
        return Optional.of(release);
    }

    static LatestRelease parseRelease(String body) {
        String tag = extractJsonField(body, "tag_name");
        if (tag == null || tag.isBlank()) return null;
        String url = extractJsonField(body, "html_url");
        String publishedAt = extractJsonField(body, "published_at");
        return new LatestRelease(tag, url != null ? url : RELEASES_PAGE, extractJsonField(body, "name"),
                epochMillisOrNull(publishedAt));
    }

    private static Long epochMillisOrNull(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return null;
        try {
            return Instant.parse(isoInstant).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static boolean isNewer(String latest, String current) {
        int[] latestParts = parseVersion(latest);
        int[] currentParts = parseVersion(current);
        if (latestParts == null || currentParts == null) return false;
        for (int i = 0; i < latestParts.length; i++) {
            if (latestParts[i] != currentParts[i]) return latestParts[i] > currentParts[i];
        }
        return false;
    }

    static int[] parseVersion(String version) {
        String normalized = normalizeVersion(version);
        if (normalized.isEmpty()) return null;
        int dash = normalized.indexOf('-');
        String core = dash < 0 ? normalized : normalized.substring(0, dash);
        String suffix = dash < 0 ? "" : normalized.substring(dash + 1);
        String[] parts = core.split("\\.");
        int[] out = { 0, 0, 0, STAGE_GA, 0 };
        try {
            for (int i = 0; i < 3 && i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
            if (!suffix.isEmpty()) {
                Matcher preRelease = MILESTONE_OR_RC_SUFFIX_PATTERN.matcher(suffix);
                if (preRelease.matches()) {
                    out[3] = "M".equalsIgnoreCase(preRelease.group(1)) ? STAGE_MILESTONE : STAGE_RC;
                    out[4] = Integer.parseInt(preRelease.group(2));
                } else {
                    out[3] = STAGE_UNKNOWN;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return "";
        char first = trimmed.charAt(0);
        return first == 'v' || first == 'V' ? trimmed.substring(1) : trimmed;
    }

    static String loadCurrentVersion() {
        try (var is = ReleaseCheckService.class.getClassLoader().getResourceAsStream("META-INF/build-info.properties")) {
            if (is == null) return DEV_VERSION;
            Properties props = new Properties();
            props.load(is);
            String version = props.getProperty("build.version");
            return version != null && !version.isBlank() ? version : DEV_VERSION;
        } catch (IOException e) {
            logger.debug("Could not load build-info.properties", e);
            return DEV_VERSION;
        }
    }

    private static String fetchReleaseBody() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(RELEASES_API)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.debug("GitHub releases API returned status {}", response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.debug("Failed to fetch latest release", e);
            return null;
        }
    }

    private static String extractJsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
