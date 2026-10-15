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
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget.Origin;
import org.springaicommunity.playground.service.mcp.risk.CanonicalHasher;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSnippetServiceTest {

    private final ConnectionSnippetService service = new ConnectionSnippetService();

    @Test
    void publishStepsOnTheLocalTargetUsePlainDocker() {
        String steps = this.service.publishSteps(entry(), DeployTarget.local());

        assertThat(steps.lines()).startsWith(
                "docker compose -f /bundles/qa-remote/compose.yaml build",
                "docker tag saip-mcp/qa-remote:0.1.0 ghcr.io/OWNER/qa-remote:0.1.0",
                "docker push ghcr.io/OWNER/qa-remote:0.1.0");
    }

    @Test
    void publishStepsOnARemoteTargetAddressTheEngineThatHoldsTheImage() {
        DeployTarget remote = DeployTarget.docker("build-box", null, "tcp://10.0.0.5:2376", null, Origin.ADDED);

        String steps = this.service.publishSteps(entry(), remote);

        assertThat(steps.lines()).startsWith(
                "docker --host tcp://10.0.0.5:2376 compose -f /bundles/qa-remote/compose.yaml build",
                "docker --host tcp://10.0.0.5:2376 tag saip-mcp/qa-remote:0.1.0 ghcr.io/OWNER/qa-remote:0.1.0",
                "docker --host tcp://10.0.0.5:2376 push ghcr.io/OWNER/qa-remote:0.1.0");
    }

    @Test
    void vsCodeLinkCarriesOneJsonObjectWithTheNameAndTheBearerHeader() {
        List<ConnectionSnippetService.DeepLink> links = this.service.deepLinksFor(entry().manifest(),
                DeployTarget.local(), 18282, Optional.of("secret token"));

        String vsCode = links.getLast().url();
        assertThat(vsCode).startsWith("vscode:mcp/install?%7B").doesNotContain("+").doesNotContain("&config=");
        String payload = URLDecoder.decode(vsCode.substring("vscode:mcp/install?".length()), StandardCharsets.UTF_8);
        assertThat(payload).isEqualTo("{\"name\":\"qa-remote\",\"type\":\"http\","
                + "\"url\":\"http://localhost:18282/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer secret token\"}}");
    }

    @Test
    void cursorLinkKeepsTheNameParameterAndABase64Config() {
        List<ConnectionSnippetService.DeepLink> links = this.service.deepLinksFor(entry().manifest(),
                DeployTarget.local(), 18282, Optional.empty());

        String cursor = links.getFirst().url();
        assertThat(cursor).startsWith("cursor://anysphere.cursor-deeplink/mcp/install?name=qa-remote&config=");
        String config = new String(Base64.getDecoder().decode(cursor.substring(cursor.indexOf("&config=") + 8)),
                StandardCharsets.UTF_8);
        assertThat(config).isEqualTo("{\"type\":\"http\",\"url\":\"http://localhost:18282/mcp\"}");
    }

    private static BundleEntry entry() {
        BundleManifest manifest = new BundleManifest(BundleManifest.CURRENT_BUNDLE_VERSION,
                new BundleManifest.Server("qa-remote", "d", "0.1.0"),
                new BundleManifest.Requires("0.2.0", List.of(), new BundleManifest.Models(null, null), List.of()),
                new BundleManifest.Contents(List.of(), null, null, null),
                new BundleManifest.Risk("L0", List.of()),
                new BundleManifest.Integrity(CanonicalHasher.MCP_TOOL_SCHEME, Map.of(), Map.of()),
                new BundleManifest.Provenance("test", "now"));
        return new BundleEntry("qa-remote", manifest, Path.of("/bundles/qa-remote"), null);
    }

}
