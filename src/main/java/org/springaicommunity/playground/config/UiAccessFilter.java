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
package org.springaicommunity.playground.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class UiAccessFilter extends OncePerRequestFilter {

    static final String UI_DISABLED_BODY = "Not found. This server answers MCP at /mcp; its web UI is off ("
            + UiProperties.ENABLED_PROPERTY + ").";

    private static final RequestMatcher API_PATHS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/mcp/**"), PathPatternRequestMatcher.pathPattern("/sse/**"),
            PathPatternRequestMatcher.pathPattern("/actuator/**"),
            PathPatternRequestMatcher.pathPattern("/oauth2/authorization/**"),
            PathPatternRequestMatcher.pathPattern("/login/oauth2/code/**"));

    private final boolean uiEnabled;

    public UiAccessFilter(UiProperties properties) {
        this.uiEnabled = properties.enabled() == null || properties.enabled();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return this.uiEnabled || API_PATHS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UI_DISABLED_BODY);
    }

}
