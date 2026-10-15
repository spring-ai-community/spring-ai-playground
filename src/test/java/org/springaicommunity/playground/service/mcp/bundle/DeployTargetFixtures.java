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

import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ProcResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DeployTargetFixtures {

    public static final String REMOTE_CONTEXTS = """
            {"Name":"default","DockerEndpoint":"unix:///var/run/docker.sock"}
            {"Name":"build-box","DockerEndpoint":"ssh://ops@build.example.com"}
            {"Name":"offline","DockerEndpoint":"tcp://10.9.9.9:2375"}
            """;

    private DeployTargetFixtures() {
    }

    public static DeployTargetService service(Path home, Map<String, String> properties, Set<String> unreachable,
            List<List<String>> commands) {
        DockerCliService docker = new DockerCliService((command, extraEnv, timeout) -> {
            commands.add(command);
            if (command.contains("context")) return new ProcResult(0, REMOTE_CONTEXTS, "");
            if (command.contains("version")) {
                boolean down = unreachable.stream().anyMatch(command::contains);
                return down ? new ProcResult(1, "", "Cannot connect") : new ProcResult(0, "29.3.1\n", "");
            }
            return new ProcResult(0, "", "");
        }, baseUrl -> true, "docker");
        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return new DeployTargetService(docker, options, home, environment);
    }

}
