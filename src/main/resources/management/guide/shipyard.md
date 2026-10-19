# Shipping tools, proxied tools and knowledge as an MCP server

This app can package what it holds into a standalone MCP server: Tool Studio tools, tools of registered external MCP servers (proxied through the bundle) and knowledge tools that search RAG pipelines or documents of this app. The result is a bundle folder with a Dockerfile, a compose file, a README, a server.json and a frozen copy of everything the server needs. The app can build and run it as a container on a Docker engine and tells clients how to connect.

## Procedure

1. `saip_shipyard_list_servers`: see what is already shipped, whether each container runs and whether a bundle is out of date (its sources changed after shipping).
2. `saip_shipyard_list_shippable`: see what can go into a server right now. Tool Studio tools must have passed their test (drafts are listed only as a count), proxied tools must already be exposed on this app's MCP server (use the `saip_expose_*` tools and `saip://guide/proxy` first), knowledge sources are the RAG pipelines and documents of this app.
3. `saip_shipyard_validate`: dry run with the exact request you plan. It resolves names, returns every blocker, the warnings, the environment variables the container will need and the tools the server would publish. Fix blockers before shipping.
4. `saip_shipyard_ship`: write the bundle. A person approves it with a summary of what goes in. Shipping over an existing name replaces that bundle; a server declared in `spring.ai.playground.shipyard.servers` cannot be shipped over.
5. `saip_shipyard_run`: build the image and start the container on a target (`saip_shipyard_targets` lists them). A person approves the docker command. Docker must be reachable on the target.
6. `saip_shipyard_status`: repeat until `state` is `RUNNING`. It returns the endpoint, dependencies that are unavailable (an upstream server, a model) and ready-to-paste connection snippets for Claude Code, Claude Desktop (stdio) and generic JSON. `saip_shipyard_logs` shows the container output when it does not start.
7. Hand the person the snippet. If the server needs a bearer token (remote targets always do), the person reads the token in the app; it is never returned by these tools.

## Request fields

- `name`: letters, digits, `.`, `_` and `-`. `version` is the image tag, default `0.1.0`.
- `tools`: Tool Studio tool names. Built-in tools that need an environment variable the container will not have are listed with that variable under `environment`.
- `composedTools`: `{server, tool, alias, description, hitl}` per proxied tool. Only Streamable HTTP upstreams without OAuth can ship; upstreams on this machine are rewritten so the container reaches them through `host.docker.internal`. `hitl: true` asks the calling person before each call. `composedToolsMaxRisk` caps the risk level (default L5).
- `knowledge`: `{source, name, description, topK}` per knowledge tool. `source` is a pipeline name or id, or a document title or id. The tool name defaults to `search_<slug>`; names starting with `saip_` are refused. The embedding model of this app is pinned into the bundle. A pipeline with LLM stages before retrieval (rewrite, compression, translation, multi-query) also needs `chatModel`.
- `chatModel` and `embeddingModel`: `{provider, model}`. Ollama models are reached through the Ollama of the machine that runs the container (`host.docker.internal:11434`), OpenAI models need the API key as an environment variable of the container; both appear under `environment` in the validation result.
- `transport`: `HTTP`, `STDIO` or `BOTH` (default). `hostPort`: the published HTTP port, default 18282; each running server needs its own.

## Rules that can refuse a request

- Unknown tool, server or knowledge source names, duplicate tool names inside the bundle, reserved names.
- Draft tools, tools whose required environment variables are missing, upstreams that use stdio, SSE or OAuth.
- A knowledge source whose documents were deleted, or a pipeline that needs a chat model when none is given.
- A server name that is managed by configuration, and deleting or rebuilding a server while its container runs.

## What the person does

Docker on the target, environment variables and bearer tokens are the person's side. When a result asks for one of these, say exactly what is missing and stop. Everything else, from choosing the contents to handing over the snippet, is yours.
