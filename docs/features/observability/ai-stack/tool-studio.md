title: Tool Studio Observability
description: In-process tool execution observability - JS sandbox and local @Tool methods, latency p50/p95/p99, error rate, and sandbox guard-block counts.

# Tool Studio

![Tool Studio dashboard - top note "Built-in tools - JS sandbox functions and local @Tool methods, including self-loopback through the in-process MCP server", six KPI cards (Traces with tools, Total tool calls, Distinct tools, p95 latency, Error rate, Sandbox guard blocks), and a Tool calls / minute stacked-over-time chart](../../../assets/images/observability/tool-studio-full.png)

*Tool Studio - counts in-process Spring AI tool callbacks, calls through the built-in MCP server's self-loopback (the default chat path), external tools the built-in server proxies, and calls external MCP clients make to `/mcp`. Direct calls to an external MCP server appear only in [MCP Servers](mcp-servers.md). The `Sandbox guard blocks` KPI ties this dashboard to the prevention layer in [Safety Architecture](../../../safety-architecture.md) regardless of where the tool was routed.*

**Purpose** - observe in-process tool execution: the JS-sandbox tools authored in Tool Studio, every Spring AI `@Tool` method, anything the agent calls *without* leaving the JVM. Cross-references the sandbox prevention layer through the `sandbox.guard.blocked` counter.

## When to look here

- *"Did the agent's tool selection finally trigger the expected `searchWikipedia` call?"* - Top tools horizontal bar.
- *"Why is my own in-process tool slow?"* - Tool latency p50 / p95 / p99.
- *"How often is the sandbox actually blocking unsafe actions?"* - Sandbox guard blocks counter + chart (zero is healthy; sustained non-zero means agent is repeatedly trying something the sandbox denies).
- *"What Risk Level posture is the running tool catalog?"* - Sandbox level histogram (L0 / L3 / L4 / L5 distribution).
- *"Are tools failing more than they used to?"* - Error rate KPI + the Tool calls / minute trend line.

## Span filter

`spring.ai.tool` spans that are in-process (`mcp.method.name` absent), that go through the built-in MCP server's own loopback, or that call an external tool the built-in server proxies (`saip.tool.origin=wrapped_external`).

A proxied call is one span. It is listed here under its exposed name and on [MCP Servers](mcp-servers.md) under its upstream server and tool; Overview counts it once. The **Proxied calls** card says how many of the tool calls are of this kind. Direct calls to an external MCP server appear only on MCP Servers.

**External clients.** A call an external MCP client makes to the built-in `/mcp` endpoint, to a Tool Studio tool or to a proxied tool, gets its own `spring.ai.tool` span tagged `saip.via=mcp-server` and `saip.mcp.client=<client name>`, so use of the built-in server from outside the app is counted here as well. The **Callers** chart splits the tool calls between Agentic Chat and each external client. Such a trace holds only the tool span, so it is not counted as an LLM call in Overview, AI Models or Tokens & Cost.

**Denied calls.** A tool call that was requested but not run because human-in-the-loop approval was declined, timed out or could not be asked is still recorded as a `spring.ai.tool` span, tagged `saip.hitl.outcome` (`declined`, `timeout`, `ask-failed` on the chat side; `denied`, `declined`, `timeout`, `elicit-failed` for an external client). It counts in Total tool calls, Top tools and Callers, and is shown separately as **Denied calls**; it is left out of latency, error rate and the sandbox level chart. A denied proxied call never reaches the upstream server, so MCP Servers shows it only on its own **Denied calls** card, outside the server's call counts. The [Safety](safety.md) tab keeps showing the approval decisions by side.

## Controls

All dashboards share the [Observability global settings](../index.md#global-settings) - time window, refresh interval, custom range. Tool Studio has no tab-specific controls beyond those.

## KPI cards (eight)

| Card | Shows | Source |
|---|---|---|
| Traces with tools | Number of traces with at least one tool call on this tab (in-process, self-loopback or proxied) | Distinct trace IDs with a qualifying `spring.ai.tool` span |
| Total tool calls | Count of all qualifying tool spans, denied calls included | Sum of qualifying spans |
| Distinct tools | Number of unique tool names called | `set(spring.ai.tool.definition.name)` size |
| Proxied calls | Part of Total tool calls that went to an external tool exposed on the built-in MCP server | Spans with `saip.tool.origin=wrapped_external` |
| Denied calls | Part of Total tool calls that were not run because approval was not granted | Spans with `saip.hitl.outcome` |
| p95 latency | 95th-percentile duration of executed tool calls | Span duration distribution |
| Error rate | Percentage of executed tool spans with status `ERROR` (denied calls excluded) | Span status |
| **Sandbox guard blocks** | Cumulative `sandbox.guard.blocked` counter | `MeterRegistry` counter incremented by the sandbox at each block |

The **Sandbox guard blocks** KPI is the concrete bridge to the Safety layer. See [Safety Architecture → AI Agent Tool Safety](../../../safety-architecture.md) for what triggers a block and [Observability Architecture → Foundation for behavioural controls](../../../observability-architecture.md#what-this-data-could-support) for how this counter ties Sandbox prevention to Observability visibility.

## Charts (six)

| Chart | Type | Reading |
|---|---|---|
| Tool calls / minute | Stacked bar (top 4 tools), in-process spans/min | Spike → agent in a tool-heavy phase; sustained zero with chat traffic → tools not being selected (check Top tools below) |
| Tool latency p50 / p95 / p99 | Multi-line, ms | Diverging p99 from p50 → tail-latency regression in one tool |
| Top tools | Horizontal bar (top 8 by call count) | The most-called tool head; helps spot accidental tool selection |
| Sandbox level | Histogram across L0 / L3 / L4 / L5 by call | L0 = safest baseline; high L5 traffic warrants per-tool review |
| Sandbox guard blocks | Horizontal bar (lifetime), by `category` / `reason` | Cluster of blocks → agent attempting forbidden action repeatedly (e.g. SSRF probe) |
| Callers | Horizontal bar, Agentic Chat and each external MCP client by call count | A client name here means the built-in MCP server is being used from outside the app |

## Cross-references

- [Safety Architecture](../../../safety-architecture.md) - sandbox layers, Risk Level, what triggers a guard block
- [Tool Studio (feature)](../../tool-studio/index.md) - how in-process tools are authored
- [MCP Servers](mcp-servers.md) - sibling tab for the same shape of data, externally routed
- [Observability Architecture → Tool and MCP observability](../../../observability-architecture.md#tool-and-mcp-observability-the-agentic-focus) - `McpToolObservationFilter` injection mechanism
