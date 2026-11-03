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

import org.springaicommunity.playground.SpringAiPlaygroundOptions;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.JsSandbox;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springaicommunity.playground.service.tool.ToolSpec.SandboxOverrides;
import org.springaicommunity.playground.service.tool.ToolSpecService;
import org.springaicommunity.playground.service.tool.policy.SandboxPostureCalculator.Explanation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class ToolAuthoringGuide {

    public static final String OVERVIEW_URI = "saip://guide/tool-authoring";
    public static final String EXAMPLES_URI = "saip://guide/tool-authoring/examples";
    public static final String EXAMPLE_PREFIX = "saip://examples/";
    public static final String EXAMPLE_TEMPLATE = EXAMPLE_PREFIX + "{name}";

    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    private static final int SUMMARY_LIMIT = 100;
    private static final List<String> STARTING_POINTS = List.of("stats", "convertCurrency", "parseCsv",
            "extractPageContent", "searchNaver", "sendSlackMessage", "writeTextFile");
    private static final Map<String, String> STARTING_POINT_REASONS = Map.of(
            "stats", "pure computation, locked sandbox (L0)",
            "convertCurrency", "public API with an allowed host (L3)",
            "parseCsv", "safety.parser helper, no network",
            "extractPageContent", "fetch plus HTML parsing, human approval",
            "searchNaver", "API key as ${ENV} static variables",
            "sendSlackMessage", "secret webhook URL and human approval",
            "writeTextFile", "file write (L4) with human approval");

    public record Topic(String uri, String name, String title, String description, String file) {
    }

    public static final List<Topic> TOPICS = List.of(
            new Topic(OVERVIEW_URI, "tool-authoring-guide", "Making a tool for this app",
                    "Start here: the procedure from idea to published tool and the rules of the tool spec.",
                    "tool-authoring.md"),
            new Topic(OVERVIEW_URI + "/runtime", "tool-authoring-runtime", "Tool code: helpers and rules",
                    "How the JavaScript code gets its inputs, returns results and which helpers it can use.",
                    "tool-authoring-runtime.md"),
            new Topic(OVERVIEW_URI + "/sandbox", "tool-authoring-sandbox", "Sandbox and risk levels",
                    "The sandbox baseline of this app, the fields that widen it and what raises the risk level.",
                    "tool-authoring-sandbox.md"),
            new Topic(OVERVIEW_URI + "/testing", "tool-authoring-testing", "Testing, publishing and exposure",
                    "The Local Pass, how to read a test result, who must approve and how exposure works.",
                    "tool-authoring-testing.md"),
            new Topic(OVERVIEW_URI + "/mistakes", "tool-authoring-mistakes", "Frequent mistakes",
                    "Symptoms of common failures and how to fix them.", "tool-authoring-mistakes.md"),
            new Topic(EXAMPLES_URI, "tool-authoring-examples", "Tested example tools",
                    "Bundled tools to copy from, with risk level and purpose. Read one with saip://examples/{name}.",
                    null));

    private final ToolSpecService toolSpecService;
    private final SpringAiPlaygroundOptions options;

    public ToolAuthoringGuide(ToolSpecService toolSpecService, SpringAiPlaygroundOptions options) {
        this.toolSpecService = toolSpecService;
        this.options = options;
    }

    public String render(Topic topic) {
        if (topic.file() == null) {
            return examplesIndex();
        }
        String text = ProxyGuideContribution.read(topic.file());
        return text.replace("{{baseline}}", baseline()).replace("{{risk-table}}", riskTable());
    }

    public Optional<String> example(String name) {
        return this.toolSpecService.defaultTools().stream().filter(spec -> spec.name().equalsIgnoreCase(name))
                .findFirst().map(this::exampleText);
    }

    public List<String> exampleNames() {
        return this.toolSpecService.defaultTools().stream().map(ToolSpec::name).sorted().toList();
    }

    String baseline() {
        JsSandbox sandbox = this.options.toolStudio() == null ? null : this.options.toolStudio().jsSandbox();
        Long configuredTimeout = this.options.toolStudio() == null ? null : this.options.toolStudio().timeoutSeconds();
        long timeout = configuredTimeout == null ? DEFAULT_TIMEOUT_SECONDS : configuredTimeout;
        long statements = sandbox == null || sandbox.maxStatements() == null ? 0 : sandbox.maxStatements();
        StringBuilder text = new StringBuilder();
        text.append("- Network: ").append(sandbox != null && sandbox.allowNetworkIo() ? "allowed" : "blocked")
                .append(", files: ").append(sandbox != null && sandbox.allowFileIo() ? "allowed" : "none")
                .append(".\n");
        text.append("- Time limit: ").append(timeout).append(" seconds per run");
        if (statements > 0) {
            text.append(", statement budget: ").append(statements);
        }
        text.append(".\n");
        text.append("- Java classes allowed: ").append(joined(sandbox == null ? null : sandbox.allowClasses()))
                .append(".\n");
        text.append("- Java classes always denied: ").append(joined(sandbox == null ? null : sandbox.denyClasses()))
                .append(".\n");
        return text.toString().stripTrailing();
    }

    String riskTable() {
        List<String> rows = new ArrayList<>();
        rows.add(riskRow("Locked default, nothing requested", null));
        rows.add(riskRow("networkMode ALLOWLIST with exact hosts",
                overrides("allowlist", Set.of("api.example.com"), null, null, null, Set.of())));
        rows.add(riskRow("networkMode STRICT (host known only at run time)",
                overrides("strict", Set.of(), null, null, null, Set.of())));
        rows.add(riskRow("fileRead", overrides(null, Set.of(), true, null, null, Set.of())));
        rows.add(riskRow("networkMode OPEN", overrides("open", Set.of(), null, null, null, Set.of())));
        rows.add(riskRow("hostsAllow contains *", overrides("allowlist", Set.of("*"), null, null, null, Set.of())));
        rows.add(riskRow("fileWrite", overrides(null, Set.of(), true, true, null, Set.of())));
        rows.add(riskRow("destructive", overrides(null, Set.of(), null, null, true, Set.of())));
        rows.add(riskRow("addAllowClasses with an ordinary class", overrides(null, Set.of(), null, null, null,
                Set.of("java.util.concurrent.*"))));
        rows.add(riskRow("addAllowClasses with a network class", overrides(null, Set.of(), null, null, null,
                Set.of("java.net.http.HttpClient"))));
        rows.add(riskRow("addAllowClasses with java.lang.Runtime", overrides(null, Set.of(), null, null, null,
                Set.of("java.lang.Runtime"))));
        return "| Requested | Level | Above the baseline |\n|---|---|---|\n" + String.join("\n", rows);
    }

    private String riskRow(String label, SandboxOverrides overrides) {
        Explanation explanation = this.toolSpecService.explainRisk(overrides);
        return "| " + label + " | " + explanation.level() + " | " + (explanation.elevated() ? "yes" : "no") + " |";
    }

    private static SandboxOverrides overrides(String networkMode, Set<String> hosts, Boolean fileRead,
            Boolean fileWrite, Boolean destructive, Set<String> addAllowClasses) {
        return new SandboxOverrides(addAllowClasses, Set.of(), Set.of(), Set.of(), networkMode, hosts, fileRead,
                fileWrite, null, destructive);
    }

    private String examplesIndex() {
        List<ToolSpec> specs = this.toolSpecService.defaultTools();
        StringBuilder text = new StringBuilder("# Tested example tools\n\n");
        text.append("These bundled tools passed their Local Pass. Read one completely with `saip://examples/{name}` ")
                .append("or `saip_tools_get`, copy its fields into `saip_tools_save_draft` with a new name and ")
                .append("change what you need.\n\n## Good starting points\n\n");
        STARTING_POINTS.forEach(name -> specs.stream().filter(spec -> spec.name().equals(name)).findFirst()
                .ifPresent(spec -> text.append("- `").append(name).append("`: ")
                        .append(STARTING_POINT_REASONS.getOrDefault(name, "")).append("\n")));
        Map<String, List<ToolSpec>> byCategory = new TreeMap<>();
        specs.forEach(spec -> byCategory.computeIfAbsent(spec.category() == null ? "CUSTOM" : spec.category(),
                key -> new ArrayList<>()).add(spec));
        byCategory.forEach((category, members) -> {
            text.append("\n## ").append(category).append("\n\n| Name | Level | What it does |\n|---|---|---|\n");
            members.stream().sorted(Comparator.comparing(ToolSpec::name)).forEach(spec -> text.append("| `")
                    .append(spec.name()).append("` | ").append(this.toolSpecService.riskLevelOf(spec)).append(" | ")
                    .append(firstSentence(spec.description())).append(" |\n"));
        });
        return text.toString();
    }

    private String exampleText(ToolSpec spec) {
        Explanation explanation = this.toolSpecService.explainRisk(spec.sandboxOverrides());
        StringBuilder text = new StringBuilder("# Example: ").append(spec.name()).append("\n\n");
        text.append("Category ").append(spec.category() == null ? "CUSTOM" : spec.category()).append(", risk ")
                .append(explanation.level()).append(", human approval ")
                .append(this.toolSpecService.requiresApproval(spec) ? "required" : "not required").append(".\n\n");
        text.append("## Description\n\n").append(spec.description() == null ? "" : spec.description()).append("\n\n");
        text.append("## Parameters\n\n");
        if (spec.params() == null || spec.params().isEmpty()) {
            text.append("None.\n\n");
        } else {
            text.append("| Name | Type | Required | testValue | Description |\n|---|---|---|---|---|\n");
            spec.params().forEach(param -> text.append("| ").append(param.name()).append(" | ").append(param.type())
                    .append(" | ").append(param.required()).append(" | ").append(cell(param.testValue()))
                    .append(" | ").append(cell(param.description())).append(" |\n"));
            text.append("\n");
        }
        text.append("## Static variables\n\n");
        if (spec.staticVariables() == null || spec.staticVariables().isEmpty()) {
            text.append("None.\n\n");
        } else {
            spec.staticVariables().forEach(entry -> text.append("- ").append(entry.getKey()).append(" = ")
                    .append(entry.getValue()).append("\n"));
            text.append("\n");
        }
        text.append("## Sandbox\n\n").append(sandboxLine(spec.sandboxOverrides())).append("\n\n");
        text.append("## Code\n\n```js\n").append(spec.code() == null ? "" : spec.code().stripTrailing())
                .append("\n```\n");
        return text.toString();
    }

    private static String sandboxLine(SandboxOverrides overrides) {
        List<String> parts = new ArrayList<>();
        if (overrides.networkMode() != null) {
            parts.add("networkMode=" + overrides.networkMode().toUpperCase(Locale.ROOT));
        }
        if (overrides.hostsAllow() != null && !overrides.hostsAllow().isEmpty()) {
            parts.add("hostsAllow=" + new TreeSet<>(overrides.hostsAllow()));
        }
        if (Boolean.TRUE.equals(overrides.fileRead())) {
            parts.add("fileRead=true");
        }
        if (Boolean.TRUE.equals(overrides.fileWrite())) {
            parts.add("fileWrite=true");
        }
        if (Boolean.TRUE.equals(overrides.destructive())) {
            parts.add("destructive=true");
        }
        if (overrides.addAllowClasses() != null && !overrides.addAllowClasses().isEmpty()) {
            parts.add("addAllowClasses=" + new TreeSet<>(overrides.addAllowClasses()));
        }
        return parts.isEmpty() ? "Locked default: no sandbox settings needed." : String.join(", ", parts);
    }

    private static String joined(Set<String> classes) {
        return classes == null || classes.isEmpty() ? "none"
                : classes.stream().sorted().map(name -> "`" + name + "`").collect(Collectors.joining(", "));
    }

    private static String firstSentence(String description) {
        if (description == null) {
            return "";
        }
        String flat = description.strip().lines().findFirst().orElse("").strip();
        int end = flat.indexOf(". ");
        String sentence = end > 0 ? flat.substring(0, end + 1) : flat;
        String clipped = sentence.length() <= SUMMARY_LIMIT ? sentence : sentence.substring(0, SUMMARY_LIMIT) + "...";
        return cell(clipped);
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "/").replace("\n", " ");
    }

}
