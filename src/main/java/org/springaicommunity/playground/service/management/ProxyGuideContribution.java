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
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ProxyGuideContribution implements ManagementToolContribution {

    public static final String PROXY_GUIDE_URI = "saip://guide/proxy";
    public static final String RISK_GUIDE_URI = "saip://guide/risk-levels";
    public static final String PROMPT_NAME = "saip_proxy_server";

    private static final String MARKDOWN = "text/markdown";
    private static final String GUIDE_DIRECTORY = "management/guide/";

    @Override
    public String category() {
        return ExposeManagementContribution.CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of();
    }

    @Override
    public List<SyncResourceSpecification> resources() {
        return List.of(
                guide(PROXY_GUIDE_URI, "proxy-guide", "Exposing external MCP servers through this app",
                        "How to choose tools, combine servers, set human approval and which rules can refuse a change.",
                        "proxy.md"),
                guide(RISK_GUIDE_URI, "risk-levels-guide", "Risk levels of external MCP servers and tools",
                        "What the levels L0 to L5 mean and how they are computed.", "risk-levels.md"));
    }

    @Override
    public List<SyncPromptSpecification> prompts() {
        Prompt prompt = new Prompt(PROMPT_NAME, "Expose tools of registered MCP servers through this app",
                "Step by step procedure for choosing tools of registered MCP servers, combining them and setting "
                        + "human approval.",
                List.of(new PromptArgument("goal", "Goal", "What should be exposed and how, for example: expose "
                        + "the read-only tools of the wiki server and require approval for everything else.",
                        true)));
        return List.of(new SyncPromptSpecification(prompt, (exchange, request) -> {
            Object goal = request.arguments() == null ? null : request.arguments().get("goal");
            String text = "Goal: " + (goal == null ? "(not given)" : goal) + "\n\n"
                    + "Read the resource " + PROXY_GUIDE_URI + " first, then follow its procedure with the "
                    + "saip_mcp_* and saip_expose_* tools. Use saip_expose_preview before every change and show "
                    + "the person what will be exposed and which tools have no human approval.";
            return new GetPromptResult("Expose tools of registered MCP servers",
                    List.of(new PromptMessage(Role.USER, new TextContent(text))));
        }));
    }

    private static SyncResourceSpecification guide(String uri, String name, String title, String description,
            String file) {
        Resource resource = Resource.builder().uri(uri).name(name).title(title).description(description)
                .mimeType(MARKDOWN).build();
        return new SyncResourceSpecification(resource, (exchange, request) -> new ReadResourceResult(
                List.of(new TextResourceContents(uri, MARKDOWN, read(file)))));
    }

    static String read(String file) {
        try (InputStream stream = new ClassPathResource(GUIDE_DIRECTORY + file).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing management guide " + file, e);
        }
    }

}
