# Making a tool for this app

A tool is a JavaScript action plus a spec (name, description, parameters, sandbox). It runs in a locked
sandbox inside this app and, once published and exposed, other MCP clients can call it.

## Procedure

1. **Look for a similar tool first.** `saip_tools_list` (scope BUILTIN or ALL, with a query) shows what exists.
   The catalog of tested examples is in `saip://guide/tool-authoring/examples`. Read one with
   `saip://examples/{name}` or `saip_tools_get`, copy it and change the name. Copying beats writing from scratch.
2. **Choose the smallest sandbox that works.** Start locked (no network, no files). Add only what the code needs.
   `saip_tools_check_risk` shows the risk level and the reasons before you save anything. See
   `saip://guide/tool-authoring/sandbox`.
3. **Save a draft.** `saip_tools_save_draft` stores it privately. Saving again with the same name replaces the draft.
4. **Test it.** `saip_tools_test` with the draft name runs the code once with each parameter's `testValue`.
   Read `error`, `console` and `hints`, fix the code, save again, test again. See
   `saip://guide/tool-authoring/testing`.
5. **Publish.** `saip_tools_publish` tests again and publishes only if that passes. A person approves it.
6. **Exposure is separate.** Publishing does not change what the built-in MCP server offers. The publish result
   says whether the tool is `exposed`. To change it use `saip_tools_set_exposure`.

## The spec

| Field | Rule |
|---|---|
| `name` | Letters, digits and `_`, starting with a letter, no spaces. Never start with `saip_`. Must be new. |
| `description` | For the model that will call the tool: what it does, when to use it, what it returns. |
| `params` | One entry per input. `name` must be a plain identifier, it becomes a global in the code. Set `type`, `required` and a `description` with an example value. Give every parameter a `testValue`. |
| `staticVariables` | Fixed values, each with a `name` and a `value`, also globals. Secrets must be `${ENV_NAME}` references, never the secret. A secret held here needs no parameter of its own: callers never pass secrets. |
| `code` | JavaScript body, see `saip://guide/tool-authoring/runtime`. |
| `category`, `tags` | Optional. Category ids: TEXT, DATA, DATETIME, MATH, ENCODING, CRYPTO, SECURITY, FILE, WEB, PRODUCTIVITY, MESSAGING, AI, CUSTOM. |
| `sandbox` | Omit it for the locked default. For network calls to hosts you know use ALLOWLIST with `hostsAllow`; STRICT only when the host comes from the caller. |
| `humanApproval` | Whether a person approves every call. Omit it: the default is on for any risk level above L0 and off at L0. Set true at L0 for tools that send or change things. Setting false above L0 removes a safeguard, do it only when the user asked for it. |

Changing an already published tool is not done here: save the new version under another name, publish it, then
`saip_tools_delete` the old one.

## More

- `saip://guide/tool-authoring/runtime` - what the code can use and how it returns results
- `saip://guide/tool-authoring/sandbox` - capabilities, this app's baseline and the risk levels
- `saip://guide/tool-authoring/testing` - the Local Pass, publishing and exposure
- `saip://guide/tool-authoring/mistakes` - frequent failures and their fix
- `saip://guide/tool-authoring/examples` - tested tools to copy
