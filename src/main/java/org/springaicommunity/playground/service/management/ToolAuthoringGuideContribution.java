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
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springaicommunity.playground.service.management.ToolAuthoringGuide.Topic;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ToolAuthoringGuideContribution implements ManagementToolContribution {

    public static final String PROMPT_NAME = "saip_create_tool";

    private static final String MARKDOWN = "text/markdown";

    private final ToolAuthoringGuide guide;

    public ToolAuthoringGuideContribution(ToolAuthoringGuide guide) {
        this.guide = guide;
    }

    @Override
    public String category() {
        return ToolsManagementContribution.CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of();
    }

    @Override
    public List<SyncResourceSpecification> resources() {
        return ToolAuthoringGuide.TOPICS.stream().map(this::topicResource).toList();
    }

    @Override
    public List<SyncResourceTemplateSpecification> resourceTemplates() {
        ResourceTemplate template = ResourceTemplate.builder().uriTemplate(ToolAuthoringGuide.EXAMPLE_TEMPLATE)
                .name("tool-example").title("Bundled example tool")
                .description("A tested tool with its parameters, sandbox settings and code, by name, for example "
                        + "saip://examples/convertCurrency. Names are listed in "
                        + ToolAuthoringGuide.EXAMPLES_URI + ".")
                .mimeType(MARKDOWN).build();
        return List.of(new SyncResourceTemplateSpecification(template, (exchange, request) -> {
            String name = URLDecoder.decode(request.uri().substring(ToolAuthoringGuide.EXAMPLE_PREFIX.length()),
                    StandardCharsets.UTF_8);
            String text = this.guide.example(name).orElseThrow(() -> new IllegalArgumentException(
                    "No example named '" + name + "'. The names are listed in " + ToolAuthoringGuide.EXAMPLES_URI
                            + "."));
            return new ReadResourceResult(List.of(new TextResourceContents(request.uri(), MARKDOWN, text)));
        }));
    }

    @Override
    public List<SyncPromptSpecification> prompts() {
        Prompt prompt = new Prompt(PROMPT_NAME, "Create a tool on this app",
                "Step by step procedure to design, test and publish a new tool, with the authoring guide attached.",
                List.of(new PromptArgument("goal", "Goal", "What the tool should do, for example: convert an "
                        + "amount between two currencies.", true)));
        return List.of(new SyncPromptSpecification(prompt, (exchange, request) -> {
            Object goal = request.arguments() == null ? null : request.arguments().get("goal");
            String text = "Goal: " + (goal == null ? "(not given)" : goal) + "\n\n"
                    + "Make this tool with the saip_tools_* tools. First look for a similar tool in "
                    + ToolAuthoringGuide.EXAMPLES_URI + " and copy it. Ask for the least sandbox capability that "
                    + "works and check it with saip_tools_check_risk. Save a draft, run saip_tools_test until it "
                    + "passes, then saip_tools_publish. The guide is attached.";
            EmbeddedResource overview = new EmbeddedResource(null, new TextResourceContents(
                    ToolAuthoringGuide.OVERVIEW_URI, MARKDOWN, this.guide.render(ToolAuthoringGuide.TOPICS.get(0))));
            return new GetPromptResult("Create a tool on this app", List.of(
                    new PromptMessage(Role.USER, new TextContent(text)),
                    new PromptMessage(Role.USER, overview)));
        }));
    }

    private SyncResourceSpecification topicResource(Topic topic) {
        Resource resource = Resource.builder().uri(topic.uri()).name(topic.name()).title(topic.title())
                .description(topic.description()).mimeType(MARKDOWN).build();
        return new SyncResourceSpecification(resource, (exchange, request) -> new ReadResourceResult(
                List.of(new TextResourceContents(topic.uri(), MARKDOWN, this.guide.render(topic)))));
    }

}
