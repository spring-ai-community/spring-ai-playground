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
package org.springaicommunity.playground;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HitlApprovalWaitBindingTest {

    @Test
    void bindsTheApprovalWaitFromASettingsProperty() {
        SpringAiPlaygroundOptions options = bind(new MapPropertySource("settings",
                Map.of("spring.ai.playground.hitl.approval-wait-seconds", "42")));

        assertThat(options.hitl().approvalWaitSeconds()).isEqualTo(42);
        assertThat(options.hitl().approvalWait()).isEqualTo(Duration.ofSeconds(42));
        assertThat(options.hitl().transportTimeout()).isEqualTo(Duration.ofSeconds(72));
    }

    @Test
    void bindsTheApprovalWaitFromAnEnvironmentVariable() {
        SpringAiPlaygroundOptions options = bind(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SPRING_AI_PLAYGROUND_HITL_APPROVALWAITSECONDS", "42")));

        assertThat(options.hitl().approvalWaitSeconds()).isEqualTo(42);
    }

    @Test
    void bindsTheApprovalWaitFromAnUnderscoredEnvironmentVariable() {
        SpringAiPlaygroundOptions options = bind(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SPRING_AI_PLAYGROUND_HITL_APPROVAL_WAIT_SECONDS", "42")));

        assertThat(options.hitl().approvalWaitSeconds()).isEqualTo(42);
    }

    @Test
    void defaultsToThreeMinutesWhenUnset() {
        SpringAiPlaygroundOptions options = bind(new MapPropertySource("settings", Map.of()));

        assertThat(options.hitl().approvalWaitSeconds()).isEqualTo(180);
    }

    @Test
    void capsTheApprovalWaitBelowTheChatStreamTimeout() {
        SpringAiPlaygroundOptions options = bind(new MapPropertySource("settings",
                Map.of("spring.ai.playground.hitl.approval-wait-seconds", "9000")));

        assertThat(options.hitl().approvalWaitSeconds()).isEqualTo(240);
    }

    private static SpringAiPlaygroundOptions bind(PropertySource<?> propertySource) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(propertySource);
        return new Binder(ConfigurationPropertySources.from(sources))
                .bindOrCreate("spring.ai.playground", Bindable.of(SpringAiPlaygroundOptions.class));
    }
}
