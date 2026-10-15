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

import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget.Origin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployTargetTest {

    @Test
    void theBuiltInTargetUsesTheCliDefaultAndConnectsThroughLocalhost() {
        DeployTarget local = DeployTarget.local();
        assertThat(local.isLocal()).isTrue();
        assertThat(local.isRemote()).isFalse();
        assertThat(local.cliArguments()).isEmpty();
        assertThat(local.endpointUrl(18282)).isEqualTo("http://localhost:18282");
        assertThat(local.probeUrl(18282)).isEqualTo("http://127.0.0.1:18282");
        assertThat(local.containerHostUrl("http://host.docker.internal:11434")).isEqualTo("http://localhost:11434");
    }

    @Test
    void theConnectHostComesFromThePublicHostThenTheDockerHost() {
        DeployTarget ssh = DeployTarget.docker("box", null, "ssh://ops@build.example.com:2222", null, Origin.ADDED);
        assertThat(ssh.endpointHost()).isEqualTo("build.example.com");
        assertThat(ssh.cliArguments()).containsExactly("--host", "ssh://ops@build.example.com:2222");
        assertThat(ssh.isRemote()).isTrue();

        DeployTarget tcp = DeployTarget.docker("tls", null, "tcp://10.0.0.5:2376", "mcp.example.com", Origin.ADDED);
        assertThat(tcp.endpointUrl(18400)).isEqualTo("http://mcp.example.com:18400");
        assertThat(tcp.probeUrl(18400)).isEqualTo("http://mcp.example.com:18400");
        assertThat(tcp.containerHostUrl("http://host.docker.internal:11434"))
                .isEqualTo("http://mcp.example.com:11434");

        DeployTarget ipv6 = DeployTarget.docker("v6", null, "tcp://[fd00::5]:2376", null, Origin.ADDED);
        assertThat(ipv6.endpointUrl(18400)).isEqualTo("http://[fd00::5]:18400");
    }

    @Test
    void aSocketTargetIsAnotherLocalEngineNotARemoteOne() {
        DeployTarget colima = DeployTarget.docker("colima", null, "unix:///Users/me/.colima/docker.sock", null,
                Origin.CONFIGURATION);
        assertThat(colima.isLocal()).isFalse();
        assertThat(colima.isRemote()).isFalse();
        assertThat(colima.endpointHost()).isEqualTo("localhost");
    }

    @Test
    void aContextTargetPassesTheContextAndConnectsThroughItsEndpoint() {
        DeployTarget context = DeployTarget.docker("prod", "prod", "ssh://ops@prod.example.com", null,
                Origin.DOCKER_CONTEXT);
        assertThat(context.cliArguments()).containsExactly("--context", "prod");
        assertThat(context.endpointHost()).isEqualTo("prod.example.com");
        assertThat(context.where()).isEqualTo("docker context prod (ssh://ops@prod.example.com)");
    }

    @Test
    void invalidTargetsAreRejected() {
        assertThatThrownBy(() -> DeployTarget.docker("Bad Name", null, "ssh://host", null, Origin.ADDED))
                .hasMessageContaining("lowercase");
        assertThatThrownBy(() -> DeployTarget.docker("local", null, "ssh://host", null, Origin.ADDED))
                .hasMessageContaining("built-in");
        assertThatThrownBy(() -> DeployTarget.docker("box", null, null, null, Origin.ADDED))
                .hasMessageContaining("docker context or a docker host");
        assertThatThrownBy(() -> DeployTarget.docker("box", "ctx", "ssh://host", null, Origin.CONFIGURATION))
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> DeployTarget.docker("box", null, "http://host:2375", null, Origin.ADDED))
                .hasMessageContaining("ssh://, tcp://");
    }

}
