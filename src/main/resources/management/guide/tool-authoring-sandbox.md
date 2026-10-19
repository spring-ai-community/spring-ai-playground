# Sandbox and risk levels

Tool code runs locked: no network, no files, no extra Java classes. The tool's `sandbox` asks for more. Every
request raises the risk level, and high levels need a person to approve. Ask for the least that works.

## This app's baseline

{{baseline}}

## Fields of `sandbox`

| Field | Effect |
|---|---|
| `networkMode` | BLOCKED (default), ALLOWLIST (only `hostsAllow`), STRICT (any public host), OPEN (any host) |
| `hostsAllow` | Exact host names for ALLOWLIST, for example `open.er-api.com`. Required with ALLOWLIST. |
| `fileRead`, `fileWrite` | Read files, or also write them in the tool workspace |
| `fsBasePath` | Absolute directory that file access is limited to |
| `destructive` | Declares that the tool changes or deletes things outside itself |
| `addAllowClasses` | Extra Java classes. Almost never needed. |

## Choosing the network mode

- The code calls a host you know (an API, a fixed site): ALLOWLIST with those exact names in `hostsAllow`.
  Include the hosts that redirects lead to.
- The host comes from the caller at run time (a URL parameter): STRICT. Private and local addresses stay blocked.
- Both are L3. ALLOWLIST is preferred because the person who approves the tool sees exactly where it can
  reach, so do not choose STRICT for a host you already know.
- OPEN and `hostsAllow` with `*` are L4 and need a person for every test and publish. Almost never needed.

## What sets the level

{{risk-table}}

The level is the highest of everything requested. `saip_tools_check_risk` gives the level and the reasons for
your exact settings, and cheaper alternatives.

## Approval

A test or publish is treated as high risk, and always asks a person, when the sandbox is above the baseline: risk
L4 or L5, or extra Java classes. With approval turned off such a request is refused. Tools that only need a fixed
list of hosts (L3) are published after a normal approval.
