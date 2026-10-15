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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record DeployTarget(String name, Kind kind, String dockerContext, String dockerHost, String publicHost,
        Origin origin) {

    public static final String LOCAL_NAME = "local";
    public static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,39}");
    private static final Set<String> HOST_SCHEMES = Set.of("ssh", "tcp", "unix", "npipe");
    private static final Set<String> NETWORK_SCHEMES = Set.of("ssh", "tcp");
    private static final String LOCAL_HOST = "localhost";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String CONTAINER_HOST = "host.docker.internal";

    public enum Kind { DOCKER }

    public enum Origin {
        BUILT_IN("Built in"),
        CONFIGURATION("Configuration"),
        ADDED("Added here"),
        DOCKER_CONTEXT("Docker context");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public DeployTarget {
        dockerContext = blankToNull(dockerContext);
        dockerHost = blankToNull(dockerHost);
        publicHost = blankToNull(publicHost);
    }

    public static DeployTarget local() {
        return new DeployTarget(LOCAL_NAME, Kind.DOCKER, null, null, null, Origin.BUILT_IN);
    }

    public static DeployTarget docker(String name, String dockerContext, String dockerHost, String publicHost,
            Origin origin) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Target name must be lowercase letters, digits, '.', '_' or '-' "
                    + "(max 40): " + name);
        }
        if (LOCAL_NAME.equals(name)) {
            throw new IllegalArgumentException("'" + LOCAL_NAME + "' is the built-in target");
        }
        boolean hasContext = dockerContext != null && !dockerContext.isBlank();
        boolean hasHost = dockerHost != null && !dockerHost.isBlank();
        if (origin != Origin.DOCKER_CONTEXT && hasContext == hasHost) {
            throw new IllegalArgumentException("Target '" + name + "' needs a docker context or a docker host, "
                    + "not both");
        }
        if (hasHost && !HOST_SCHEMES.contains(scheme(dockerHost))) {
            throw new IllegalArgumentException("Docker host of '" + name + "' must start with ssh://, tcp://, "
                    + "unix:// or npipe://: " + dockerHost);
        }
        return new DeployTarget(name, Kind.DOCKER, dockerContext, dockerHost, publicHost, origin);
    }

    public boolean isLocal() {
        return this.dockerContext == null && this.dockerHost == null;
    }

    public boolean isRemote() {
        return this.publicHost != null || NETWORK_SCHEMES.contains(scheme(this.dockerHost));
    }

    public List<String> cliArguments() {
        if (this.dockerContext != null) return List.of("--context", this.dockerContext);
        if (this.dockerHost != null) return List.of("--host", this.dockerHost);
        return List.of();
    }

    public String endpointHost() {
        if (this.publicHost != null) return this.publicHost;
        if (NETWORK_SCHEMES.contains(scheme(this.dockerHost))) {
            String host = uriHost(this.dockerHost);
            if (host != null) return host;
        }
        return LOCAL_HOST;
    }

    public String endpointUrl(int port) {
        return "http://" + urlHost(endpointHost()) + ":" + port;
    }

    public String probeUrl(int port) {
        String host = endpointHost();
        return "http://" + urlHost(LOCAL_HOST.equals(host) ? LOOPBACK : host) + ":" + port;
    }

    public String containerHostUrl(String containerUrl) {
        return containerUrl.replace(CONTAINER_HOST, urlHost(endpointHost()));
    }

    public String where() {
        if (isLocal()) return "Docker on this machine";
        if (this.dockerContext != null) {
            return "docker context " + this.dockerContext
                    + (this.dockerHost == null ? "" : " (" + this.dockerHost + ")");
        }
        return this.dockerHost;
    }

    private static String scheme(String dockerHost) {
        if (dockerHost == null) return "";
        int separator = dockerHost.indexOf("://");
        return separator < 0 ? "" : dockerHost.substring(0, separator).toLowerCase(Locale.ROOT);
    }

    private static String urlHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String uriHost(String dockerHost) {
        try {
            return new URI(dockerHost).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

}
