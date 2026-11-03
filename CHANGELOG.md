# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Work toward **1.0.0**, the first stable release, continues after 0.2.0-M14. Another milestone may ship before it.

### Planned

- **Local tool contributions** - one kind of external contribution will open: local tools built in Tool Studio by a person, an AI agent, or both. A tool must earn its Local Pass in the app first; CI then re-runs the Local Pass, an LLM reviews the PR, and only then does the maintainer review and merge it. Accepted tools ship with the next release as a Community bundle. The acceptance rules, the CI and the review pipeline are being built now, so this is not open yet; the plan and the draft rules are in [CONTRIBUTING.md](CONTRIBUTING.md#planned-local-tool-contributions).
- **MCP notifications** - support for the notifications the newest MCP spec still carries.
- **Signed Windows installers** - the installer is signed with the SignPath Foundation release certificate once it is issued; until then it ships unsigned, and the published checksums and provenance attestations verify it.
- **Microsoft Store listing** - the desktop app is listed in the Microsoft Store starting with 1.0.0.

## [0.2.0-M14]

### Added

- **Shipyard: ship what passed as a standalone MCP server** - the new **Shipyard** tab composes Local Pass tools, built-in tools, proxied external tools and RAG knowledge into one table, exactly as MCP clients will see them, and writes a self-contained bundle under `<home>/mcp/bundles/<name>/`: a `saip-bundle.json` manifest with content hashes, the tool subset it needs, `Dockerfile`, `compose.yaml`, `README.md` and a starter `server.json` for the MCP Registry. **Ship MCP Server** in the Tool Studio and MCP Server headers opens the flow with the open or exposed tools preselected, and the preview lists what the server needs at run time (models, upstream servers, environment variables). Two tools with the same name block shipping (`feat(shipyard)`).
- **Run shipped servers on local or remote Docker** - one image per server (`saip-mcp/<name>:<version>`) serves Streamable HTTP and stdio. **Run** picks a target (the built-in `local`, an `ssh://` or `tcp://` Docker host, or a remote docker context), checks that its Docker and the Ollama model answer, and shows what changes there. The server page carries status, logs, Stop, Test connection, Add to this app, an **Out of date** banner with **Rebuild** when a shipped tool or upstream connection changed, and a Connect panel with one-click deep links and copy-ready snippets for common MCP clients. Inside the container an unreachable upstream or model endpoint leaves only the tools that need it unavailable and is retried every 30 seconds (`feat(shipyard)`).
- **Knowledge servers** - **+ Knowledge** ships a RAG pipeline or document as a retrieval-only `search_<source>` tool over a frozen snapshot of its chunks and vectors, with an editable name, description and default `top_k`, and **Suggest** proposes one tool per scoped pipeline plus the all-documents pipeline for the rest. **Ship** in the Pipeline Studio summary bar starts the flow from that pipeline and carries the last test question into **Try**; a running server's Tools tab has a **Try** per tool through the container; the **Out of date** banner sums up knowledge changes per tool ("1 re-embedded, 1 deleted, 1 added") and **Review changes** lists each document's status (`feat(shipyard)`).
- **Shipyard in configuration** - `spring.ai.playground.shipyard.servers` and `spring.ai.playground.shipyard.targets` declare servers (tools, composed tools, knowledge, models, transport, port, target) and remote Docker engines in YAML or environment variables. The app writes those bundles at startup, marks them **Managed by configuration**, lists a server that could not be exported with the reason, and `auto-run` starts one right after its bundle is written. `SAIP_MCP_AUTH_TOKEN` and `SAIP_OLLAMA_BASE_URL` feed every server started from the app, and `docker-executable`, `image-repository` and `image-tag` (`v<app version>` by default) are configurable (`feat(shipyard)`).
- **Headless mode** - `SPRING_AI_PLAYGROUND_UI_ENABLED=false` answers `404` on every path other than `/mcp`, `/sse`, `/actuator/**` and the OAuth callbacks, which turns the app, or a shipped server, into a headless MCP server; shipped servers keep their screens by default (`feat(shipyard)`).
- **Management MCP tools: let an external AI operate the app** - with a bearer token on, the built-in server also offers 51 `saip_<area>_<verb>` tools in seven categories (`tools`, `mcp`, `expose`, `monitor`, `shipyard`, `knowledge`, `chat`), and 56 in eight once the opt-in `mcp-register` category is on, through which ChatGPT (Codex), Claude Code, Claude Desktop, Cursor or any client with elicitation does what the screens do. The tools call the same services the screens call, so what the AI sets up is what you see in Tool Studio, the Composed Tools drawer, the Vector Database and Shipyard; there is no REST API. `spring.ai.playground.management.*` sets the categories (`mcp-register` is off unless listed), exclusions, the approval mode and the audit capacity, and **MCP Server -> gear -> AI management** shows the state (`feat(shipyard)`, `feat(management)`).
- **Guides served as MCP resources** - the app serves its own procedures, versioned with the build, as `saip://guide/tool-authoring` (with `/runtime`, `/sandbox`, `/testing`, `/mistakes`, `/examples` and `saip://examples/{name}`), `saip://guide/proxy`, `saip://guide/risk-levels`, `saip://guide/knowledge` and `saip://guide/shipyard`, started by the prompts `saip_create_tool`, `saip_proxy_server`, `saip_knowledge_server` and `saip_shipyard_server`, so an AI learns the rules from the app itself (`feat(shipyard)`, `feat(management)`).
- **AI-authored tools under the Local Pass rule** - `saip_tools_*` lists, reads, previews the risk of a sandbox setting, saves drafts, tests them with hints for the failure, publishes only after a passing re-test, deletes, and shows or changes exposure. A published tool is replaced through a new draft rather than edited in place (`feat(management)`).
- **AI-driven proxying, knowledge and shipping** - `saip_mcp_*` and `saip_expose_*` list registered servers with per-tool risk, dry-run and apply multi-server selections with aliases and a per-tool or per-risk HITL policy, applied or refused as a whole; `saip_knowledge_*` ingests documents (with MCP progress notifications per stage when the call carries a `progressToken`), applies pipelines, searches exactly as a shipped `search_` tool would and suggests a knowledge list; `saip_shipyard_*` validates, ships, runs, stops, deletes and inspects shipped servers (`feat(shipyard)`, `feat(management)`).
- **Monitoring over MCP** - `saip_monitor_*` returns what the Observability screens show (overview, tool, proxied and HITL calls, risk events, traces and their spans, system metrics, the app log tail, the management audit log) with masked secrets. `saip_shipyard_status` adds what Docker reports about a container, `saip_shipyard_logs` filters its log tail by level and text, and `saip://status/servers`, `saip://status/exposure` and `saip://status/shipyard` mirror the read-only views as JSON recomputed on every read (`feat(management)`).
- **The app's chat as a sub-agent** - `saip_chat_ask` runs one Agentic Chat turn without the screen and returns the answer, as a real conversation that appears in the history with its process details and traces; `saip_chat_list_conversations` lists conversations to continue. A tool the turn calls that needs approval asks the person at the AI client through elicitation, and the management tools are never available to the turn (`feat(management)`).
- **Documents and RAG pipelines in configuration** - `spring.ai.playground.rag.documents` ingests declared documents (a `path`, `url` or inline `text`, with the same extract, split and enrich options as the upload dialog) and re-ingests one only when its source bytes or options change; `spring.ai.playground.rag.pipelines` creates or updates pipelines by name. Both show **Managed by configuration** and are read-only in the UI (`feat(rag)`).
- **One shared approval wait with a visible countdown** - `spring.ai.playground.hitl.approval-wait-seconds` (180 s by default, capped at 240) is the single wait for the chat approval, file-upload and image dialogs, an Inspector call of a HITL tool, the Inspector's pending Sampling and Elicitation cards, and the built-in server's approval gate. The chat dialog shows `m:ss left` beside **Decline**, and a running Inspector tool card shows the time left and the server's approval request itself with **Accept** / **Decline** / **Review**. Expiry is an explicit outcome: the model is told `Tool 'x' was not run: no approval within 180 s` and the Safety dashboard counts a `timeout` (`feat(hitl)`).
- **External MCP clients see the wait** - the approval question ends with `Answer within 180 s, or the call is refused.`, a client that sent a `progressToken` receives `Waiting for approval: N s left` progress every 10 seconds, and an answer that arrives after the wait is refused as a timeout (`feat(hitl)`).
- **Reconnect and explicit re-review of changed proxied tools** - a saved server gets a **Reconnect** button that restarts its connection and re-syncs the tools it contributes; a composed tool whose description or schema changed shows **Definition changed** and stays withheld until **Accept new definition** is ticked (`fix(mcp)`).
- **Generating indicator in chat** - three animated dots show in the reply until the first token arrives (`feat(chat)`).
- **Pipeline run progress in Pipeline Studio** - while a run waits, the steps list their settings first, then show each stage result as it arrives with elapsed seconds, and fold behind the final answer when it is done (`feat(vectorstore)`).

### Changed

- **Screens delegate to services** - logic that lived in the Vaadin views (chat turns and recording, tool resolution, provider readiness, release checks, MCP connection specs, tool exposure, ETL and pipeline execution) moves into services, so the screens, the management tools and configuration run the same operations (`refactor(webui)`).
- **The built-in server's request timeout follows the approval wait** - `spring.ai.mcp.server.request-timeout` is raised at startup to the approval wait plus a 30-second margin (`210s` by default instead of `150s`), and the loopback client for Inspector HITL calls is derived the same way, so a pending approval no longer outlives its transport (`feat(hitl)`).
- **Declared composed tools win over the drawer** - a non-empty `spring.ai.playground.mcp-server.composed-tools` is applied on every start and makes the drawer's composed section read-only (`feat(shipyard)`).
- **Proxied tools carry one composed risk level everywhere** - the drawer chip, the served tool, the Inspector card and the chat approval dialog use the same server-plus-tool level, and the HITL credit never moves a tool below L1 or turns an `L0 - Verified` built-in tool into L1 (`fix(mcp)`, `fix(observability)`).

### Removed

- **`spring.ai.playground.chat.agent-loop.approval-timeout-seconds` and `dialog-timeout-seconds`** - the 120 s approval and 180 s dialog timeouts are replaced by the shared `spring.ai.playground.hitl.approval-wait-seconds`; leftover values are no longer read (`feat(hitl)`).

### Fixed

- **MCP clients cannot auto-accept an approval** - the approval elicitation now carries one required field, `approve`, rendered as a pre-ticked **Run this call** checkbox. Only **Accept** with the box still ticked runs the tool; an unticked box, **Deny**, cancel, timeout, or an `ACCEPT` without the field refuses it, so a client that fills empty forms by itself, such as Codex with its approvals switched off, no longer passes the gate (`fix(hitl)`).
- **Mark all selected tools for human review** - the header checkbox now ticks every selected row, reaches rows selected later, turns off when one row is unticked, and reflects the saved state when the drawer opens; raising the risk cap again re-enables the rows it had disabled (`fix(mcp)`).
- **MCP Inspector and server form** - the client declares the `roots`, `sampling` and `elicitation` capabilities, a duplicate root URI is refused, manual Sampling answers carry the model the protocol requires, the app-managed built-in server form can no longer be saved, applying the built-in server drawer reloads the Inspector's tool list, saving a server with a missing environment variable names the variable, persisted servers start before exposed tools are synced, and Home counts only connected external servers as connected (`fix(mcp)`).
- **Tool Studio names and exposure** - a custom tool can no longer take the name of a bundled default tool or push the genuine one off the server, a tool taken off the server stays off when it is loaded or edited again, turning approval on for a published tool re-registers it behind the gate, applying a preset lifts earlier built-in exclusions but keeps custom ones, and **Format** waits for the Prettier scripts instead of failing with `prettier is not defined` (`fix(tool-studio)`).
- **Observability counts** - vector operations are recorded in a memory-only operation log that feeds the Vector Database dashboard (with the last 20 query texts under **Recent searches**), tool spans join the chat trace of their turn, calls from external MCP clients and calls refused at the approval gate are recorded as tool spans (**Callers** and **Denied calls**), a proxied call is counted once, and unrecorded token counts render as a dash instead of zero (`fix(observability)`).
- **Chat history and attachments** - a conversation that only has an attachment appears in the history and can be reopened, untitled conversations show a placeholder label, and attaching the same file twice is refused as `already attached` instead of adding a second chip (`fix(chat)`).
- **Workspace layout and re-upload** - long sidebar titles shrink with an ellipsis so the header delete buttons stay inside the sidebar, a closed settings drawer is inert so focus cannot scroll it into view, and uploading the same file again after cancelling or removing it no longer fails chunking with a duplicate key (`fix(webui)`).
- **Library messages stay English** - the JVM default locale is pinned to English at startup, so messages from libraries do not follow a non-English OS locale (`fix(config)`).

### Documentation

- **Shipyard, management tools and configuration** - the MCP Server page gains the Shipyard section, a new **Management MCP Tools** page covers the areas, guides, approval levels and client setup (Codex, Claude Code, Claude Desktop, Cursor), and the configuration reference gains Management tools, Shipyard, RAG declarations and the approval wait; the site is matched to M14 behaviour and its captures refreshed (`docs`).
- **AI Guides, Templates and Samples** - a new page shows what the built-in server teaches an AI: every guide word for word, included from the files the app serves, the sandbox guide as filled in with this app's limits, the four start prompts and the example tools, with Inspector captures and a curl script to read them from your own app; the home page, the README and the management tools, architecture, tutorial and feature pages link it (`docs`).
- **Tutorials 18-22** - **Let an AI Author a Tool**, **Ship a Knowledge Server and Hand It to ChatGPT**, **Let an AI Proxy External MCP Servers Through a Safety Net** (with an architecture diagram of one AI connection, tool composition, HITL approval and monitoring), **Let an AI Build and Ship a Knowledge Server** and **Let an AI Use the Chat as a Sub-agent**; tutorial 16 now ships its tool with Shipyard (`docs`).
- **Shipyard Architecture and AI Operations Architecture** - the bundle contract, the container's start checks and **Out of date**; the management tools' registration, approval gate, guides and audit trail (`docs`).
- **Related tutorial videos** - 56 feature and tutorial pages show the matching episodes as thumbnail cards under the page title (`docs`).
- **Home page and README for 0.2.0-M14** - the lead demo is the new episode, "An AI Ships and Runs an MCP Server"; the first feature item says an AI connected over MCP writes tools, proxies external servers, builds RAG knowledge, monitors the app and ships servers, inside one safety net; the home page folds long install and reference detail into collapsible blocks and gains an entry for the author's book; a new `CONTRIBUTING.md` and a README section announce planned local tool contributions (not open yet) (`docs`).

### Security

- **Management tools exist only behind a bearer token** - without the built-in server's token they are not registered at all, they are never offered inside a shipped bundle, in stdio mode, or to the app's own Agentic Chat, and every call, refused and declined ones included, is audited on **Observability -> Safety -> Management tool calls** with masked arguments.
- **Human approval in the AI's own client** - destructive management actions ask the client's user through MCP elicitation, naming the client and every change, including the tools that will have no human approval; a client without elicitation is refused, actions rated L4 or higher always ask, and only an explicit ticked **Run this call** answered within the wait runs a call; a late answer is a timeout.
- **No secrets through the AI** - headers and values that look like secrets must be `${ENV_NAME}` references, stdio servers can be registered only from the catalog with the exact command in the question, this app's own `/mcp` cannot be registered as an upstream, and bearer tokens never pass through the Shipyard tools.
- **Bundles fail closed** - the manifest's file hashes are checked on every start, a missing required variable or a different model provider stops the server, `/mcp` answers `503` until every listed tool is registered, OAuth upstreams and literal secrets in upstream headers or URL parameters block shipping, and a server on a remote target must require a bearer token.
- **Path ingestion is confined** - documents ingested by path (declared documents and the knowledge tools) must sit inside `spring.ai.playground.rag.ingest-roots` after symbolic links are resolved.
- **Desktop app dependencies patched** - the Electron lock file is refreshed to pull patched versions of vulnerable dependencies.

### Build / Tooling

- **Desktop installers build on Node 22** - the release and SignPath test workflows move from Node 20 to Node 22 (`fix(electron)`).
- **Opt-in Docker live test** - `ShipyardDockerLiveTest` builds and runs a bundle on the local engine and on a Testcontainers Docker-in-Docker engine registered as a remote target, behind `RUN_DOCKER_IT=true` (`feat(shipyard)`).
- **Version bump to 0.2.0-M14** (`build(pom)`).

## [0.2.0-M13]

### Added

- **Offline ETL and Modular RAG Pipeline Studio in Vector Database** - RAG is split the way Spring AI splits it. **New Document & ETL Pipeline** auto-recommends a reader from the file (Tika, text, JSON, Markdown, HTML, PDF by page or paragraph), exposes the `TokenTextSplitter` settings, optionally enriches chunks with LLM keyword and summary metadata, and previews every chunk with a per-chunk opt-out before **Embed and insert**. **New RAG Pipeline** is a four-tab wizard over the framework's own modules (pre-retrieval, retrieval, post-retrieval, generation), saved as JSON and runnable in place with Documents / Final prompt / Trace sections. Deleting a document warns about pipelines that scope it (`feat(rag)`).
- **One RAG source selector in Agentic Chat** - the chat picks a document (`retrieval only`) or a saved pipeline (`stages: N - +M LLM calls`); both run through the same `RagPipelineExecutor` the studio tests against, and the RAG DOCUMENTS panel prints every stage, query, timing, and score. Conversations saved by M11 / M12 load into the new single source (`feat(rag)`).
- **Document attachments in Agentic Chat** - drop a `pdf`, `txt`, `md`, `html`, `docx`, or `pptx` file (20MB, five per conversation) on the prompt. Up to 4,000 tokens the text is injected in full every turn; above that the file is chunked, embedded, and summarized into an always-present overview with one scoped excerpt search per turn, so "summarize this" and pinpoint questions both work. An attachment stays private to its conversation until the chip's database icon registers it in the Vector Database (`feat(chat)`).
- **Bearer token for the built-in MCP server** - **MCP Server -> gear -> Authentication** switches between **None** and **Bearer token** (reveal, Generate, Copy), applies without a restart, and stores the token encrypted; `spring.ai.playground.mcp-server.auth-token` does the same declaratively and wins over the UI. `/mcp` then answers `401` with `WWW-Authenticate: Bearer` to a missing or wrong token, while the web UI and the app's own tools keep working (`feat(mcp)`).
- **Model-free boot** - `spring.ai.model.chat=none` and `spring.ai.model.embedding=none` start the app with no model provider: Tool Studio and the built-in MCP server keep working, and a chat send fails fast with a clear message (`feat(config)`, `fix(chat)`).
- **Canonicalization scheme on the tool hash ledger** - the ledger records which scheme produced each tool fingerprint and re-baselines on a scheme change instead of raising a false tamper signal (`feat(mcp)`).
- **HITL decisions split by gate** - `mcp.hitl.decision` carries `side` (`chat` / `server`), and the Safety chart breaks outcomes out per gate (`feat(observability)`).
- **KOROAD accident hotspots tool** - `getTrafficAccidentHotspots` joins the Korea defaults (116 built-in tools), with tutorial 16 walking a public API into a published MCP tool (`feat(tools)`).
- **`qwen3.8:27b` in the default model lists** - Ollama and MLX profiles, and the desktop launcher templates (`feat(chat)`).

### Changed

- **Spring AI 2.0.1, Vaadin 25.2.8, and Jackson 3** - the remaining Jackson 2 usages move to Jackson 3; JSON persisted by earlier milestones still loads. Vaadin 25.2 brings the Vite 8 / Rolldown frontend toolchain: the JSON editor no longer imports the Ace worker script into the page bundle (it is served as its own file), and `tslib` is declared for the ECharts bundle (`build(pom)`, `refactor`).
- **Dynamic tool search matches name tokens** - in `HYBRID` mode tools whose name tokens appear in the query rank ahead of the vector hits, so "add two numbers" finds `get-sum` on a connected MCP server (`fix(chat)`).
- **Map cards use keyless OpenStreetMap tiles** - CARTO started watermarking un-keyed requests; the dark theme is a CSS filter over the OSM tiles (`fix(chat)`).
- **MCP servers stay selectable in dynamic tool mode**, and labels across Chat, Home, MCP, and Tool Studio use plain ASCII punctuation (`fix(chat,ui)`).

### Removed

- **`spring.ai.playground.chat.tool-search.default-on`** - the property was documented but never read, so it is gone from the options and the configuration reference; new chats follow `chat.default-preset` as before, and a leftover value is ignored (`refactor`).

### Fixed

- **OpenAI embeddings index the document text only** - metadata no longer leaks into the embedding input (`fix(rag)`).
- **HITL timeout is reported as a timeout** - an expired approval dialog is recorded as `timeout` and tells the model the tool was not run, instead of counting as `ask-failed` (`fix(chat)`).
- **A `?conv=` deep link wins over the restored history selection** (`fix(chat)`).
- **Disabled selectors keep their placeholder** - "No RAG sources" and "No MCP servers connected" render instead of a blank box (`fix(chat)`).

### Documentation

- **RAG section rebuilt** - Overview, Offline: Indexing, Pipeline Studio, Runtime: RAG in Chat, and Chat Attachments pages, each mapping a control to its Spring AI class; the architecture pages gain the attachment and RAG flows (`docs`).
- **Tutorials 1-16 and the feature pages re-verified in the app** - drifted claims corrected, 70 captures refreshed, tutorial 16 added to the nav, downloads page reworked; new **tutorial 17 - Attach a Document and Ask** walks the attachment flow from drop to registered document (`docs`).
- **Configuration and connections** - model-free boot, the token-match tool search, and a **Require a bearer token** guide (in-app setup, per-launch-mode configuration, client header examples); README lists the M13 features (`docs`).

### Security

- **Optional bearer token on `/mcp`** - stored encrypted and bound to the host and user, never logged, compared in constant time, and editable only from a browser on the machine that runs the app; a stored token that cannot be decrypted keeps `/mcp` locked instead of falling open.
- **Attachments are untrusted input** - their text enters the prompt fenced as data with an instruction not to follow it.

### Build / Tooling

- **Windows installer signing via SignPath** - the release workflow submits the installer to SignPath and refreshes the auto-updater metadata; signing is gated on the release certificate (`ci`).
- **Code of conduct and code signing policy** added to the repository (`chore(repo)`).
- **Network smoke test is opt-in** behind `RUN_NETWORK_SMOKE=true` (`test`).
- **Version bump to 0.2.0-M13** (`build(pom)`).

## [0.2.0-M12]

### Added

- **Desktop app stays resident in the tray** - closing the main window now hides it instead of stopping the server, with a one-time "Still running in the tray" notification that reopens the window when clicked. A **Recent Activity** tray menu lists the pages you last had open (seven in the menu, twenty kept in `<userData>/configs/recent-activity.json`) and reopens the app directly on one of them, keeping the `?conv=` query so a chat entry lands back on that conversation. Agentic Chat now writes the active conversation into the browser URL and the page title, so a reopened or restored window returns to the same conversation instead of a blank chat (`feat(electron)`).
- **Risk level shown in HITL approval dialogs, with friction that scales by level** - every human approval prompt now carries the tool's risk chip and the rationale for that level, plus a level-specific caution line. L4 renders the approve button in the error theme; L5 keeps approval disabled behind an "I reviewed the arguments and accept the risk" checkbox. Applies to both gates, the chat approval dialog and the MCP elicitation path used by external clients (`feat(hitl)`).
- **Daily MCP inventory and per-model usage telemetry** - two aggregate events join the anonymous usage set: `mcp_server_active` (at most once a day per configured server: transport, catalog id, oauth, connected) and `model_usage` (at most once a day per model used in the last 24 hours: calls, tokens, errors, p95 latency). `tool_called` gains `catalog_id`, `usage_snapshot` gains `ram_gb`, and the Google Tag Manager `js_error` event is now documented in the telemetry table. Still content-free, still covered by the single `SPRING_AI_PLAYGROUND_TELEMETRY_ENABLED=false` switch (`feat(analytics)`).
- **KOSIS National Statistics in the MCP catalog** - a vendor-official pilot entry for Korea's national statistics service, operated by the National Data Office, pre-filled with the `https://kosismcp2026.vercel.app` endpoint and its separate `/api/mcp` path, marked PREVIEW, and shipped with per-tool risk levels and annotations for its ten read-only tools (`feat(mcp)`).
- **Chart sorting and chart visual polish** - `renderChart` and `renderWindRose` take an optional `sort` (`asc` / `desc`, plus `none` on the rose) so nominal categories can be ordered by value while time and ordinal categories keep their given order. A single-series rose now defaults to one color per slice sorted ascending (nightingale style), and labels with an inherent order such as compass points, hours, months and weekdays keep theirs. Every chart type picks up a shared eight-color palette, rounded bar caps, gradient area fills, surface-colored item borders, and themed axes, legends and tooltips (`feat(chat,mcp)`).
- **Ollama models stay loaded for thirty minutes** - `spring.ai.ollama.chat.keep-alive` and `spring.ai.ollama.embedding.keep-alive` default to `30m` and ride on every request, so returning to a conversation after a pause no longer waits out a model reload. The request value wins over the Ollama server's own `OLLAMA_KEEP_ALIVE` default, a single conversation can override it through the settings drawer's provider-options JSON (`{"keep_alive": "5m"}`), and a `BeanPostProcessor` retypes the plain embedding options built by the vector store and the tool index so the configured defaults reach embedding calls too - a workaround to delete once Spring AI merges defaults into plain-typed requests (`feat(chat)`).

### Changed

- **Chat transcript layout** - user messages are right-aligned and message avatars and name labels are removed, so the transcript reads as a conversation rather than a labelled log (`feat(chat)`).
- **Home environment status** - the environment-variable readiness chip becomes a status pill beside the provider chips, expanding into a per-variable detail list that names the tools each variable unblocks, and the "Open all 14 dashboards" link is a real router link instead of a click-handler anchor (`fix(home)`).

### Removed

- **Custom environment-variable aliases** - the sixteen ad-hoc env names the yaml used to declare (`OBS_RING_CAPACITY`, `OBS_RETAIN_DAYS`, `OBS_PERSIST`, `OBS_MAX_SPANS`, `TOOL_STUDIO_FS_BASE`, `SPRING_AI_PLAYGROUND_MCP_NAME` / `_MCP_DESCRIPTION` / `_MCP_EXPOSURE_MODE`, `SPRING_AI_PLAYGROUND_ACTUATOR_INCLUDE`, `SPRING_AI_PLAYGROUND_TRACE_SAMPLE`, and the six `SPRING_AI_*_OBSERVE_*` logging toggles) are gone. **Breaking**: every knob now binds only through Spring Boot's standard relaxed binding - `spring.ai.playground.observability.persist` becomes `SPRING_AI_PLAYGROUND_OBSERVABILITY_PERSIST`, `...tool-studio.fs.base-path` becomes `SPRING_AI_PLAYGROUND_TOOL_STUDIO_FS_BASE_PATH`, and so on. `SERVER_PORT` keeps working because it is the relaxed-binding name itself, and `OPENAI_API_KEY` stays because it is the ecosystem-standard name the desktop launcher and the OpenAI tools read. A guard test fails the build if a new alias reaches the shipped yaml, and the docs list only the derived names (`refactor(config)`).

### Fixed

- **Composer stays locked while a response streams, and relocks on reentry** - the send controls no longer accept a second message mid-stream, and returning to a conversation that is still streaming restores the locked state instead of showing an idle composer. A new `ChatStreamRegistry` owns the per-conversation stream and its finish listeners so the lock is released exactly once, and the history commit in `ChatService` moves into a `finally` block so a failing listener can no longer leave a conversation locked for the rest of the session (`fix(chat)`).
- **Exposed built-in tool lists refresh when the popover opens** - opening the tool popover re-reads what the built-in MCP server currently exposes instead of showing the set captured at attach time, and a combo with nothing to offer stays disabled rather than looking empty but clickable (`fix(chat)`).
- **Chat no longer scrolls horizontally** - the message scroller is vertical only, and the RAG and MCP selectors are disabled with an accurate placeholder when there is nothing to select instead of opening an empty list (`fix(chat)`).
- **Wide tables stay inside the process panels** - a wide table in a THINK, MCP TOOLS, or RAG panel scrolls inside the panel instead of stretching the conversation (`fix(chat)`).
- **`page_view` no longer carries the conversation id or title** - the analytics page view now overrides the browser-supplied page location and title with the bare route path and a route-derived label, so neither the `?conv=` id in the URL nor a conversation title derived from user text can reach the wire (`fix(analytics)`).
- **Launcher shutdown and launch arguments** - the actuator shutdown call validates the response instead of treating any reply, including an HTML error page, as success; a stop you asked for is reported as a clean stop rather than an unexpected exit; App Args that name a setting the launcher already owns are dropped before spawn, because Spring merges repeated keys into one comma-joined value and the JVM then fails to boot; and the spawned JVM no longer opens a console window on Windows (`fix(electron)`).

### Documentation

- **Tray, Recent Activity, and Updates** - `docs/getting-started/desktop.md` gains a Tray and Menubar section covering close-to-tray behavior, reopening the window, quitting for real, Recent Activity, the Updates submenu, and the System switches (`docs`).
- **Per-level HITL approval dialogs** - the human-in-the-loop feature page, the HITL architecture page, and tutorial 11 gain L3 / L4 / L5 approval dialog captures and describe the escalating friction; the chat-side gate's decision outcomes are corrected across the safety docs and the Safety dashboard labels (`docs`).
- **Refreshed captures** - chat, preset, tutorial and visualization screenshots recaptured against the current UI, and the integration diagram redrawn (`docs`).

### Security

- **`beans` dropped from the default actuator exposure** - the shipped default is now `health,info,metrics,prometheus`, so a default install no longer serves a full bean inventory. The value stays overridable through standard relaxed binding (`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`), and a test pins the shipped default.
- **Analytics page views send a route path only** - no conversation id and no conversation title leave the browser, on either the web app or the desktop surface.

### Build / Tooling

- **Test suite runs without Docker, Ollama, or a local MCP venv** - the external-dependency integration tests are replaced by loopback MCP coverage (streamable HTTP and SSE), an in-process stdio test server, and browserless render tests across the chat, home, MCP, observability, tool studio and vector store views; the render tests assert on-screen content and flows - per-entry dashboard identity, the model-download dialog's failure paths, the exact `page_view` payload - instead of bare component mounting (`test`).
- **CI runs the full suite on every push** - `skip-tests` is dropped from the shared workflow, workflow permissions are scoped to `contents: read`, the concurrency group keys on the branch name so the push and pull_request events share one group, and the integrity guard job gains a timeout (`ci`).
- **Version bump to 0.2.0-M12** (`build(pom)`).

## [0.2.0-M11]

### Added

- **Bounded agent loop** — Agentic Chat's tool-calling loop is now governed by `AgentLoopManager`, a decorator on Spring AI's `ToolCallingManager` — the Spring AI loop itself stays untouched. A per-turn `AgentTurn` enforces a soft round cap (16 — every further call is answered with a synthetic "wrap up" so the model closes out), a hard cap (18 — `returnDirect` forces the loop shut), an identical-call repeat guard, and a per-turn interactive-tool budget, while an `AgentRoundInterceptor` chain (loop guard → HITL approval → file upload → image reference) can claim any tool call before it executes. Multiple HITL approvals in one round batch into a single dialog, and a cancelled upload or declined approval answers the call cleanly instead of re-triggering it forever. Tunable via `spring.ai.playground.chat.agent-loop.*`, with guard outcomes counted on the `chat.tool.loop` meter (`feat(chat)`).
- **Image attachments — native multimodal vision input** — attach up to five images per message via the picture button, drag-and-drop, or paste. Images are resized and EXIF-tagged in the browser, stored content-addressed under the app home, rendered as removable chips with a lightbox and an `N/5` counter, and handed to the model as native multimodal input; a capability check warns when the selected model cannot actually see (including the Apple Silicon `-mlx` false-positive case). A new `describeImage` tool re-summons an earlier image into the current turn, and **Image analyst** / **Document detective** presets ship on top — the bundled tool catalog grows to 115 (`feat(chat)`, `feat(chat,tools)`).
- **Desktop tray, auto-update, and Ollama auto-start** — the launcher gains a system tray (macOS menubar template icon, settings deep links, Ollama and Voice model managers, update checks, quit). Update checks rank milestones stage-aware (M < RC < GA); on Windows and Linux the update downloads and installs in place via `electron-updater` (NSIS / deb / rpm metadata now published with each release), while unsigned macOS stays semi-automatic with a browser-download fallback. When the selected profile needs Ollama and it is not running, the launcher starts it automatically (app bundle or brew CLI, 15-second readiness poll, tray toggle to opt out), and Whisper voice-model management moves out of the config editor into its own manager window (`feat(electron)`).
- **Home as a launchpad** — the home view reworks into an adaptive hero CTA, provider readiness chips, a live System & Observability panel (bind posture, risk levels, KPIs — every number computed from the running services), a passive checklist, and recent activity; the PWA install card is dropped. On the desktop app, setup links (environment variables, provider set-up) deep-link straight into the launcher's config window instead of the docs site (`feat(home)`).
- **Default chat model `qwen3.5:4b` and a wider model menu** — the Ollama default moves from `qwen3.5:2b` to `qwen3.5:4b`, the smallest build that ships vision tensors, so image chat works out of the box. The menu expands to the qwen3.5 2b/4b/9b, qwen3.6 27b/35b, and gemma4 e2b/e4b/12b/31b families plus `gpt-oss:20b` and `deepseek-r1:8b`, and on Apple Silicon the standard GGUF builds stay in the menu alongside the `-mlx` catalog (MLX builds ship no vision tensors). The **Self-equipping agent** preset becomes the chat default and the Prompt Library is reworked around it, joined by a new **Workspace organizer** preset (`feat(chat)`).
- **Filesystem search, edit, and a destructive tier** — seven new built-in filesystem tools (`searchInFiles`, `editTextFile`, `appendTextFile`, `copyFile`, `moveFile`, `deleteFile`, `deleteDir`) round out the per-conversation workspace, growing the filesystem family from 11 to 18 tools; destructive operations rate the new **L5** risk ceiling and pause for human approval (`feat(tools)`).
- **Visualization action cards and in-chat file upload** — a fourteen-tool visualization family renders ECharts charts, diffs, and multi-point Leaflet maps as review-then-act cards, and a `requestFileUpload` tool lets the model ask for a file mid-conversation: an upload dialog opens, spreadsheets convert to CSV (SheetJS) inside the conversation workspace, and images arrive base64-ready (`feat(chat,tools)`).
- **Dual-level tool risk chips** — built-in and external tool chips show both the inherent risk and the HITL-mitigated effective level, so the mitigation is visible instead of silently replacing the raw score (`feat(mcp)`).
- **Anonymous usage telemetry (opt-out)** — production builds send content-free usage events (thirteen kinds — page views, chat / model / preset / tool usage) with authored names masked; development runs never send, and one switch (`SPRING_AI_PLAYGROUND_TELEMETRY_ENABLED=false`) covers the web app, every launcher window, and the spawned backend (`feat(analytics)`).
- **Keep workspace files when deleting a chat** — deleting a conversation now asks whether its per-conversation workspace files should be kept or removed (`feat(chat)`).

### Fixed

- **Tool fingerprints are key-order independent** — reordered JSON keys in an upstream tool schema no longer flag a false rug-pull on the hash ledger (`fix(mcp)`).
- **Built-in MCP loopback carries the conversation id** — chat tool calls that loop back through `/mcp` propagate the conversation identity, so per-conversation workspace writes land in the right directory (`fix(mcp)`).
- **Unknown tool calls answered with a recovery hint** — a model naming a tool that is not exposed gets a corrective tool response instead of crashing the stream (`fix(mcp)`).
- **Home update banner ranks M / RC / GA correctly and skips on desktop** — version comparison is stage-aware on both the Java and launcher sides, and the desktop app (launcher-managed updates) suppresses the web banner (`fix(home)`).
- **macOS keychain probe skipped when no secrets store exists** — first launch no longer triggers a keychain prompt before anything has been saved (`fix(electron)`).

### Documentation

- **Agent Loop architecture** — new `docs/agent-loop-architecture.md` (the `ToolCallingManager` seam, round verdicts, interceptor chain, limits) cross-linked from the architecture set, plus `configuration.md` coverage of `chat.agent-loop.*` (`docs`).
- **Multimodal Vision** — the Image Attachments page is retitled **Multimodal Vision Input** with refreshed captures, and three new tutorials land: file upload (13), image analysis (14), and a document-detective filesystem pipeline (15) (`docs`).
- **OWASP MCP Top 10 coverage mapping** — a page mapping the playground's controls onto the OWASP MCP Top 10, linked from the architecture nav (`docs`).
- **Filesystem tools, destructive tier, and preset rework** documented across the tool and chat pages (`docs`).
- **Usage telemetry & README refresh** — the telemetry event table and opt-out switch documented; the README links the 30-second YouTube trailer and picks up the new model defaults (`docs`).

### Build / Tooling

- **Version bump to 0.2.0-M11** (`build(pom)`).

## [0.2.0-M10]

### Added

- **Agentic Chat action cards** — `sendEmail`, `addToCalendar`, and `showLocation` are review-then-act tools: the model fills the arguments and the reply renders an interactive card — an email draft, an `.ics` calendar event, or an embedded map — with a confirm button the user clicks; the tool itself never sends, schedules, or navigates, and the assistant is instructed to tell the user to click rather than claim the action is done. A `ChatClientAction` registry (extendable with a single Spring bean) plus a JS `registerActionCard` card registry generalize the pattern, `returnDirect` is inferred from a code marker so an action's card becomes its own reply, and a new **Personal assistant** preset drives them. The bundled tool catalog grows to 88 (`feat(chat,tools)`).
- **Dynamic tool discovery** — a new Agentic Chat mode (Spring AI 2.0 `ToolSearchToolCallingAdvisor`) in which the model searches for the capability it needs through a `toolSearchTool` instead of receiving every tool definition up front, keeping the context small for large tool sets. A persistent, content-addressed `PersistentToolIndex` embeds each tool once and reuses it across conversations — warmed at boot by `ToolIndexWarmup` and persisted under `~/spring-ai-playground/tool-index/`. The chat settings drawer gains a mutually-exclusive **Dynamic tool discovery** ⟷ **Manual built-in tool selection** toggle gated on a minimum tool count, and the **Self-equipping agent** preset ships in dynamic mode (`feat(chat)`).
- **Per-conversation filesystem workspace** — built-in filesystem tools read across the user's home but write only inside a per-conversation workspace (`<app-home>/workspace/<conversationId>/`), bridged through the tool-call identity so writes are isolated per chat and cleaned up with the conversation. A new `listAllowedDirectories` tool reports the readable roots and the active working directory, `SafeFs` is reworked around a home-read / workspace-write `FsScope`, and the workspace base unifies under the single `springAiPlaygroundHomeDir` app-home bean (`feat(tools)`).
- **Clickable file paths in chat** — file paths the assistant emits render as clickable links; on the desktop they reveal the file in the OS file manager (read-only, no execution) through a `LocalFileBrowserService`, with the web and desktop seams kept separate (`feat(chat)`).
- **Local speech-to-text mic in Agentic Chat** — captures voice input and runs whisper.cpp locally via an Electron Node addon (no cloud round-trip). Whisper model selection and download surface in the desktop launcher's config editor and startup splash, alongside in-launcher Ollama model downloads (`feat(chat,electron)`).
- **Ollama runtime and Safety observability dashboards** — two new dashboards bring the Observability set from twelve to fourteen. **Ollama** surfaces live local-runtime telemetry (server reachability, loaded models and their VRAM footprint, GPU/CPU offload, idle-unload countdown, installed-model inventory, Apple Silicon unified-memory pressure); **Safety** surfaces the MCP risk model as lifetime KPIs (risk signals, tamper rejects, poisoning hits, risk-floor overrides, HITL approval rate, sandbox guard blocks) plus a risk-event timeline, backed by a bounded `McpRiskEventRingBuffer` (`feat(observability)`).

### Changed

- **Observability Logs viewer** — the always-empty per-line tool / MCP-server columns are dropped (those signals live on the trace spans, not every log line), a log row opens its detail dialog on **double-click** so single-click is freed for drag-to-select and copy, and the rolling-file / console log pattern no longer emits `[tool= server=]` noise on lines that aren't tool calls (`refactor(observability)`).

### Fixed

- **Preset apply is blocked when a preset's tools need unset API keys** — selecting a chat preset whose tools require missing environment variables now lists the missing keys and disables **Apply** (a hard block) instead of silently applying a preset that cannot function; the bundled preset prompts and tool lists were simplified to key-less core tools so no shipped preset trips the gate (`fix(chat)`).
- **Chat process-panel summaries show the full tool / document list** — the THINK, MCP TOOLS, and RAG summary lines no longer hard-truncate the list to ~40 characters with a literal `...`; the full list renders and the summary now ellipsis-clips at the panel edge (its `vaadin-details-summary` content fills the width) instead of cutting short and leaving empty space (`fix(chat)`).

### Documentation

- **Agentic Chat surfaces** — chat and tool docs rewritten for action cards, dynamic tool discovery, clickable file paths, and the per-conversation filesystem workspace, with the new **Personal assistant** preset (`docs(chat,tools)`).
- **On-device voice input** — documented the local whisper.cpp speech-to-text mic and launcher model download (`docs`).
- **Fourteen Observability dashboards** — new Ollama and Safety pages, dashboard-count propagation (twelve → fourteen) across the architecture and index pages, and README / home positioning updates (`docs`).

### Build / Tooling

- **Version bump to 0.2.0-M10** (`build(pom)`).

## [0.2.0-M9]

### Added

- **Agentic Chat overhaul** — provider-aware request options (`ChatRequestOptionsFactory`), a per-request **reasoning / think** toggle, reusable **system-prompt presets** (`ChatSystemPromptPresetCatalog` / `ChatSystemPromptPresetService`), and a **Prompt Library** of example prompts and `{{variable}}` templates rendered by `ChatSystemPromptTemplateRenderer`. Message bubbles gain a hover toolbar (copy, show raw, collapse, read aloud, cite, Export) and an always-visible **meta row** — elapsed time, token in/out, model, and tool / RAG chips. Rich rendering adds code syntax highlighting, KaTeX math, and Mermaid diagrams, and a shared categorized tool selector scopes which tools a conversation may call (`feat(chat,tools)`).
- **Per-chat memory window with full local history** — a decorator splits the LLM context window from the on-disk archive: a new `LlmWindowChatMemory` feeds only the last N messages to the advisor, while the full store (safety cap 2,000) keeps the entire conversation for the screen and disk. Tunable globally (`memory-max-messages` / `history-max-messages`) and per chat via a memory-window field in the chat settings drawer (`feat(chat)`).
- **Apple Silicon MLX auto-select** — `AppleSiliconMlxActivator` turns on an `mlx` Spring profile on Apple Silicon and auto-selects MLX-quantized model tags for markedly faster local inference; the Electron launcher maps MLX models accordingly (`feat(electron,config)`).
- **MCP safety observability** — a new `McpRiskSignalSink` taps the MCP risk pipeline (server / tool risk scoring, risk-floor overrides, hash-ledger mismatches, composition lifecycle, and tool-poisoning hits) and a default `McpRiskSignalLogger` (`@Component`, replacing the prior NOOP sink) fans each event out to structured logs, Micrometer metrics, and a bounded `McpRiskEventRingBuffer`. A new **Safety** tab in the Observability dashboards surfaces the live risk-event feed, making the integrity verifier, poisoning scanner, and risk calculators observable at runtime (`feat(mcp)`).

### Changed

- **Migrated to Spring AI 2.0 / Spring Boot 4.1 / Vaadin 25** — a full platform upgrade: Spring Boot `3.5 → 4.1`, Spring AI `1.1 → 2.0`, Vaadin `24 → 25`, Jackson `2 → 3` (`tools.jackson`), Spring Security `7`, and the desktop Electron shell `28 → 42` (Chromium 136, required for the Vaadin 25 webview to render). The codebase is adapted to the changed APIs: builder-based immutable chat options, the `ToolCallingAdvisor` / `.tools()` tool-calling surface, Jackson 3's unchecked `JacksonException` / `ValueDeserializer` / immutable-mapper model (with corrupt-file boot resilience restored), Vaadin 25 navigation access control and explicit `@StyleSheet(Lumo.STYLESHEET)` theming, and the `openai` (was `openai-sdk`) model starter. The vendored `OllamaChatModel` patch is dropped (`build(deps)`).

### Fixed

- **Repaired dead built-in tools** — `convertCurrency` (now key-gated) and `getCountryInfo` (allowlist redirect) work again, and oversized tool results are capped and clipped before they overflow the model context (`fix(tools)`).
- **Chat exposed-tool combos repopulate on attach; restored-message timestamp null-guarded** so returning to a conversation no longer shows empty tool selectors or throws on history without a timestamp (`fix(chat)`).
- **Desktop Docker image builds on Spring Boot 4** — the container layer-extraction step switched from the removed `layertools` jarmode to `tools ... extract --layers --launcher`, which Boot 4 dropped (`build(deps)`).
- **Electron download and launcher UX** — per-task download cancel button, throttled download progress, debounced window-fit, config-editor text fixes, and `chmod 600` on the persisted secrets file (`fix(electron)`).
- **Chat settings hardening** — the model-download gate follows the active provider (the download UI stays off for non-Ollama models), stop sequences are validated against the provider cap (OpenAI allows up to 4) and block Apply when exceeded, numeric fields gain bounds and step buttons under a new **Context** section, and the generating indicator keeps pulsing through think / tool / RAG phases (`fix(chat,tools)`).
- **Chat preset apply resets built-in tool exposure** — Apply & New Chat re-exposes exactly the preset's ready (key-less) tools on the built-in MCP server behind a confirmation dialog and persists them so a restart rebuilds the same set, with Tool Studio re-reading exposure on attach (`fix(chat,tools)`).
- **Vector Database and workspace header robustness** — the vector grid degrades to an empty state and a toast when the embedding provider is unreachable instead of aborting navigation, and long content-header titles truncate instead of overflowing (`fix(chat,tools)`).

### Documentation

- **Agentic Chat & context-engineering guides** — new `docs/features/agentic-chat/` pages (guide, Prompt Library, prompt presets / templates) and `docs/context-engineering-architecture.md`, captured against the reworked chat.
- **Integration diagram & External Connections** — a home-page "how it all connects" diagram, a new External Connections page (client config files + model configuration), and safety-architecture scope corrections.
- **Desktop launcher recapture** — refreshed launcher screenshots and walkthrough with Apple Silicon MLX and Download Tasks (cancel) coverage.
- **Aligned with Spring AI 2.0** — `openai-sdk → openai` text and screenshots, refreshed observability captures, mobile-overflow and stale-endpoint fixes, and "composed tools" naming (`fix(docs)`).

### Build / Tooling

- **Dependency upgrade** — Spring Boot 4.1.0, Spring AI 2.0.0, Vaadin 25.1.8, testcontainers 1.21.4, Electron 42, electron-builder 26.15.3 (`build(deps)` / `build(electron)`).
- **Version bump to 0.2.0-M9** (`build(pom)`).

## [0.2.0-M8]

### Added

- **MCP server & tool risk profiles with L0–L5 scoring** — every connected MCP server and each tool it exposes earns a risk level (L0 Verified → L5) derived from `McpRiskFactors` / `McpTrustSignal` signals: transport, authentication (`McpAuthClassifier` / `McpAuthMode`), catalog trust tier, and a per-server **documentation-completeness** dimension. `McpServerInfoService` surfaces a per-server risk chip on the MCP Server Info pane and per-tool chips in the Inspector, a connection-time **tool-poisoning scan** flags description / parameter injection, and `McpCompositionRiskAggregator` rolls member-tool risk up to a composed server. Risk levels ride the observability spans through `McpToolObservationFilter` (`feat(mcp)`).
- **MCP Server Proxy — re-publish external tools on the built-in server** — the **Expose Tools** drawer (gear icon on the MCP Server Info pane) composes selected tools from connected external MCP servers onto the playground's own built-in `/mcp` server. `McpExposedToolService` + `McpCompositionService` + `McpCompositionToolCallbackProvider` register `WrappedExternalToolCallback`s that carry a per-tool **alias / description override**, risk level, HITL flag, logging, and secret masking; an **exposure mode** (`built-in` / `composed` / `both`) and a **max-risk-to-expose** cap gate selection. Re-exposed tools become callable from Agentic Chat and any external `/mcp` client. Persisted via `McpCompositionPersistenceService` (`feat(mcp)`).
- **Human-in-the-Loop (HITL) tool approval** — a per-tool flag pauses an individual tool call and waits for explicit human approval before it runs, enforced at the caller's entry point: a chat approval dialog (`HumanQuestion` / `HumanQuestionHandler` via `HitlToolCallAdvisor`) for Agentic Chat and `McpServerHitlToolGate` (MCP elicitation) for external clients, wired through `McpToolCallingManager`, `ChatService`, and `ChatContentView`. The gate is set per tool in Tool Studio (Sandbox & Capabilities) or per re-exposed tool in the Expose Tools drawer. Also adds session-local chat tool selection so a conversation can scope which tools it may call (`feat(mcp)`).
- **Tamper- and impersonation-proof default tools** — `CanonicalHasher` + `DefaultIntegrityVerifier` name-pin the 86 bundled default tools against a golden `resources/integrity/default-integrity.json` manifest (86 tool hashes + a manifest version). A tool that claims a reserved default name must match the canonical classpath hash or it is rejected at registration in `ToolSpecService`; customizing a default forks it to a new identity rather than mutating the pinned one. A `DefaultIntegrityManifestTest` tripwire fails the build if the shipped catalog drifts from the manifest (`feat(mcp)`).
- **Tool hash ledger re-review approval** — `McpToolHashLedger` records a trust-on-first-use fingerprint for every exposed tool; when an upstream tool's hash changes (rug-pull), `McpCompositionToolCallbackProvider` surfaces a re-review prompt and records the operator's re-approval against the new hash on the ledger (`feat(mcp)`).
- **YAML-configurable built-in MCP server** — `spring.ai.playground.built-in-mcp-server.{name,description,exposure-mode}` (env `SPRING_AI_PLAYGROUND_MCP_NAME` / `_DESCRIPTION` / `_EXPOSURE_MODE`, exposure default `both`) set the built-in server's published name, description, and default exposure at startup, read by `McpServerInfoService` and reflected in the MCP Server view, Observability tabs, and Tool Studio (`feat(mcp)`).
- **Device-based user identity in logs** — `DeviceIdProvider` derives a stable per-device user id that `UserIdentityService` + `MdcIdentityFilter` propagate into the MDC (`user=`) across web requests, the persistence executor, and MCP tool-call log lines, so multi-surface activity correlates to one local identity (`feat(identity)`).

### Changed

- **Chat message rendering moved off Viritin to Vaadin Markdown** — chat bubbles are now a `ChatMessage` built on Vaadin's core `vaadin-message` plus the official `Markdown` component with server-side content, dropping the `viritin` (and an interim `flexmark`) dependency. Avatar color comes from a server-side `userColorIndex` (USER / ASSISTANT presets) instead of a client-only `executeJs`, so it survives component re-attach (`fix(chat)`).
- **Trusted-server tools waive the documentation penalty** — tools from trusted catalog servers no longer take the doc-completeness risk penalty, and HITL is offered as an explicit risk mitigation that lowers the effective level by one band (`fix(mcp)`).
- **Per-server documentation-adequacy evaluation** — the catalog computes a `docsAdequate` signal per server (feeding the risk score), and the Puppeteer entry's docs link is corrected to the archived-servers location (`feat(mcp)`).

### Fixed

- **Chat bodies and avatars survive menu round-trips** — returning to Agentic Chat from another menu no longer blanks the last turn's message body or greys its avatar; the server-side `ChatMessage` re-sends content and color index on re-attach (`fix(chat)`).
- **Chat scroll pinned after stream; same-conversation reload de-duplicated** (`fix(chat)`).
- **MCP server view refreshes on attach; connection-filter empty state fixed** (`fix(mcp)`).
- **Hand-picked built-in MCP exposure survives a restart** — with auto-expose off, the explicitly selected built-in tools are re-published to `/mcp` on startup instead of silently dropping to none until re-confirmed; the startup reconcile also filters drafts, so an un-passed tool can never reach `/mcp` through a stale exposed id, and the chat model-settings *Apply* no longer throws when its drawer was never opened (`fix(mcp)`).
- **MCP connection form polish** — OAuth checkbox, clearable header / env rows, presets fill only empty fields, and PlayMCP Bearer auth (`fix(mcp)`).
- **Expose selection capped by base risk; update banner gated on a genuinely newer release; streaming usage tokens de-duplicated** (`fix(qa)`).
- **Analytics split** — the docs site loads Google Analytics via its own Google tag id (docs stream only) and the app disables GA when telemetry is off (`fix(analytics)`).

### Documentation

- **Human-in-the-Loop pages** — new `docs/hitl-architecture.md` (two-gate approval model and proxied call path), `docs/features/human-in-the-loop.md`, and `docs/tutorials/11-human-approval.md`.
- **MCP server safety, proxy, and configuration** — new `docs/mcp-server-safety.md` (L0–L5 connection risk, tool-poisoning scan, fingerprint ledger, composition shadowing rules, HITL mitigation), `docs/features/mcp-server/proxy.md`, `docs/getting-started/configuration.md`, and `docs/tutorials/10-proxy-external-tool.md`, with screenshots and nav.
- **Catalog & tutorial refresh** — OAuth flow, env rows, per-server risk chips and typical-level chips, setup-time preset vs. exposure drawer, and reordered catalog sections.
- **Docs analytics** — the docs site moved to its own Google tag with production-only loading, and glightbox is skipped on catalog card icons (`docs(site)`).
- **Reference docs normalized to ASCII punctuation; duplicate page titles fixed** — prepares the documentation for translation (`docs`).

### Security

- **Default-tool integrity** — name-pinned canonical hashing rejects tampered or impersonated default tools at registration, and the hash ledger detects upstream rug-pulls and forces human re-review before the changed tool can run.
- **Human-in-the-loop approval gate** — high-risk and re-exposed tools can require explicit per-call human approval before execution, on both the chat path and the external MCP elicitation path.
- **Tool-poisoning scan** — a connection-time scan flags prompt-injection in tool descriptions / parameters and feeds the server risk level.

### Build / Tooling

- **Spring AI bumped to 1.1.7** — `build(pom): bump to 0.2.0-M8, spring-ai 1.1.7`.

## [0.2.0-M7]

### Added

- **Developer-facing Observability dashboards** — Micrometer-backed `ObservabilityCollector` consumes `ChatClientObservation`, `gen_ai.client.operation`, MCP tool callbacks, sandbox metrics, and JVM samplers with MDC propagation (`conv`, `msg`, `traceId`, `spanId`); aggregates into an in-memory ring buffer (2,000 traces, configurable via `spring.ai.playground.observability.*`) plus dated disk persistence with 30-day retention (`~/spring-ai-playground/observability/<YYYY-MM-DD>/<traceId>.json`); surfaces 12 dashboard tabs via Vaadin sidebar — Overview, Tokens & Cost, AI Models, Tool Studio, MCP Servers, MCP Inspector, Vector Database, Agentic Chat, Host, Web Application, Logs, Traces — backed by `BucketedTimeSeries` + `BoundedRingBuffer` and ECharts panels. Recent activity table drills into Trace Detail Dialog (timeline, spans with raw attributes, raw JSON) and Conversation Thread Dialog (USER/ASSISTANT messages with KPIs). Deep-link via `/observability?tab=<slug>&trace=<traceId>` and the "Continue in chat" button lands the conversation back in Agentic Chat. `ObservationRegistry` wired into `ToolCallingManager` and `SimpleVectorStore` so tool / vector spans appear alongside chat client spans.
- **Vendor-official remote MCP server catalog (49 entries)** — curated list of remote MCP servers (`Gmail`, `Outlook-Mail`, `Notion`, `Slack`, `Microsoft-Teams`, `GitHub`, `Linear`, `Atlassian`, `Tavily`, `Exa`, `Firecrawl`, `Sentry`, `Asana`, `HubSpot`, `Mixpanel`, `Figma`, `Canva`, `Webflow`, ...) grouped into Productivity / CRM / Design / DEV / Search / Utility / Example categories in a left-side filter sidebar. Each entry pre-fills the connection JSON with `${ENV_VAR}` placeholders (e.g. `${GITHUB_PERSONAL_ACCESS_TOKEN}`, `${MS_TENANT_ID}`) and surfaces its preview / community / free-tier tags. Activation gates on env-var presence so disabled servers can't be turned on without setup.
- **OS-split stdio MCP catalog** — `default-mcp-specs-stdio-{mac,windows,linux}.json` (8 entries each) loaded per-platform, with `[macOS]` / `[Windows]` / `[Linux]` prefix in description. Tag suggestions derive dynamically from existing entries so new connections auto-complete from the live catalog. Tier 1 (built-in) + Tier 2 (catalog) = 57 inactive entries plus the built-in `spring-ai-playground-tool-mcp` connection (58 total visible on the MCP page).
- **MCP secret masking + tool call logging** — `SecretMasking.mask()` extracts `${ENV_VAR}` references from connection templates, resolves their values via `EnvVarResolver`, and redacts any value ≥ 4 characters from `mcp.tool.crash` error logs (replaced with `***`). `LoggingMcpToolCallback` wraps every MCP tool callback with `mcp.tool.start` / `mcp.tool.done` / `mcp.tool.crash` log lines carrying an 8-character correlation id (`cid`), server name, tool name, duration ms, and `via=chat` channel marker — feeds the Observability MCP / Tools tabs.
- **Electron launch hardening** — `fs.existsSync` validation of the bundled JRE path (`process.resourcesPath/jre-bundle/bin/java[.exe]`) with explicit fatal-error message pointing to the `electron/scripts/prepare-resources.mjs` step. Telemetry env (`SPRING_AI_PLAYGROUND_TELEMETRY_ENABLED`) propagated from the Electron main process into the spawned Java via `buildSpawnArguments` (entire `process.env` merged into `spawn` env) and into the splash / config editor / Ollama manager / server splash windows via `?telemetry=0` query string. `launchReadinessState` machine (`idle → starting → ready / failed / timedOut`) feeds the splash with stage messages.

### Changed

- **Shared sidebar widgets extracted** — `SidebarSection`, `SidebarItem`, and `PageSidebar` factored out as reusable components (`refactor(ui)`). Tool Studio, MCP Server, Vector Database, Agentic Chat, and the Observability dashboards now share the same look and interaction (bolded active item, group headers, count chips). The MCP Inspector header also picks up the status indicator + active counter.
- **Tool resources regrouped under `tool/`** — bundled tool catalog JSON files (`default-tool-specs*.json`), categories, presets, and workspace-samples moved into `src/main/resources/tool/` (`refactor(resources)`). The wildcard pattern (`classpath*:tool/default-tool-specs*.json`) at `default-tool-location` continues to load all six bundles.
- **MCP startup spawn deduplicated and Inspector polished** — removed the duplicate MCP client startup spawn and consolidated the sidebar / Inspector header into a single entry point (`fix(mcp)`).

### Fixed

- **Tool Studio empty optional testValue no longer breaks Test Run** — an optional parameter with an empty `testValue` used to throw at Test Run time; the regression is fixed alongside JSHint editor cleanup and KR locale tidy (`fix(tool-studio)`).
- **Persistence boot NPE** — added a `shouldLoadFile(Path)` hook on `PersistenceServiceInterface` so the default `loads()` path skips `.tmp`, `.tmp.json`, and `.deprecated` files plus foreign JSON files (e.g. `default-tools-preference.json`). `ToolSpecPersistenceService.shouldLoadFile` further narrows to exactly `toolSpecsMcpSetting`. Closes the boot-time NPE from bad deserialization (`fix(persistence)`).
- **Catalog seed test alignment** — `McpCatalogServiceTest.tierSplitMatchesCatalogSeed` updated to tier2 size `30 to 39` and the new `MS_TENANT_ID` placeholder reflecting the catalog expansion (`fix(mcp)`).
- **Style polish** — `@Test` methods unified to camelCase, FQN dropped in `PersistenceService`, sidebar active items rendered bold (`fix(style)`).

### Documentation

- **Observability feature pages** — new `docs/features/observability/` directory: `overview.md`, `ai-usage/{index, tokens-cost, ai-models}.md`, `ai-stack/{index, tool-studio, mcp-servers, mcp-inspector, vector-database, agentic-chat}.md`, and `runtime/{index, host, web-application, logs, traces}.md` (14 pages). Each page covers KPI / chart descriptions, full-page screenshots, and drill-down scenarios.
- **Default MCP Catalog feature pages** — `docs/features/default-mcp-catalog/` split per category (`business.md`, `data-cloud.md`, `dev.md`, `examples.md`, `productivity.md`, `search.md`, `index.md`).
- **MCP Inspector feature page** — new `docs/features/mcp-server/inspector.md` covering all 8 inspector tabs (Tools / Resources / Prompts / Ping / Notifications / Roots / Sampling / Elicitation) with screenshots.
- **Safe Tool Specification reference** — `docs/safe-tool-specification.md` plus `docs/safe-tool-spec.schema.json` (JSON Schema for tool spec authoring).
- **Tutorial 9: MCP-Everything walkthrough** — end-to-end from `@modelcontextprotocol/server-everything` activation through every Inspector tab.
- **Tool Studio docs promoted** — `docs/features/tool-studio.md` is now `docs/features/tool-studio/index.md` (directory layout).
- **Observability architecture overview** — `docs/observability-architecture.md` walks the collector → ring buffer → time series → dashboard flow.
- **Mobile nav polish** — pre-paint flicker guard CSS in `docs-overrides/main.html`, depth-1 auto-expand via `docs/assets/javascripts/nav-default-expand.js`, and `navigation.instant` + `navigation.instant.progress` features enabled in `mkdocs.yml`.

### Build / Tooling

- **Spring AI checkstyle baseline adopted** — `maven-checkstyle-plugin 3.6.0` + `checkstyle 10.21.0` + `spring-javaformat-checkstyle 0.0.47` plugin block added to `pom.xml`, bound to the `verify` phase with `failOnViolation=true`. `src/checkstyle/checkstyle.xml` mirrors the Spring AI baseline with project-specific deltas (4-space indent kept; `JavadocPackage`, `SpringLambdaCheck`, `NeedBracesCheck`, `InnerTypeLastCheck` removed; `RightCurlyCheck option=same`; `WhitespaceAround` empty-* options relaxed; `AvoidStaticImport` excludes extended with internal helpers). The license header regex is inlined as a `header` property so the same config works in IntelliJ Checkstyle without extra path setup. `src/checkstyle/checkstyle-suppressions.xml` excludes 131 M6 baseline files, the OllamaChatModel upstream patch, and 5 files introduced by commits already pushed to `origin/main` — incremental adoption: only new and modified files participate in the scan.
- **CI verify** — `.github/workflows/ci.yml` `maven-goals` flipped from `clean compile -B` to `clean verify -B` (skip-tests unchanged), so checkstyle runs on every push and PR.
- **Spring AI bumped to 1.1.6** — `chore: bump to 0.2.0-M7 with spring-ai 1.1.6`.

## [0.2.0-M6]

### Added

- Bundled **default-tool catalog of 86 tools** split across five JSON source files (`default-tool-specs.json`, `default-tool-specs-builtin.json`, `default-tool-specs-builtin-helpers.json`, `default-tool-specs-builtin-fs.json`, `default-tool-specs-network.json`, `default-tool-specs-kr.json`) loaded via wildcard `classpath*:default-tool-specs*.json`. Categories: Text & Strings, Data Formats, Date & Time, Math & Compute, Encoding, Crypto & Random, Security, Files, Web (global + Korea), Productivity, Messaging, AI APIs.
- **`DefaultToolPresetCatalog` + preset/preference resolver** picking which slice of the bundled catalog the built-in MCP server exposes. Five presets shipped: `Starter 5` (default, no setup), `Dev Essentials`, `Korea Toolkit (free)`, `File Toolkit`, `Everything`. `DefaultToolsPreferenceService` layers per-tool include / exclude rules (by tag / category / name) on top of the chosen preset and persists to `~/spring-ai-playground/tool/save/default-tools-preference.json`. CLI override resolvable through `defaultToolsPreferenceResolver`.
- **Tool MCP Server Setting drawer** in Tool Studio (toolbox icon) — single surface for what the built-in MCP server exposes. Three sections: **Tools exposed** chip summary, **Custom tools (you created)** with auto-add toggle + Manually exposed combo, **Default tools (built-in)** with Preset radio and Advanced curation (include / exclude rules). Writes to the same `default-tools-preference.json` file as the desktop launcher.
- **Desktop launcher Default MCP Tools card** in the config editor — preset chooser with active-preset chip preview + collapsible Advanced curation block (include / exclude by tag / category / name). Edits the same `default-tools-preference.json` as Tool Studio's drawer.
- **Tool Studio Draft state + MCP exposure gate**: a new or unverified tool sits in the **Drafts** section and is **not** registered with the built-in MCP server. A Local Pass flips the `McpToolDefinition` exposure flag and `ToolActivationCalculator` registers the callback with `McpSyncServer` live (no restart). Tool Studio sidebar surfaces Drafts and Local Pass panels separately.
- **`McpToolDefinition` record + `ToolManifest` envelope** for every published tool — manifest hash, code hash, runtime helpers list, sandbox risk level, audit timestamps, integrity hashes, optional signature.
- **Per-tool sandbox capability overrides** via `EffectivePolicyResolver` — `addAllowClasses`, `addAllowedHosts`, `egressLevel` (`strict` / `allowlist` / `custom` / `permissive`), `fileRead` / `fileWrite`, `fsBasePath`. Exposed in the **Sandbox & Capabilities** pane with a visible per-tool **Risk Level** badge (L0–L5) computed by `SandboxPostureCalculator`.
- **Tool Studio per-tool surface** — category-grouped sidebar (backed by `ToolCategoryCatalog` + `ChipListBinding`) with chip filters across cohort tags (`korea`, `example`, `util`, `pipeline`, `github`, `search`, `finance`, `weather`, `geo`).
- **Tier 1 built-in JavaScript helpers** (registered through `JsRuntimeGlobals`):
  - `fetch` with a 4-layer SSRF guard — scheme allowlist, literal-IP check, DNS resolve check, and per-tool egress policy. 5 redirect cap, 10 MB body cap, 30 s timeout, 5 s connect timeout. Restricted hop-by-hop headers stripped before sending; 303 redirects downgrade `POST → GET`. Response paging via `init.maxLength` / `init.startIndex`. Backed by `SafeHttpFetch`.
  - `URL`, `URLSearchParams`, `atob`, `btoa`, and `crypto.subtle` (digest / sign / verify / `getRandomValues`).
- **Tier 2 built-in JavaScript helpers**:
  - `safety.fs` — `readText`, `writeText`, `list`, `stat`, `grep`, `lineCount`, `slice`, `cut`, `sort`, `find`. All paths resolved against `tool-studio.fs.base-path` and rejected on escape. Backed by `SafeFs`.
  - `safety.parser.{html,yaml,csv,xml}` — strict, non-instantiating parsers (HTML via jsoup, XML via XXE-hardened `javax.xml.parsers`, YAML via SnakeYAML, CSV via Apache Commons CSV) exposed without leaking host classes to user code.
- **`JsHelperException`** for structured helper errors (security / invalid-input / helper-runtime) plus an executor-level error classifier so JS-side failures surface consistently on both the Test Run path and the MCP path.
- **Tool execution observability** — per-execution `tool.exec.start` / `tool.exec.done` / `tool.exec.crash` log lines with correlation id, risk level, capability summary, allowed hosts, fs base path, masked params, and `durationMs`. Env-backed static-variable values are masked from the params summary before logging.
- **Spring AI MCP client capability surface** expanded with an in-memory notification store and loopback safety so the embedded tool MCP server can co-host with the loopback `spring-ai-playground-tool-mcp` client on the same JVM without race conditions.

### Changed

- **Default sandbox posture is now deny-first**:
  - `allow-network-io` defaults to `false` (was `true`).
  - `allow-classes` shrunk to pure-compute packages only (`java.lang/math/time/util/text.*`). `java.net.*`, `java.io.*`, `java.net.http.*`, `org.jsoup.*` are no longer reachable directly.
  - New `deny-classes` list (evaluated before allow-classes; deny always wins) covers `System`, `Runtime`, `ProcessBuilder`, `Process`, `Class`, `java.lang.invoke.*`, `java.lang.reflect.*`, `Thread`, `ThreadGroup`, `ClassLoader`, `ServiceLoader`, `java.util.spi.*` — closing reflection / SPI / process-spawn escape vectors.
  - `tool-studio.fs.base-path` introduced for `safety.fs`, defaulting to `${TOOL_STUDIO_FS_BASE:${user.home}}`.
- `default-tool-location` moved under `spring.ai.playground` and switched to wildcard `classpath*:default-tool-specs*.json` so the bundled catalog can ship as multiple JSON files without code changes.
- Tool taxonomy refactored to a tool-centric category model (`ToolCategoryCatalog`, `ToolCategory` taxonomy). Sidebar grouped by category and filterable with chips.
- Tool studio view extracted (`SandboxCapabilitiesView`, `ToolMcpServerSettingView`) and polished alongside Electron window chrome and chat send affordances.
- `JsToolExecutor` and its helpers moved to `service/tool/runtime/`; policy code lives under `service/tool/policy/`.
- Env-var fail-fast hoisted into Java (drop JS-side `ensureResolved`) so a tool with an unresolved `${ENV_VAR}` placeholder fails at registration / load time instead of inside the JS sandbox.
- Strict network mode now rehydrates sandbox overrides on tool edit so capability changes (e.g. switching `egressLevel` from `strict` to `allowlist`) are reflected in the next test run without restarting Tool Studio.

### Fixed

- Surface JS errors on the MCP path consistently with the Test Run path; correctly materialise `Map` / `List` parameters from MCP JSON.
- Scope **Manually exposed tools** combo in the Tool MCP Server Setting drawer to custom-authored tools only — the 86 bundled default tools are managed through the Preset / Advanced curation block above and no longer leak into this combo.

### Security

- Network access from tool JS goes through a single reviewable choke point (`fetch` → `SafeHttpFetch`). Strict mode rejects loopback / link-local / site-local / any-local / multicast / IPv6 ULA / CGNAT addresses both as literals and after DNS resolution.
- `safety.fs` enforces a base-path jail; any path that resolves outside `tool-studio.fs.base-path` is rejected before any I/O.
- Environment-backed static variables are masked in `console.log` output, in the Test Run debug pane, and in `tool.exec.*` observability log lines.
- `deny-classes` evaluation order (deny before allow) closes reflection / `invoke` / `ClassLoader` / `ServiceLoader` / `Thread` / `Process` escape vectors that a permissive allow-list could otherwise re-open per tool.

### Documentation

- Docs site restructured to a per-page surface: **Features** (`tool-studio`, `mcp-server`, `vector-database`, `agentic-chat`) and **Tutorials** (1–8 individually) replace the previous monolithic `features.md` / `tutorials.md`.
- New **Default Tools** reference set under `docs/features/default-tools/` — index + Examples / Utilities / Filesystem / Global / Korea pages, each with per-tool cards (params, env vars, sandbox risk level, JS source), a Keys & secrets footer (issuance URLs for every required key including the eight-service `data.go.kr` keychain), and shared composition-pattern guidance.
- New **AI Agent Tool Safety Architecture** page (`docs/safety-architecture.md`) — defense-in-depth sandbox model, policy resolution, per-execution enforcement, Risk Level decision matrix, threat-to-layer mapping, known limitations, configuration reference.
- New **Tutorial 8: Default Tool Recipes** walks composition patterns (search → summarise, fetch → notify, time + calendar, cross-exchange spread, disaster → Slack, etc.) end-to-end.
- README and Home updated for the 86-default-tool blurb, version refs `M4` → `M6`, and the Default MCP Tools curation entry-point.

## [0.2.0-M5] - 2026-05-06

### Added

- OAuth 2.1 Authorization Code support per MCP server: dedicated sub-form (Client ID, Issuer URI, Scopes, Advanced overrides, Authorize button), browser-based consent via the system default browser, AWAITING_AUTHORIZATION state, Home dashboard awaiting-auth counter, and an encrypted disk-persisted token store under `~/spring-ai-playground/mcp/oauth-tokens/` with host-specific salt + `user.home` key derivation and transparent refresh.
- Per-server HTTP headers with `${ENV_VAR}` placeholder substitution (resolved at connect time from OS environment with JVM system-property fallback). Auth header presets — Bearer Token, Basic Auth, API Key Header — drop a templated row into the Headers section. The same `${VAR}` syntax also applies to STDIO `env` values and `requiredEnv` lists.
- MCP Inspector expansion to eight tabs: Tools (refactored), Resources (with templates), Prompts, Ping, Notifications, Roots, Sampling, Elicitation — server-side primitives and client-side primitives both surfaced for development and debugging.
- MCP server view redesign with per-server status indicators (OK / OFFLINE / ERROR sidebar dots), a Test Connection button that spins up a transient sync client without touching the live connection, full-width Tools cards with JSON-Schema-typed inputs and tool-annotation badges (read-only / destructive / idempotent / open-world), an inline per-card result panel (REQUEST / RESPONSE / Raw JSON-RPC toggle, Copy with toast feedback, dismiss), and a search filter.
- `mcp-stdio` Spring profile that switches the embedded MCP server to stdio transport while keeping the Vaadin Inspector on port 8282 in the same process. Opt-in via `SPRING_PROFILES_INCLUDE=mcp-stdio` (preserves the default `ollama` profile so model config still applies). Logback detaches the CONSOLE appender in this mode so stdout stays a clean JSON-RPC channel; rolling-file logs at `~/spring-ai-playground/logs/` are unaffected.
- Container repackaging with a custom jlink JRE (~70 MB vs ~380 MB full JDK; Truffle/JS support intact for the JavaScript tool sandbox), dual-mode build (CI-built fat JAR fast path or local `mvn package` fallback driven by `.dockerignore`), `debian:bookworm-slim` runtime base, and an `io.modelcontextprotocol.server.name` OCI label for MCP Registry ownership verification.
- Unified release pipeline (`.github/workflows/release.yml`) builds the fat JAR once and shares it across both the Docker image matrix (native `linux/amd64` + `ubuntu-24.04-arm` runners, no QEMU emulation) and the desktop installer matrix (mac arm64/Intel, Windows NSIS, Linux DEB/RPM). The GitHub Release is atomic across both tracks, with SHA-256 checksums and Sigstore SLSA build provenance attestations.
- Home sidebar links to Discussions and the bug-report flow.

### Changed

- MCP client capability surface expanded with an in-memory notification store. When the embedded server runs in stdio mode, the loopback `updateDefaultMcpTool` flow short-circuits to avoid attempting a streamable-http connection to its own non-HTTP endpoint.
- CI workflow housekeeping: `ci.yml` cancels duplicate runs from push + pull-request event pairs via a concurrency group; `actions/checkout` bumped to v5 on the docs-site workflow; greeter workflow's `repo-token` renamed to `repo_token` to match `actions/first-interaction@v3`.
- PWA install popup no longer auto-shows on the first web visit; installation remains available through the browser's normal install affordance.

## [0.2.0-M4] - 2026-04-27

### Added

- New adaptive Home dashboard with workspace status, provider health pill, recent activity, and a Getting Started checklist that mirrors the first-run flow.
- Agentic Chat surfaces RAG search options (query, filter, top-K, similarity threshold) and emits a structured retrieval-completion event so the UI can show retrieved doc count and titles.
- Agentic Chat anchors the new prompt at the top of the viewport on submit, labels each step with timing/counts (`RAG · {time} · {N docs} · {titles}`, `THINK · {time}`, `MCP TOOLS · {time} · {N calls} · {names}`), preserves the partial assistant response when the user clicks Stop, and shows a Stopped/Error stream-status badge that persists across reload.
- Common workspace shell shared by Chat, MCP, Tool Studio, and Vector Database surfaces (sidebar + content header + settings drawer) for consistent layout.
- Telemetry opt-out (`SPRING_AI_PLAYGROUND_TELEMETRY_ENABLED=false`) now applies uniformly across the web app and every desktop launcher window (splash, server-splash, config editor, Ollama manager).
- Release assets ship with SHA-256 checksums (`.sha256`) and Sigstore build provenance attestations, verifiable via `gh attestation verify --owner spring-ai-community`.
- Documentation site generates per-page Open Graph / Twitter social cards (1200×630) and embeds `SoftwareApplication` JSON-LD schema for richer search results.
- Getting Started page now hosts a platform-aware download confirm popup that resolves the latest release through the GitHub API, shows filename, size, and the typical OS download path; README and the docs home redirect platform badges to the same popup.

### Changed

- Persistence rewritten to save on mutation through an async executor with atomic writes; `SimpleVectorStore` dumps are debounced to coalesce bursts into a single write, keeping disk-write threads non-daemon so in-flight writes finish on shutdown.
- Generic `RuntimeException` wrapping replaced with targeted error handling; MCP startup uses sequential per-server iteration with try/catch in place of `parallelStream` to isolate failures.
- Standardized desktop installer filenames to `spring-ai-playground-<version>-<platform>-<arch>.<ext>` (no spaces, version included). Bumped to `0.2.0-M4`. CI now resolves the installer version from the branch/tag ref and injects it into `electron/package.json` at build time.
- Linux `.deb` / `.rpm` package metadata renamed from `spring-ai-playground-desktop` to `spring-ai-playground` for consistency with the bundle ID, binary, and user-visible app name.
- Architecture documentation split into a dedicated page; README and mkdocs nav aligned with the new Home dashboard.

### Fixed

- `APP_HOME` now defaults to `<user.home>/<app-name>` when the JVM `user.home` property is unset, preventing log/config write failures in containerized environments.
- Loopback MCP client and embedded MCP server are closed before Tomcat graceful shutdown so the JVM exits cleanly.
- Saved persistence files are deleted immediately on removal instead of being deferred to JVM exit, eliminating stale files after a crash.
- MCP client map keys scoped per-server, stale clients closed on update, and self-update flows allowed without orphaning the previous client.
- Chat history reload restores `tool_calls` and matching tool responses correctly.
- Renamed `lancher-*` documentation assets to `launcher-*` (typo fix).

### Security

- Persisted file names are sanitized to prevent path traversal when writing under the application home directory.

## [0.2.0-M3] - 2026-04-18

### Added

- Shared community CI workflow for build verification across branches and pull requests.
- Desktop launcher support for downloading, reviewing, copying, retrying, and deleting Ollama models from a dedicated model manager window.
- Desktop launcher guidance screenshots covering the config editor, startup flow, and Ollama model manager.

### Changed

- Expanded the desktop launcher documentation in the README and getting started guide, including platform-specific install notes and launcher walkthroughs.
- Improved launcher setup UX with clearer Ollama status information, installed-model visibility, and a more capable configuration flow.
- Limited desktop and container release workflows to `main` pushes and version tags, while keeping the general CI workflow available on every branch.

### Fixed

- Corrected invalid launcher config template YAML structure for Spring AI settings.
- Improved Electron launcher startup and splash behavior, including more consistent Spring server shutdown handling across platforms.
