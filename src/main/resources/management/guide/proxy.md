# Exposing external MCP servers through this app

This app can re-publish tools of external MCP servers on its own MCP server. Every re-published tool gets a computed risk level, optional human approval (HITL), logging and secret masking. People register the external servers in the app. You choose which of their tools to expose, and with which approval rules.

## Procedure

1. `saip_mcp_list_servers`: see the registered servers, their connection status and risk level. Only connected servers can be exposed. If a server is not connected, tell the user what its `status` asks for (missing environment variables, OAuth authorization) and stop; a person has to fix it.
2. `saip_mcp_server_tools` with the server name: read each tool's description, parameters, risk (`inherentRisk` is what counts for the cap), the poisoning scan result and whether it is already exposed.
3. `saip_expose_get`: see what is exposed right now, the risk cap and the exposure mode.
4. `saip_expose_preview`: dry run of the change you plan. It lists what would be added, updated or removed and every reason for a refusal. Nothing is changed.
5. `saip_expose_add_tools`: apply it. A person is asked to approve the change with a summary of every tool, its risk and which ones have no human approval.
6. `saip_monitor_tool_calls`, `saip_monitor_risk_events` and `saip_monitor_hitl` show how the exposed tools are used.

## Choosing tools

- Combine several servers in one call: `selections` has one entry per server.
- `tools` is `"all"`, a list of tool names, or a list of `{name, alias, description, hitl}` objects for per-tool settings.
- The exposed name defaults to `<server>_<tool>`. Set `alias` to change it. Names must be 1 to 64 characters of letters, digits, `_` or `-`, must be unique, and must not start with `saip_`.
- Give a `description` only when the upstream text is missing or misleading. It replaces the upstream description for MCP clients.

## Human approval (HITL)

- With HITL on, every call to the exposed tool asks the person using the calling client to approve it. Clients that cannot ask are refused.
- `hitl.default` is `ALL` (every tool needs approval), `NONE` or `RISK` (only tools at or above `atLeast`). `hitl.except` lists tools that get the opposite of the default. If you leave `hitl` out, every tool needs approval.
- Turn approval off only for tools that are read-only and low risk. The change summary shown to the person lists every tool exposed without approval.

## Rules that can refuse a change

- Risk cap: a tool whose inherent risk is above `maxRisk` (default L5, or the value already set) is refused. HITL does not lift the cap.
- A tool whose definition changed since it was last approved is refused until you list it in `acknowledgeChanged`. Do that only after reading its current description with `saip_mcp_server_tools`; a changed description can carry hidden instructions.
- Duplicate or reserved names, servers that are not connected, and tools the server does not offer are refused. Nothing is applied when any part is refused.
- If the exposed set is managed by configuration (`spring.ai.playground.mcp-server.composed-tools`), it cannot be changed here.
- The exposure mode must include composed tools for proxied tools to be visible. `saip_expose_get` shows the mode; it is set in configuration.

## Secrets

Secrets never pass through this interface. If a server needs an API key, a person sets an environment variable on the machine that runs this app.
