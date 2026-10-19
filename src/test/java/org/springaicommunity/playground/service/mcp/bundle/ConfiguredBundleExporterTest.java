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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.CodeType;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.vectorstore.RagPipeline;
import org.springaicommunity.playground.service.vectorstore.ConfiguredKnowledgeLoader;
import org.springaicommunity.playground.service.vectorstore.RagPipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.ai.playground.shipyard.image-tag=9.9.9",
        "spring.ai.playground.shipyard.servers[0].name=cfg-test-server",
        "spring.ai.playground.shipyard.servers[0].description=declared in configuration",
        "spring.ai.playground.shipyard.servers[0].version=1.2.0",
        "spring.ai.playground.shipyard.servers[0].tools[0]=cfgExportTool",
        "spring.ai.playground.shipyard.servers[0].transport=http",
        "spring.ai.playground.shipyard.servers[0].host-port=18555"
})
class ConfiguredBundleExporterTest {

    private static final String SERVER_NAME = "cfg-test-server";
    private static final String TOOL_ID = "cfg-t1";

    @Autowired
    private ConfiguredBundleExporter exporter;

    @Autowired
    private SpringAiPlaygroundOptions playgroundOptions;

    @Autowired
    private BundleExportService bundleExportService;

    @Autowired
    private RagPipelineService ragPipelineService;

    @Autowired
    private BundleRegistryService bundleRegistryService;

    @Autowired
    private ToolSpecService toolSpecService;

    @Autowired
    private BundleArtifactWriter artifactWriter;

    @Autowired
    private BundleFreshnessService freshnessService;

    @Autowired
    private KnowledgeContribution knowledgeContribution;
    @Autowired
    private ConfiguredKnowledgeLoader knowledgeLoader;

    @Autowired
    private Path springAiPlaygroundHomeDir;

    @AfterEach
    void cleanup() {
        this.bundleRegistryService.delete(SERVER_NAME);
        this.toolSpecService.deleteToolSpec(TOOL_ID);
    }

    @Test
    void aKnowledgeSourceIsResolvedByPipelineNameAndGetsItsDefaults() {
        publishTool();
        RagPipeline pipeline = this.ragPipelineService.create("Cfg Pipeline", "declared knowledge",
                List.of("docInfoId-cfg-missing"), RagPipeline.PreRetrievalConfig.defaults(),
                new RagPipeline.RetrievalConfig(null, 4, null), RagPipeline.PostRetrievalConfig.defaults(),
                RagPipeline.GenerationConfig.defaults());
        try {
            ShipyardServer server = new ShipyardServer("cfg-knowledge", null, null, List.of("cfgExportTool"), null,
                    null, null, null, null, null, null,
                    List.of(new SpringAiPlaygroundOptions.ShipyardKnowledge("Cfg Pipeline", null, null, null)), null);

            ShipRequest normalized = this.bundleExportService.normalize(this.exporter.toRequest(server));

            ShipRequest.Knowledge knowledge = normalized.knowledge().getFirst();
            assertThat(knowledge.sourceId()).isEqualTo(pipeline.id());
            assertThat(knowledge.toolName()).isEqualTo("search_cfg_pipeline");
            assertThat(knowledge.topK()).isEqualTo(4);
            assertThat(normalized.models().embedding()).isNotNull();
            assertThat(normalized.toolCount()).isEqualTo(2);
        } finally {
            this.ragPipelineService.deleteById(pipeline.id());
        }
    }

    @Test
    void anUnknownKnowledgeSourceIsReportedByName() {
        publishTool();
        ShipyardServer server = new ShipyardServer("cfg-knowledge", null, null, List.of("cfgExportTool"), null,
                null, null, null, null, null, null,
                List.of(new SpringAiPlaygroundOptions.ShipyardKnowledge("No Such Pipeline", null, null, null)), null);

        assertThatThrownBy(() -> this.exporter.toRequest(server))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown knowledge source 'No Such Pipeline'");
    }

    private ShipyardServer configuredServer() {
        return this.playgroundOptions.shipyard().servers().getFirst();
    }

    private void publishTool() {
        this.toolSpecService.update(new ToolSpec(TOOL_ID, "cfgExportTool", "configured export tool", List.of(),
                List.of(), "return 1;", CodeType.Javascript, null).withDraft(false));
    }

    @Test
    void aConfiguredServerIsExportedOnceAndLeftAloneWhileUnchanged() throws Exception {
        publishTool();

        this.exporter.reconcile(configuredServer());

        BundleEntry entry = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        assertThat(entry.buildConfig().toolIds()).containsExactly(TOOL_ID);
        assertThat(entry.buildConfig().version()).isEqualTo("1.2.0");
        assertThat(entry.buildConfig().transport()).isEqualTo(Transport.HTTP);
        assertThat(entry.effectiveHttpPort()).isEqualTo(18555);
        assertThat(entry.manifest().server().description()).isEqualTo("declared in configuration");
        assertThat(Files.readString(entry.dir().resolve("Dockerfile")))
                .startsWith("FROM ghcr.io/spring-ai-community/spring-ai-playground:9.9.9");
        String firstExport = entry.manifest().provenance().exportedAt();

        this.exporter.reconcile(configuredServer());

        assertThat(this.bundleRegistryService.find(SERVER_NAME).orElseThrow().manifest().provenance().exportedAt())
                .isEqualTo(firstExport);
    }

    @Test
    void aConfiguredServerIsRebuiltWhenItsToolChangesAfterExport() {
        publishTool();
        this.exporter.reconcile(configuredServer());
        String firstExport = this.bundleRegistryService.find(SERVER_NAME).orElseThrow().manifest().provenance()
                .exportedAt();

        this.toolSpecService.update(new ToolSpec(TOOL_ID, "cfgExportTool", "configured export tool", List.of(),
                List.of(), "return 2;", CodeType.Javascript, null).withDraft(false));
        this.exporter.reconcile(configuredServer());

        BundleEntry rebuilt = this.bundleRegistryService.find(SERVER_NAME).orElseThrow();
        assertThat(rebuilt.manifest().provenance().exportedAt()).isNotEqualTo(firstExport);
    }

    @Test
    void anUnknownToolNameLeavesNoBundleBehind() {
        this.exporter.reconcile(configuredServer());

        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isEmpty();
    }

    @Test
    void anUnexportedServerIsRecordedAsAProblemUntilItsToolExists() {
        this.exporter.reconcile(configuredServer());

        assertThat(this.exporter.problems()).containsEntry(SERVER_NAME,
                "not exported: unknown tool name(s) [cfgExportTool]");

        publishTool();
        this.exporter.reconcile(configuredServer());

        assertThat(this.exporter.problems()).doesNotContainKey(SERVER_NAME);
        assertThat(this.bundleRegistryService.find(SERVER_NAME)).isPresent();
    }

    @Test
    void anAutoRunServerIsStartedOnItsTargetAfterExport() {
        publishTool();
        Map<String, String> properties = Map.of(
                "spring.ai.playground.shipyard.servers[0].name", SERVER_NAME,
                "spring.ai.playground.shipyard.servers[0].tools[0]", "cfgExportTool",
                "spring.ai.playground.shipyard.servers[0].transport", "http",
                "spring.ai.playground.shipyard.servers[0].host-port", "18555",
                "spring.ai.playground.shipyard.servers[0].auto-run", "true");
        SpringAiPlaygroundOptions options = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("spring.ai.playground", SpringAiPlaygroundOptions.class);
        List<List<String>> commands = new ArrayList<>();
        DeployTargetService deployTargets = DeployTargetFixtures.service(this.springAiPlaygroundHomeDir
                .resolve("auto-run-test"), properties, Set.of(), commands);
        ConfiguredBundleExporter autoRunExporter = new ConfiguredBundleExporter(options, this.springAiPlaygroundHomeDir,
                this.bundleExportService, this.bundleRegistryService, this.artifactWriter, this.toolSpecService,
                deployTargets, this.freshnessService, this.knowledgeContribution, this.knowledgeLoader);

        autoRunExporter.reconcile(options.shipyard().servers().getFirst());

        await().atMost(Duration.ofSeconds(10)).until(() -> commands.stream().anyMatch(command -> command.contains("up")));
        List<String> up = commands.stream().filter(command -> command.contains("up")).findFirst().orElseThrow();
        assertThat(up).contains("compose", "--build");
        assertThat(deployTargets.targetOf(SERVER_NAME).isLocal()).isTrue();
        assertThat(autoRunExporter.problems()).isEmpty();
    }

    @Test
    void theUiCannotShipOverAServerManagedByConfiguration() {
        publishTool();
        ShipRequest request = new ShipRequest(SERVER_NAME, "from the ui", "0.1.0",
                List.of(TOOL_ID), Transport.BOTH, null, null);

        assertThat(this.bundleExportService.managedByConfiguration(SERVER_NAME)).isTrue();
        assertThat(this.bundleExportService.validate(request).blockers())
                .anyMatch(blocker -> blocker.contains("managed by configuration"));
        assertThat(this.bundleExportService.validate(request).warnings())
                .noneMatch(warning -> warning.contains("is not a release"));
    }

}
