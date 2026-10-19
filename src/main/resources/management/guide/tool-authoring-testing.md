# Testing, publishing and exposure

## The Local Pass

A tool is published only after it runs successfully once in the sandbox. That run uses the `testValue` of each
parameter, converted to the declared `type`. Give a `testValue` to every parameter the code needs: a parameter
without one is not passed (it is listed in `skippedParams`), and a pass then proves little.

`saip_tools_test` takes `name` (a saved draft) or `draft` (an unsaved definition: a nested object with the same
fields as `saip_tools_save_draft`, never a string holding JSON; a string is refused by schema validation). The result:

| Field | Meaning |
|---|---|
| `localPass` | true if the code ran without error |
| `result` | what the code returned, as text |
| `error` | the failure message when it failed |
| `console` | `console.log` output and sandbox notes |
| `riskLevel`, `elevated` | risk of the sandbox settings, `elevated` means above the baseline |
| `testedParams`, `skippedParams` | parameters that got a test value and those that did not |
| `hints` | what to change, based on the error |
| `next` | after a pass: the one call to make next (publish, or save first). Do not test a passing tool again. |

A tool that needs a secret keeps it in a static variable such as `${API_KEY}`. Its test can pass only after the
user has set that environment variable for the app. If the test says the variable is not set, stop and tell the
user; never move the secret into a parameter or the code to get a pass.

A failed test comes back as an error result with the same fields. Fix the code, `saip_tools_save_draft` again
(same name replaces the draft), test again.

## Approval

| Action | Person asked? |
|---|---|
| `saip_tools_save_draft` | no (only when the server is set to approve every write) |
| `saip_tools_test` | no, unless the sandbox is above the baseline: network OPEN or `*`, file write, destructive, or extra classes |
| `saip_tools_publish`, `saip_tools_delete`, `saip_tools_set_exposure` | yes |

An elevated test or publish is always asked, and refused when approval is turned off. Ask for the least the
tool needs, `saip_tools_check_risk` tells you what will happen.

## Publishing and exposure

- A draft is private. Publishing makes it a live tool of this app.
- Whether other MCP clients see a published tool depends on the exposure settings. `saip_tools_get_exposure`
  shows them. New tools are exposed automatically only when `autoAdd` is on.
- The publish result field `exposed` says what happened. To change it use `saip_tools_set_exposure`
  (a person approves).
