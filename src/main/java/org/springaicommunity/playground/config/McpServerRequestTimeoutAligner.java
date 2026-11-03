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

import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class McpServerRequestTimeoutAligner implements BeanPostProcessor {

    private static final String APPROVAL_WAIT_PROPERTY = "spring.ai.playground.hitl.approval-wait-seconds";

    private final Environment environment;

    public McpServerRequestTimeoutAligner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof McpServerProperties serverProperties) {
            Duration floor = approvalTransportTimeout();
            Duration configured = serverProperties.getRequestTimeout();
            if (configured == null || configured.compareTo(floor) < 0) {
                serverProperties.setRequestTimeout(floor);
            }
        }
        return bean;
    }

    private Duration approvalTransportTimeout() {
        Integer approvalWaitSeconds = this.environment.getProperty(APPROVAL_WAIT_PROPERTY, Integer.class);
        return new SpringAiPlaygroundOptions.Hitl(approvalWaitSeconds).transportTimeout();
    }
}
