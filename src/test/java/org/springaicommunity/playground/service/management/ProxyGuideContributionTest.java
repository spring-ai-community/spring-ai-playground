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
package org.springaicommunity.playground.service.management;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.observability.McpRiskEventRingBuffer;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ApprovalMode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyGuideContributionTest {

    private static final Pattern TOOL_NAME = Pattern.compile("saip_[a-z]+(?:_[a-z]+)+");

    private final ProxyGuideContribution contribution = new ProxyGuideContribution();

    private static Set<String> registeredToolNames() {
        ManagementAuditLog auditLog = ManagementTestSupport.auditLog(
                ManagementTestSupport.options(ApprovalMode.DESTRUCTIVE), new McpRiskEventRingBuffer());
        Set<String> names = new HashSet<>();
        ManagementToolsCatalogTest.contributions(auditLog).forEach(contribution ->
                contribution.tools().forEach(tool -> names.add(tool.name())));
        return names;
    }

    private String read(String uri) {
        SyncResourceSpecification specification = contribution.resources().stream()
                .filter(candidate -> candidate.resource().uri().equals(uri)).findFirst().orElseThrow();
        TextResourceContents contents = (TextResourceContents) specification.readHandler()
                .apply(null, new ReadResourceRequest(uri)).contents().getFirst();
        return contents.text();
    }

    @Test
    void theGuidesAreServedAsMarkdownResources() {
        assertThat(contribution.resources()).extracting(specification -> specification.resource().uri())
                .containsExactly(ProxyGuideContribution.PROXY_GUIDE_URI, ProxyGuideContribution.RISK_GUIDE_URI);
        assertThat(contribution.resources()).allMatch(specification ->
                "text/markdown".equals(specification.resource().mimeType()));
        assertThat(read(ProxyGuideContribution.PROXY_GUIDE_URI)).contains("Procedure", "saip_expose_preview");
        assertThat(read(ProxyGuideContribution.RISK_GUIDE_URI)).contains("L0", "L5");
    }

    @Test
    void everyToolNamedInTheGuidesAndThePromptExists() {
        Set<String> known = registeredToolNames();
        Set<String> mentioned = new HashSet<>();
        for (String text : List.of(read(ProxyGuideContribution.PROXY_GUIDE_URI),
                read(ProxyGuideContribution.RISK_GUIDE_URI), promptText("anything"))) {
            Matcher matcher = TOOL_NAME.matcher(text);
            while (matcher.find()) {
                mentioned.add(matcher.group());
            }
        }
        mentioned.remove(ProxyGuideContribution.PROMPT_NAME);
        mentioned.removeIf(name -> name.startsWith("saip_mcp_") && name.endsWith("_"));

        assertThat(mentioned).isNotEmpty();
        assertThat(known).containsAll(mentioned.stream().filter(name -> !name.equals("saip_mcp_")).toList());
    }

    @Test
    void thePromptCarriesTheGoalAndPointsAtTheGuide() {
        String text = promptText("expose the read-only wiki tools");

        assertThat(text).contains("Goal: expose the read-only wiki tools")
                .contains(ProxyGuideContribution.PROXY_GUIDE_URI).contains("saip_expose_preview");
        assertThat(contribution.prompts()).singleElement()
                .satisfies(specification -> assertThat(specification.prompt().arguments()).singleElement()
                        .satisfies(argument -> assertThat(argument.required()).isTrue()));
    }

    @Test
    void theGuidesAreConciseEnoughToKeepContextSmall() {
        assertThat(read(ProxyGuideContribution.PROXY_GUIDE_URI)).hasSizeLessThan(6000);
        assertThat(read(ProxyGuideContribution.RISK_GUIDE_URI)).hasSizeLessThan(4000);
    }

    private String promptText(String goal) {
        SyncPromptSpecification specification = contribution.prompts().getFirst();
        GetPromptResult result = specification.promptHandler().apply(null,
                new GetPromptRequest(ProxyGuideContribution.PROMPT_NAME, Map.of("goal", goal)));
        return ((TextContent) result.messages().getFirst().content()).text();
    }

}
