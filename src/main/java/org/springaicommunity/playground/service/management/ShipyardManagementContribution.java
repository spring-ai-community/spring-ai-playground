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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ComposedTool;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardKnowledge;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardModel;
import org.springaicommunity.playground.SpringAiPlaygroundOptions.ShipyardServer;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ExportResult;
import org.springaicommunity.playground.service.mcp.bundle.BundleExportService.ValidationReport;
import org.springaicommunity.playground.service.mcp.bundle.BundleManifest;
import org.springaicommunity.playground.service.mcp.bundle.BundleRegistryService.BundleEntry;
import org.springaicommunity.playground.service.mcp.bundle.ConfiguredBundleExporter;
import org.springaicommunity.playground.service.mcp.bundle.ConnectionSnippetService;
import org.springaicommunity.playground.service.mcp.bundle.DeployTarget;
import org.springaicommunity.playground.service.mcp.bundle.DeployTargetService;
import org.springaicommunity.playground.observability.logs.LogQueryService;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.ContainerState;
import org.springaicommunity.playground.service.mcp.bundle.DockerCliService.DockerAvailability;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunPlan;
import org.springaicommunity.playground.service.mcp.bundle.RunPlanner.RunRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest;
import org.springaicommunity.playground.service.mcp.bundle.ShipRequest.Transport;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.FlowDraft;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerState;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ServerSummary;
import org.springaicommunity.playground.service.mcp.bundle.ShipyardService.ToolChoices;
import org.springaicommunity.playground.service.tool.ToolManifest.Sandbox.RiskLevel;
import org.springaicommunity.playground.service.tool.ToolSpec;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class ShipyardManagementContribution implements ManagementToolContribution {

    public static final String CATEGORY = "shipyard";
    public static final String GUIDE_URI = "saip://guide/shipyard";
    public static final String SHIPYARD_STATUS_URI = "saip://status/shipyard";
    public static final String PROMPT_NAME = "saip_shipyard_server";

    static final int LOG_LINES_DEFAULT = 100;
    static final int LOG_LINES_MAX = 1000;
    private static final String MARKDOWN = "text/markdown";

    private final ShipyardService shipyardService;
    private final BundleExportService bundleExportService;
    private final ConfiguredBundleExporter configuredBundleExporter;
    private final RunPlanner runPlanner;
    private final DeployTargetService deployTargetService;
    private final ConnectionSnippetService snippetService;

    public ShipyardManagementContribution(ShipyardService shipyardService, BundleExportService bundleExportService,
            ConfiguredBundleExporter configuredBundleExporter, RunPlanner runPlanner,
            DeployTargetService deployTargetService, ConnectionSnippetService snippetService) {
        this.shipyardService = shipyardService;
        this.bundleExportService = bundleExportService;
        this.configuredBundleExporter = configuredBundleExporter;
        this.runPlanner = runPlanner;
        this.deployTargetService = deployTargetService;
        this.snippetService = snippetService;
    }

    public record NoInput() {
    }

    public record ServerInput(@JsonPropertyDescription("Shipped server name from saip_shipyard_list_servers.")
                              String server) {
    }

    public record LogsInput(@JsonPropertyDescription("Shipped server name.") String server,
                            @Nullable @JsonPropertyDescription("Number of trailing log lines, default 100, max 1000.")
                            Integer lines,
                            @Nullable @JsonPropertyDescription("Only lines of this level: ERROR, WARN, INFO, DEBUG or TRACE.")
                            String level,
                            @Nullable @JsonPropertyDescription("Only lines that contain this text, ignoring case.")
                            String text) {
    }

    public record RunInput(
            @JsonPropertyDescription("Shipped server name.") String server,
            @Nullable @JsonPropertyDescription("Deploy target name from saip_shipyard_targets; default is the target "
                    + "the server last ran on, or local.") String target,
            @Nullable @JsonPropertyDescription("Ollama endpoint the container should use when the server needs an "
                    + "Ollama model; default reaches the Ollama of this machine through host.docker.internal.")
            String ollamaEndpoint) {
    }

    public record ComposedToolInput(
            @JsonPropertyDescription("Name of an MCP server registered in this app.") String server,
            @JsonPropertyDescription("Tool name as that server publishes it.") String tool,
            @Nullable @JsonPropertyDescription("Name the shipped server publishes it under; default <server>_<tool>.")
            String alias,
            @Nullable @JsonPropertyDescription("Replacement description; default the upstream text.")
            String description,
            @Nullable @JsonPropertyDescription("Ask the calling person before each call; default false.")
            Boolean hitl) {
    }

    public record ModelInput(@JsonPropertyDescription("Provider, for example ollama or openai.") String provider,
                             @JsonPropertyDescription("Model name, for example qwen3.5:4b.") String model) {
    }

    public record KnowledgeInput(
            @JsonPropertyDescription("RAG pipeline name or id, or a knowledge-base document title or id, from "
                    + "saip_shipyard_list_shippable.") String source,
            @Nullable @JsonPropertyDescription("Tool name, default search_<source slug>; must not start with saip_.")
            String name,
            @Nullable @JsonPropertyDescription("Tool description shown to clients; default names the documents.")
            String description,
            @Nullable @JsonPropertyDescription("Excerpts returned per query; default the source's own setting.")
            Integer topK) {
    }

    public record ShipInput(
            @JsonPropertyDescription("Server name: letters, digits, '.', '_' and '-'. Shipping over an existing "
                    + "name replaces its bundle.") String name,
            @Nullable @JsonPropertyDescription("What the server is for.") String description,
            @Nullable @JsonPropertyDescription("Image tag, default 0.1.0.") String version,
            @Nullable @JsonPropertyDescription("Tool Studio tool names from saip_shipyard_list_shippable.")
            List<String> tools,
            @Nullable @JsonPropertyDescription("Tools of registered MCP servers to proxy through the shipped server.")
            List<ComposedToolInput> composedTools,
            @Nullable @JsonPropertyDescription("Highest risk level a proxied tool may have, default L5.")
            RiskLevel composedToolsMaxRisk,
            @Nullable @JsonPropertyDescription("HTTP, STDIO or BOTH (default).") Transport transport,
            @Nullable @JsonPropertyDescription("Host port of the HTTP endpoint, default 18282.") Integer hostPort,
            @Nullable @JsonPropertyDescription("Chat model the shipped server needs; required by knowledge pipelines "
                    + "with LLM stages and by proxied tools with human approval prompts that need one.")
            ModelInput chatModel,
            @Nullable @JsonPropertyDescription("Embedding model; normally derived from this app, set only to "
                    + "override.") ModelInput embeddingModel,
            @Nullable @JsonPropertyDescription("Knowledge tools over RAG pipelines or documents of this app.")
            List<KnowledgeInput> knowledge) {
    }

    public record ServerData(String name, String version, String description, int toolCount, int proxiedCount,
                             int knowledgeCount, boolean modelFree, String state, String endpoint, String target,
                             boolean outOfDate, boolean managedByConfiguration, String problem) {
    }

    public record ServersData(List<ServerData> servers) {
    }

    public record ShippableTool(String name, String kind, String riskLevel) {
    }

    public record ProxiedChoice(String server, String tool, String alias, boolean hitl) {
    }

    public record KnowledgeSource(String source, String id, String kind, String hint) {
    }

    public record ShippableData(List<ShippableTool> tools, long draftTools, long toolsMissingRequirements,
                                List<ProxiedChoice> proxiedTools, List<KnowledgeSource> knowledgeSources,
                                List<String> chatModels, String embeddingModel, String baseImage) {
    }

    public record EnvRequirement(String name, String purpose, boolean secret) {
    }

    public record ShippedToolData(String name, String kind, String risk, boolean hitl) {
    }

    public record ValidationData(boolean exportable, List<String> blockers, List<String> warnings,
                                 List<EnvRequirement> environment, List<ShippedToolData> tools) {
    }

    public record ShipData(String name, String version, String bundleDir, int toolCount, int proxiedCount,
                           int knowledgeCount, String image, List<EnvRequirement> environment, String nextStep) {
    }

    public record RunData(String name, String target, String endpoint, String state, boolean tokenRequired,
                          String nextStep) {
    }

    public record SnippetData(String client, String content) {
    }

    public record ContainerData(String status, String startedAt, String finishedAt, Integer exitCode,
                                Integer restartCount, String health) {
    }

    public record StatusData(String name, String state, String target, String endpoint, int hostPort,
                             boolean tokenConfigured, List<String> unavailable, List<String> changedSinceShipped,
                             boolean managedByConfiguration, List<SnippetData> connect, ContainerData container) {
    }

    public record LogsData(String name, int lines, int matched, String text) {
    }

    public record TargetData(String name, String where, boolean local, boolean dockerCliFound, boolean dockerUp,
                             String dockerVersion) {
    }

    public record TargetsData(List<TargetData> targets) {
    }

    public record NameData(String name) {
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public List<ManagementTool> tools() {
        return List.of(listServers(), listShippable(), validate(), ship(), run(), stop(), status(), logs(),
                delete(), targets());
    }

    @Override
    public List<SyncResourceSpecification> resources() {
        Resource resource = Resource.builder().uri(GUIDE_URI).name("shipyard-guide")
                .title("Shipping tools, proxied tools and knowledge as an MCP server")
                .description("Procedure for building an MCP server bundle from this app, running it as a "
                        + "container and connecting clients.")
                .mimeType(MARKDOWN).build();
        return List.of(new SyncResourceSpecification(resource, (exchange, request) -> new ReadResourceResult(
                List.of(new TextResourceContents(GUIDE_URI, MARKDOWN, ProxyGuideContribution.read("shipyard.md"))))),
                ManagementJson.statusResource(SHIPYARD_STATUS_URI, "shipyard-status",
                        "Shipped MCP servers and their container state",
                        "The same view as saip_shipyard_list_servers, as JSON, read fresh on every read: bundle "
                                + "contents, container state, endpoint, target and whether a source changed since "
                                + "it was shipped.",
                        () -> new ServersData(this.shipyardService.servers().stream().map(this::serverData)
                                .toList())));
    }

    @Override
    public List<SyncPromptSpecification> prompts() {
        Prompt prompt = new Prompt(PROMPT_NAME, "Ship an MCP server from this app",
                "Step by step procedure for choosing what to ship, exporting the bundle, running the container "
                        + "and handing over the connection details.",
                List.of(new PromptArgument("goal", "Goal", "What the server should offer and where it should "
                        + "run, for example: ship the date tools and the handbook search on port 18290 and run it "
                        + "here.", true)));
        return List.of(new SyncPromptSpecification(prompt, (exchange, request) -> {
            Object goal = request.arguments() == null ? null : request.arguments().get("goal");
            String text = "Goal: " + (goal == null ? "(not given)" : goal) + "\n\n"
                    + "Read the resource " + GUIDE_URI + " first, then follow its procedure with the "
                    + "saip_shipyard_* tools: list what is shippable, validate the request, ship it, run it, "
                    + "check saip_shipyard_status and give the person the connection snippet.";
            return new GetPromptResult("Ship an MCP server from this app",
                    List.of(new PromptMessage(Role.USER, new TextContent(text))));
        }));
    }

    private ManagementTool listServers() {
        return ManagementTool.named("saip_shipyard_list_servers", CATEGORY, NoInput.class).data(ServersData.class)
                .description("List the MCP servers shipped from this app: what each bundle contains, whether its "
                        + "container is running and where, whether its sources changed since it was shipped, and "
                        + "whether it is declared in configuration.")
                .idempotent()
                .handle((input, caller) -> {
                    List<ServerData> servers = this.shipyardService.servers().stream().map(this::serverData).toList();
                    return ManagementResult.ok(servers.size() + " shipped server(s).", new ServersData(servers));
                });
    }

    private ManagementTool listShippable() {
        return ManagementTool.named("saip_shipyard_list_shippable", CATEGORY, NoInput.class)
                .data(ShippableData.class)
                .description("What a shipped server can contain right now: Tool Studio tools that passed their "
                        + "test, tools of registered MCP servers that are exposed and can be proxied, knowledge "
                        + "sources (RAG pipelines and documents) and the models this app can pin.")
                .idempotent()
                .handle((input, caller) -> {
                    FlowDraft draft = this.shipyardService.draft(null);
                    ToolChoices builtIn = this.shipyardService.toolChoices(true);
                    ToolChoices custom = this.shipyardService.toolChoices(false);
                    List<ShippableTool> tools = new ArrayList<>();
                    custom.shippable().forEach(tool -> tools.add(shippable(tool, "custom", custom)));
                    builtIn.shippable().forEach(tool -> tools.add(shippable(tool, "built-in", builtIn)));
                    List<ProxiedChoice> proxied = draft.proxiedChoices().stream()
                            .map(member -> new ProxiedChoice(member.serverId(), member.toolName(),
                                    member.exposedAlias(), member.hitl())).toList();
                    List<KnowledgeSource> knowledge = draft.knowledgeSources().stream()
                            .map(source -> new KnowledgeSource(source.name(), source.sourceId(),
                                    source.pipeline() ? "pipeline" : "document", source.hint())).toList();
                    ShippableData data = new ShippableData(tools, builtIn.drafts() + custom.drafts(),
                            builtIn.missingRequirements() + custom.missingRequirements(), proxied, knowledge,
                            draft.chatModels(), draft.embeddingModel(), this.bundleExportService.image());
                    return ManagementResult.ok(tools.size() + " tool(s), " + proxied.size() + " proxied tool(s), "
                            + knowledge.size() + " knowledge source(s) can be shipped.", data);
                });
    }

    private ManagementTool validate() {
        return ManagementTool.named("saip_shipyard_validate", CATEGORY, ShipInput.class).data(ValidationData.class)
                .description("Dry run of saip_shipyard_ship: resolves names, lists every reason the request would "
                        + "be refused, the warnings, the environment variables the container will need and the "
                        + "tools it would publish. Nothing is written.")
                .idempotent()
                .handle((input, caller) -> {
                    ValidationReport report = this.bundleExportService.validate(requestOf(input));
                    ValidationData data = validationData(report);
                    String message = report.exportable()
                            ? "Ready to ship: " + report.tools().size() + " tool(s)"
                                    + (report.warnings().isEmpty() ? "." : ", " + report.warnings().size()
                                            + " warning(s).")
                            : "Cannot ship: " + String.join("; ", report.blockers());
                    return new ManagementResult(report.exportable(), message, false, false, report.warnings(), data);
                });
    }

    private ManagementTool ship() {
        return ManagementTool.named("saip_shipyard_ship", CATEGORY, ShipInput.class).data(ShipData.class)
                .description("Export an MCP server bundle (Dockerfile, compose file, README, server.json and the "
                        + "frozen tools, upstream connections and knowledge snapshot) under this app's home. "
                        + "Replaces the bundle of a server with the same name; a server declared in configuration "
                        + "cannot be shipped over. Run it afterwards with saip_shipyard_run.")
                .write(input -> {
                    ShipRequest request = requestOf(input);
                    ValidationReport report = this.bundleExportService.validate(request);
                    if (!report.exportable()) {
                        throw new IllegalArgumentException("Cannot ship '" + request.serverName() + "': "
                                + String.join("; ", report.blockers()));
                    }
                    return "Ship '" + request.serverName() + "' v" + request.version() + " with "
                            + contents(request) + (report.warnings().isEmpty() ? ""
                            : " (warnings: " + String.join("; ", report.warnings()) + ")");
                })
                .handle((input, caller) -> {
                    ShipRequest request = requestOf(input);
                    ValidationReport report = this.bundleExportService.validate(request);
                    if (!report.exportable()) {
                        return ManagementResult.failure("Cannot ship '" + request.serverName() + "': "
                                + String.join("; ", report.blockers()), validationData(report));
                    }
                    ExportResult result = this.bundleExportService.exportBundle(request);
                    BundleManifest manifest = result.manifest();
                    ShipData data = new ShipData(manifest.server().name(), manifest.server().version(),
                            result.bundleDir().toString(), manifest.contents().tools().size(),
                            manifest.contents().composedMembers().size(), manifest.contents().ragPipelines().size(),
                            this.bundleExportService.image(), environment(report),
                            "Call saip_shipyard_run to start it, then saip_shipyard_status for the connection "
                                    + "snippet.");
                    return ManagementResult.persisted("Shipped '" + data.name() + "' v" + data.version() + " to "
                            + data.bundleDir() + ".", data).withWarnings(report.warnings());
                });
    }

    private ManagementTool run() {
        return ManagementTool.named("saip_shipyard_run", CATEGORY, RunInput.class).data(RunData.class)
                .description("Build the image and start the container of a shipped server with docker compose on "
                        + "a deploy target. Docker must be reachable there; a remote target requires a bearer "
                        + "token, which the app generates or takes from its run settings and never returns here.")
                .openWorld()
                .approval(ApprovalLevel.DESTRUCTIVE, input -> {
                    BundleEntry entry = this.shipyardService.entry(input.server());
                    DeployTarget target = targetOf(entry, input.target());
                    RunPlan plan = this.runPlanner.plan(entry, target, this.bundleExportService.image(),
                            ollamaEndpoint(input.ollamaEndpoint()));
                    if (!plan.docker().daemonUp()) {
                        throw new IllegalStateException("Docker on " + target.where() + " is not reachable");
                    }
                    return ManagementApproval.destructive("Run '" + entry.name() + "' on " + target.where()
                            + " as " + plan.endpoint() + ". Runs the command: " + plan.commandPreview());
                })
                .handle((input, caller) -> {
                    BundleEntry entry = this.shipyardService.entry(input.server());
                    RunPlanner.RunResult result = this.runPlanner.run(new RunRequest(entry.name(), input.target(),
                            Map.of(), ollamaEndpoint(input.ollamaEndpoint()), null));
                    ServerState state = this.shipyardService.state(entry.name());
                    RunData data = new RunData(entry.name(), result.target().name(),
                            ConnectionSnippetService.mcpUrl(result.target(), state.hostPort()),
                            state.status().state().name(), result.authToken().isPresent(),
                            "Call saip_shipyard_status until state is RUNNING, then hand over the connection "
                                    + "snippet." + (result.authToken().isPresent()
                                    ? " The bearer token is shown to the person in the app, not here." : ""));
                    return ManagementResult.ok("Container of '" + entry.name() + "' is " + data.state() + " on "
                            + result.target().where() + ".", data);
                });
    }

    private ManagementTool stop() {
        return ManagementTool.named("saip_shipyard_stop", CATEGORY, ServerInput.class).data(NameData.class)
                .description("Stop and remove the container of a shipped server (docker compose down). The bundle "
                        + "and the image stay.")
                .openWorld()
                .write(input -> "Stop the container of '" + this.shipyardService.entry(input.server()).name() + "'")
                .handle((input, caller) -> {
                    this.shipyardService.stop(input.server());
                    return ManagementResult.ok("Stopped '" + input.server() + "'.", new NameData(input.server()));
                });
    }

    private ManagementTool status() {
        return ManagementTool.named("saip_shipyard_status", CATEGORY, ServerInput.class).data(StatusData.class)
                .description("Container state of a shipped server, its endpoint and target, dependencies that are "
                        + "unavailable right now, sources that changed since it was shipped, the container's "
                        + "health (docker status, start and exit times, exit code, restart count, health check) "
                        + "and connection snippets for clients. A bearer token is never included; the person "
                        + "reads it in the app.")
                .idempotent()
                .handle((input, caller) -> {
                    ServerState state = this.shipyardService.state(input.server());
                    List<SnippetData> connect = state.live()
                            ? this.snippetService.snippetsFor(state.entry().manifest(), state.target(),
                                    state.hostPort(), Optional.empty()).stream()
                                    .map(snippet -> new SnippetData(snippet.label(), snippet.content())).toList()
                            : List.of();
                    StatusData data = new StatusData(state.entry().name(), state.status().state().name(),
                            state.target().name(), ConnectionSnippetService.mcpUrl(state.target(), state.hostPort()),
                            state.hostPort(), state.authToken().isPresent(),
                            state.status().unavailable().stream()
                                    .map(dependency -> dependency.name() + " (" + dependency.kind() + "): "
                                            + dependency.detail()).toList(),
                            state.changes().stream().map(change -> change.change() + " " + change.source()).toList(),
                            state.managedByConfiguration(), connect, containerData(state));
                    return ManagementResult.ok("'" + data.name() + "' is " + data.state() + " on "
                            + state.target().where() + (state.changes().isEmpty() ? "." : ", out of date."), data);
                });
    }

    private ManagementTool logs() {
        return ManagementTool.named("saip_shipyard_logs", CATEGORY, LogsInput.class).data(LogsData.class)
                .description("Trailing container log lines of a shipped server, for example to see why it did not "
                        + "start. Filter by level or text like saip_monitor_logs_tail; the filters apply to the "
                        + "requested tail, so raise lines to look further back.")
                .idempotent()
                .handle((input, caller) -> {
                    int lines = input.lines() == null ? LOG_LINES_DEFAULT
                            : Math.max(1, Math.min(input.lines(), LOG_LINES_MAX));
                    String text = this.shipyardService.logsTail(input.server(), lines);
                    List<String> all = text.isBlank() ? List.of() : List.of(text.split("\\R"));
                    List<String> matched = all.stream()
                            .filter(line -> matchesLevel(line, input.level()))
                            .filter(line -> matchesText(line, input.text()))
                            .toList();
                    boolean filtered = hasText(input.level()) || hasText(input.text());
                    String message = filtered ? matched.size() + " of " + all.size() + " line(s) match."
                            : text.isBlank() ? "No log output." : "Last " + lines + " line(s).";
                    return ManagementResult.ok(message, new LogsData(input.server(), lines, matched.size(),
                            ManagementSecrets.maskText(String.join("\n", matched))));
                });
    }

    private ManagementTool delete() {
        return ManagementTool.named("saip_shipyard_delete", CATEGORY, ServerInput.class).data(NameData.class)
                .description("Delete the bundle of a shipped server from this app's home. Its container must be "
                        + "stopped first; images already built or pushed are not touched. Servers declared in "
                        + "configuration cannot be deleted here.")
                .destructive(input -> {
                    this.shipyardService.requireMutable(input.server(), "deleting");
                    return "Delete the bundle of '" + input.server() + "' permanently";
                })
                .handle((input, caller) -> {
                    this.shipyardService.delete(input.server());
                    return ManagementResult.persisted("Deleted '" + input.server() + "'.",
                            new NameData(input.server()));
                });
    }

    private ManagementTool targets() {
        return ManagementTool.named("saip_shipyard_targets", CATEGORY, NoInput.class).data(TargetsData.class)
                .description("Deploy targets a shipped server can run on: the local Docker plus every configured or "
                        + "added remote engine, each with its Docker reachability. Targets are added by people in "
                        + "the app or in configuration.")
                .idempotent()
                .handle((input, caller) -> {
                    List<TargetData> targets = this.deployTargetService.targets().stream().map(target -> {
                        DockerAvailability docker = this.deployTargetService.check(target);
                        return new TargetData(target.name(), target.where(), target.isLocal(), docker.cliFound(),
                                docker.daemonUp(), docker.version());
                    }).toList();
                    return ManagementResult.ok(targets.size() + " target(s).", new TargetsData(targets));
                });
    }

    ShipRequest requestOf(ShipInput input) {
        List<ComposedTool> composed = input.composedTools() == null ? List.of() : input.composedTools().stream()
                .map(tool -> new ComposedTool(tool.server(), tool.tool(), tool.alias(), tool.description(),
                        Boolean.TRUE.equals(tool.hitl()))).toList();
        List<ShipyardKnowledge> knowledge = input.knowledge() == null ? List.of() : input.knowledge().stream()
                .map(entry -> new ShipyardKnowledge(entry.source(), entry.name(), entry.description(),
                        entry.topK())).toList();
        ShipyardServer server = new ShipyardServer(input.name(), input.description(), input.version(),
                input.tools(), composed, input.composedToolsMaxRisk(), input.transport(), input.hostPort(),
                modelOf(input.chatModel()), modelOf(input.embeddingModel()), null, knowledge, false);
        return this.bundleExportService.normalize(this.configuredBundleExporter.toRequest(server));
    }

    private static ShipyardModel modelOf(ModelInput input) {
        return input == null ? null : new ShipyardModel(input.provider(), input.model());
    }

    private DeployTarget targetOf(BundleEntry entry, String targetName) {
        if (targetName == null || targetName.isBlank()) return this.runPlanner.currentTarget(entry.name());
        return this.deployTargetService.find(targetName)
                .orElseThrow(() -> new IllegalArgumentException("No target named '" + targetName + "'"));
    }

    private String ollamaEndpoint(String requested) {
        return requested == null || requested.isBlank() ? this.runPlanner.defaultOllamaEndpoint() : requested;
    }

    private ContainerData containerData(ServerState state) {
        if (state.status().state() == ContainerState.ABSENT) return null;
        return this.shipyardService.containerDetails(state.entry().name())
                .map(details -> new ContainerData(details.status(), details.startedAt(), details.finishedAt(),
                        details.exitCode(), details.restartCount(), details.health()))
                .orElse(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean matchesLevel(String line, String level) {
        if (!hasText(level)) return true;
        String wanted = level.strip().toUpperCase(Locale.ROOT);
        LogQueryService.LogLine parsed = LogQueryService.parse(line);
        if (parsed != null && hasText(parsed.level())) return wanted.equals(parsed.level());
        return line.toUpperCase(Locale.ROOT).contains(" " + wanted + " ");
    }

    private static boolean matchesText(String line, String text) {
        return !hasText(text) || line.toLowerCase(Locale.ROOT).contains(text.strip().toLowerCase(Locale.ROOT));
    }

    private ServerData serverData(ServerSummary summary) {
        if (summary.entry().isEmpty()) {
            return new ServerData(summary.name(), null, null, 0, 0, 0, true, "NOT_EXPORTED", null, null, false,
                    true, summary.problem());
        }
        ServerState state = this.shipyardService.state(summary.name());
        BundleManifest manifest = state.entry().manifest();
        return new ServerData(summary.name(), manifest.server().version(), manifest.server().description(),
                manifest.contents().tools().size(), manifest.contents().composedMembers().size(),
                manifest.contents().ragPipelines().size(), manifest.requires().models().modelFree(),
                state.status().state().name(), ConnectionSnippetService.mcpUrl(state.target(), state.hostPort()),
                state.target().name(), summary.outOfDate(), state.managedByConfiguration(), summary.problem());
    }

    private static ShippableTool shippable(ToolSpec tool, String kind, ToolChoices choices) {
        return new ShippableTool(tool.name(), kind, choices.riskLevels().get(tool.toolId()));
    }

    private static ValidationData validationData(ValidationReport report) {
        return new ValidationData(report.exportable(), report.blockers(), report.warnings(), environment(report),
                report.tools().stream().map(tool -> new ShippedToolData(tool.name(), tool.kind().name(),
                        tool.riskLabel(), tool.hitl())).toList());
    }

    private static List<EnvRequirement> environment(ValidationReport report) {
        return report.envRequirements().stream()
                .map(env -> new EnvRequirement(env.name(), env.purpose(), env.secret())).toList();
    }

    private static String contents(ShipRequest request) {
        return request.toolIds().size() + " tool(s), " + request.composedTools().size() + " proxied tool(s), "
                + request.knowledge().size() + " knowledge tool(s)";
    }

}
