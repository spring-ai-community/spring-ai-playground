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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UiAccessFilterTest {

    @Test
    void theScreensAreServedUnlessTheUiPropertyTurnsThemOff() throws Exception {
        UiAccessFilter unset = new UiAccessFilter(new UiProperties(null));
        UiAccessFilter on = new UiAccessFilter(new UiProperties(true));

        MockFilterChain unsetChain = new MockFilterChain();
        unset.doFilter(request("/"), new MockHttpServletResponse(), unsetChain);
        MockFilterChain onChain = new MockFilterChain();
        on.doFilter(request("/agentic-chat"), new MockHttpServletResponse(), onChain);

        assertThat(unsetChain.getRequest()).isNotNull();
        assertThat(onChain.getRequest()).isNotNull();
    }

    @Test
    void aHeadlessServerAnswersNotFoundForEveryScreen() throws Exception {
        UiAccessFilter filter = new UiAccessFilter(new UiProperties(false));

        MockHttpServletResponse root = new MockHttpServletResponse();
        MockFilterChain rootChain = new MockFilterChain();
        filter.doFilter(request("/"), root, rootChain);
        MockHttpServletResponse push = new MockHttpServletResponse();
        filter.doFilter(request("/VAADIN/push"), push, new MockFilterChain());
        MockHttpServletResponse view = new MockHttpServletResponse();
        filter.doFilter(request("/tool-studio"), view, new MockFilterChain());

        assertThat(root.getStatus()).isEqualTo(404);
        assertThat(root.getContentAsString()).isEqualTo(UiAccessFilter.UI_DISABLED_BODY);
        assertThat(rootChain.getRequest()).isNull();
        assertThat(push.getStatus()).isEqualTo(404);
        assertThat(view.getStatus()).isEqualTo(404);
    }

    @Test
    void aHeadlessServerKeepsItsMcpAndActuatorPathsReachable() throws Exception {
        UiAccessFilter filter = new UiAccessFilter(new UiProperties(false));

        for (String path : new String[] {"/mcp", "/sse", "/actuator/health", "/actuator/bundle",
                "/oauth2/authorization/github", "/login/oauth2/code/github"}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request(path), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(path).isNotNull();
        }
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setRemoteAddr("172.17.0.1");
        return request;
    }

}
