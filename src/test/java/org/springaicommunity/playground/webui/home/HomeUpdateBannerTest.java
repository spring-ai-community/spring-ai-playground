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
package org.springaicommunity.playground.webui.home;

import com.vaadin.browserless.SpringBrowserlessTest;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.home.ReleaseCheckService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class HomeUpdateBannerTest extends SpringBrowserlessTest {

    @MockitoBean
    private ReleaseCheckService releaseCheckService;

    @Test
    void homeAsksTheServiceForAnUpdateAndShowsNoBannerWhenNothingNewerExists() {
        when(this.releaseCheckService.availableUpdate()).thenReturn(Optional.empty());

        HomeView view = navigate(HomeView.class);

        verify(this.releaseCheckService, timeout(5000)).availableUpdate();
        roundTrip();
        assertThat(view.getElement().getTextRecursively()).doesNotContain("Update available");
    }

}
