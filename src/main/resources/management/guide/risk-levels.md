# Risk levels of external MCP servers and tools

Levels run from L0 to L5. They are computed by the app, not declared by the server.

| Level | Meaning |
|---|---|
| L0 | Built-in trusted tool of this app |
| L1 | Local operations, no external access |
| L2 | External read-only, or API-key or bearer authentication |
| L3 | External write, network fetch, or community-curated trust |
| L4 | Admin scope, command execution or irreversible actions. Needs extra care |
| L5 | A floor rule tripped, or an unverified or unauthenticated server |

## Server risk

Four axes are added up and bucketed: transport (private LAN host adds 1), authentication (API key or bearer adds 1, custom OAuth adds 2), trust (a URL that is not in the catalog adds 1 or 2, a community-curated catalog entry adds 1) and documentation gaps (up to 2). Three floor rules force L5: a remote server without authentication that advertises write capability, a remote server without authentication that is not in the catalog, and OAuth scopes such as admin or write_all.

## Tool risk

Read from the tool annotations and description: not read-only adds 1, destructive adds 2, open world adds 1, idempotent subtracts 1, remote write or admin scope adds 1 or 2. Floor rules: an irreversible verb in the name (delete, drop, purge, wipe, remove, force_push) forces L5, destructive without idempotent forces L5, and a tool with neither description nor annotations is L4. Tools of catalog servers with a trust signal are not penalised for terse documentation.

## Human approval

Turning on human approval (HITL) for an exposed tool lowers its effective level by one band. The cap on what may be exposed is checked against the inherent level, so approval never lifts a tool above the cap.

## Description scan

Every tool description is scanned for injection patterns such as hidden instructions, role hijacking, invisible unicode, terminal escapes and exfiltration directives. A hit is reported by `saip_mcp_server_tools` and forfeits the lowered level. Treat a hit as a reason to leave the tool out.
