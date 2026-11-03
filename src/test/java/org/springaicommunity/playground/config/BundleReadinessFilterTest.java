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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleReadiness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleReadinessFilterTest {

    @TempDir
    Path homeDir;

    @Test
    void mcpRequestsWaitWhileTheBundleIsStillBeingRegistered() throws Exception {
        BundleReadiness readiness = pendingBundle();
        BundleReadinessFilter filter = new BundleReadinessFilter(readiness);

        MockHttpServletResponse held = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/mcp", "172.17.0.1"), held, chain);
        MockHttpServletResponse heldSse = new MockHttpServletResponse();
        filter.doFilter(request("/sse", "172.17.0.1"), heldSse, new MockFilterChain());

        assertThat(held.getStatus()).isEqualTo(503);
        assertThat(held.getHeader("Retry-After")).isEqualTo("2");
        assertThat(chain.getRequest()).isNull();
        assertThat(heldSse.getStatus()).isEqualTo(503);
    }

    @Test
    void everythingElseAndTheAppItselfStayReachable() throws Exception {
        BundleReadinessFilter filter = new BundleReadinessFilter(pendingBundle());

        MockFilterChain actuator = new MockFilterChain();
        filter.doFilter(request("/actuator/health", "172.17.0.1"), new MockHttpServletResponse(), actuator);
        MockFilterChain loopback = new MockFilterChain();
        filter.doFilter(request("/mcp", "127.0.0.1"), new MockHttpServletResponse(), loopback);
        MockFilterChain loopbackV6 = new MockFilterChain();
        filter.doFilter(request("/mcp", "::1"), new MockHttpServletResponse(), loopbackV6);

        assertThat(actuator.getRequest()).isNotNull();
        assertThat(loopback.getRequest()).isNotNull();
        assertThat(loopbackV6.getRequest()).isNotNull();
    }

    @Test
    void mcpRequestsPassOnceTheBundleIsServed() throws Exception {
        BundleReadiness readiness = pendingBundle();
        BundleReadinessFilter filter = new BundleReadinessFilter(readiness);
        readiness.markReady();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/mcp", "172.17.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aRuntimeWithoutABundleIsReadyFromTheStart() throws Exception {
        BundleReadinessFilter filter = new BundleReadinessFilter(new BundleReadiness(this.homeDir));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/mcp", "172.17.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private BundleReadiness pendingBundle() throws IOException {
        Files.writeString(this.homeDir.resolve(BundleManifest.FILE_NAME), "{}");
        return new BundleReadiness(this.homeDir);
    }

    private static MockHttpServletRequest request(String uri, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddress);
        return request;
    }

}
