title: MCP Servers
description: External MCP server health and traffic - latency by transport, OAuth state across servers, lifecycle events, transport mix, server status timeline.

# MCP Servers

![MCP Servers dashboard - sections labelled SERVERS - VOLUME & QUALITY and OAUTH STATE; six KPI cards on the volume row (MCP servers, Servers up, MCP tool calls, Distinct tools, p95 latency, Error rate), four KPI cards in the OAuth row (OAuth authorized, OAuth awaiting, OAuth errored/offline, OAuth expiring <5 min), and the MCP calls / minute stacked-by-transport chart](../../../assets/images/observability/mcp-servers-full.png)

*MCP Servers - the OAuth cards count only servers that use OAuth, so the built-in server is not among them. Tool-call volume bars surface as soon as an external MCP catalog entry is activated and the agent reaches a tool through it.*

**Purpose** - external MCP server health and traffic. Latency by transport, OAuth state across servers, lifecycle events.

## When to look here

- *"Is one MCP server slow?"* - Latency p50 / p95 / p99 stacked by transport; Top servers identifies which one carries the most traffic.
- *"Did an OAuth token expire silently?"* - OAuth expiring <5 min KPI + the OAuth status grid below.
- *"Is the agent reaching a server that crashed?"* - Servers up KPI (`up / configured`, e.g. `3 / 5` means two configured servers are down).
- *"Which transport family dominates?"* - Transport mix donut (`pipe` = STDIO vs `tcp` = HTTP).
- *"Did the lifecycle behave abnormally?"* - Lifecycle events by transport class chart (initialize / shutdown / etc.).

## Span filter

`spring.ai.tool` spans where `mcp.method.name` is present.

Each such span is enriched by `McpToolObservationFilter` with **risk and composition dimensions** lifted from the call's MDC context: `saip.tool.origin` (`internal_js` / `wrapped_external`), `saip.composition.id` / `.name`, `saip.tool.exposed_alias`, `saip.mcp.server` (the upstream server name), the three risk levels `saip.risk.final` / `.server` / `.publish`, and `saip.risk.floor_trigger` when a floor rule tripped. So a tool re-exposed through [composition](../../mcp-server/index.md#expose-external-tools) is traceable back to its upstream server and its computed risk - not just its exposed alias.

**Proxied calls.** A tool re-exposed on the built-in MCP server is recognised by its exposed name, so its span is tagged with the upstream server (`saip.mcp.server`), the upstream tool (`saip.upstream.tool`), the upstream transport and `saip.tool.origin=wrapped_external`. This covers a call from Agentic Chat (whether the tool was picked manually or found by dynamic tool search) and a call from an external MCP client to the built-in `/mcp` endpoint, which gets its own `spring.ai.tool` span tagged `saip.via=mcp-server` and `saip.mcp.client=<client name>`. The **Callers** chart splits the MCP tool calls between Agentic Chat and each external client. A proxied call is one span: it is listed here under the upstream server and tool, on [Tool Studio](tool-studio.md) under its exposed name, and Overview counts it once. The **Proxied calls** card says how many of the MCP tool calls arrived this way. A proxied call this app refuses at the human-in-the-loop gate never reaches the server: it is shown on the **Denied calls** card and left out of MCP tool calls, latency, error rate and the per-server charts. Tool Studio and Safety show it as well.

## Controls

All dashboards share the [Observability global settings](../index.md#global-settings) - time window, refresh interval, custom range. MCP Servers has no tab-specific controls beyond those.

## KPI cards - Servers and Calls (eight)

| Card | Shows | Source |
|---|---|---|
| MCP servers | Number of distinct external MCP servers that received tool calls in the window (self-loopback excluded) | Distinct `saip.mcp.server` on qualifying spans |
| Servers up | Number with last connection state OK | Live ping / connection state |
| MCP tool calls | Count of all externally-routed tool calls | Spans with `mcp.method.name` non-null |
| Distinct tools | Unique upstream tool names called externally | `set(saip.upstream.tool or spring.ai.tool.definition.name)` filtered |
| Proxied calls | Part of MCP tool calls that came through a tool exposed on the built-in MCP server | Spans with `saip.tool.origin=wrapped_external` |
| Denied calls | Proxied calls refused at the human-in-the-loop gate before reaching the server (not part of MCP tool calls) | Spans with `saip.hitl.outcome` and an upstream server |
| p95 latency | 95th-percentile external tool duration | Span duration distribution |
| Error rate | Percentage of external calls with `status=ERROR` | Span status |

## KPI cards - OAuth State (four)

| Card | Shows | Source |
|---|---|---|
| OAuth authorized | Count of OAuth-protected servers currently in authorized state | `McpClientService.snapshotOAuthState()` rows with status `OK` and an access or refresh token bound |
| OAuth awaiting | Count awaiting authorization (user-driven OAuth dance not yet completed) | OAuth state machine |
| OAuth errored / offline | Count where OAuth flow has errored or the server is unreachable | OAuth state machine + ping |
| OAuth expiring < 5 min | Token-refresh urgency - these will fail soon if not refreshed | Token expiry timestamp |

## Charts (eight)

| Chart | Type | Reading |
|---|---|---|
| MCP calls / minute | Line per transport, stacked | Drop to zero on one transport → that transport class lost connectivity |
| Latency p50 / p95 / p99 | Multi-line, ms (one per transport) | stdio is consistently faster (no network); HTTP variants show round-trip cost |
| Top servers | Horizontal bar, by call count | Identifies the server carrying the most traffic |
| Top MCP tools | Horizontal bar, by call count | Same question scoped to specific tool names across servers |
| Transport mix | Donut by `network.transport` (`pipe` = STDIO, `tcp` = Streamable HTTP or SSE) | Sanity check against expected routing |
| Server status | Stacked bar over time (OK / Error / Awaiting) | Spot a server flipping in/out of healthy state |
| Lifecycle events by transport class | Bar | Unusual `shutdown` cluster → server crash loop |
| Callers | Horizontal bar, Agentic Chat and each external MCP client by call count | A client name here means an external client reached the server through a proxied tool |

## Tables

**OAuth status grid** - `Server · Transport · Status · ...` - sorted by Status; expiring tokens highlighted. Click a row to navigate to the MCP Server connection page where you can re-authorize.

## Cross-references

- [MCP Server (feature)](../../mcp-server/index.md) - how external MCP servers are registered + the Inspector built-in to Spring AI Playground
- [MCP Inspector](mcp-inspector.md) - sibling tab for MCP primitive operations (read tools, read resources, etc.) rather than tool execution
- [Tool Studio](tool-studio.md) - sibling tab for the same shape of data, locally executed
- [Observability Architecture → Tool and MCP observability](../../../observability-architecture.md#tool-and-mcp-observability-the-agentic-focus) - `mcp.method.name` discriminator
