# Frequent mistakes

| Symptom | Cause and fix |
|---|---|
| `fetch is not defined` | The sandbox blocks the network. Set `sandbox.networkMode` to ALLOWLIST and list the hosts in `sandbox.hostsAllow`. |
| `host not in allowlist: x` | Add exactly that host to `hostsAllow`. Redirects to other hosts need to be listed too. |
| Asking for `networkMode` OPEN or host `*` | Risk L4 and a person must approve every test and publish. List the real hosts instead (L3). |
| STRICT for a host the code already names | Same level as ALLOWLIST (L3) but the approver cannot see where the tool reaches. Use ALLOWLIST with the host in `hostsAllow`; keep STRICT for hosts that arrive as a parameter. |
| A parameter for a key that is already a `${ENV_NAME}` static variable | Drop the parameter. The static variable is the global the code reads, and callers never pass secrets. |
| `input validation failed ... /draft: string found, object expected` | `draft` of `saip_tools_test` is a nested object with the fields of `saip_tools_save_draft`. Do not wrap it in a string holding JSON. |
| `x is not defined` | The name is not declared. Parameters and static variables are globals only with exactly the declared name. |
| A parameter is `undefined` in the test | It has no `testValue`, see `skippedParams`. |
| The test passes but returns nothing | The code has no `return` at the end. |
| Test fails with `env var not set: ${NAME}` | The environment variable is missing on the machine that runs this app. Do not turn the secret into a parameter or write it into the code, that leaks it. Stop and ask the user to set `NAME` for the app and restart it, then test again. |
| Secret written into `code` or `staticVariables` | Refused. Put it in an environment variable on the machine that runs the app and reference `${NAME}`. |
| `safety.fs` is undefined | The sandbox grants no file access. Use `fileRead` (L3), and `fileWrite` (L4) only if the tool must write. |
| Name refused | Letters, digits and `_`, starting with a letter, not `saip_...`, not used by another tool. |
| Timeout or limit exceeded | Less work per call, smaller responses, use `maxLength` on `fetch`. |
| Tool published but MCP clients do not see it | It is not exposed, see `saip_tools_get_exposure` and `saip_tools_set_exposure`. |
| Wanting to edit a published tool | Save the new version as a draft under another name, publish it, delete the old one. |
| Tool description is vague | The calling model decides from `description` and parameter descriptions. Say what it does, what each input means, what it returns. |
