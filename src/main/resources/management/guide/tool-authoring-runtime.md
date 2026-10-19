# Tool code: what it can use

The code is the body of an async function, written in modern JavaScript (ECMAScript 2024). There is no
`require`, `import` or DOM.

## Inputs and result

- Every parameter and static variable is a global with exactly that name. An optional parameter that was not
  passed is `null` or `undefined`, so check it.
- `return` the result. Return an object with named fields, or a string. Throw `new Error('message')` for bad
  input, the message reaches the caller.
- `await` works at the top level. `console.log` output appears as `console` in the test result.

Runnable example, no network. Its `params` line is what `saip_tools_test` would take from the `testValue` fields:

```js
// params: {"amount": 80, "percent": 15}
if (amount == null) throw new Error('amount required');
const rate = percent == null ? 10 : Number(percent);
const tip = Math.round(Number(amount) * rate) / 100;
return { tip: tip, total: Number(amount) + tip };
```

## Helpers

| Helper | Use |
|---|---|
| `fetch(url, init)` | HTTP. Exists only when the sandbox allows the network. Returns `{ ok, status, statusText, headers.get(name), text(), json() }`. `init` takes `method`, `headers`, `body` (string) and `maxLength` (bytes to keep). |
| `URL`, `URLSearchParams` | Build and read URLs. Use `encodeURIComponent` for path and query values. |
| `atob`, `btoa`, `crypto` | Base64, `crypto.randomUUID()`, `crypto.getRandomValues`, and `crypto.subtle.digest`, `sign`, `importKey` for hashes and HMAC (see the bundled tools `hash` and `hmac`). |
| `safety.parser.csv(text, { header: true })` | Rows as objects (with `header`) or arrays. |
| `safety.parser.html(text)` | A jsoup document: `select(css)`, `text()`, `attr(name)`. |
| `safety.parser.xml(text)`, `safety.parser.yaml(text)` | Parsed structures. |
| `safety.fs.readText(path)`, `list(dir)`, `exists`, `stat`, `grep` | Only when the sandbox grants `fileRead`. Writing needs `fileWrite`. |

Runnable example with a parser:

```js
// params: {"csv": "item,qty\nnut,3\nbolt,5"}
const rows = safety.parser.csv(csv, { header: true });
let total = 0;
for (const row of rows) total += Number(row.qty);
return { rows: rows.length, totalQty: total };
```

A network call looks like this (needs `networkMode` ALLOWLIST with the host in `hostsAllow`):

```js-net
if (base == null || base === '') throw new Error('base required');
const response = await fetch('https://open.er-api.com/v6/latest/' + encodeURIComponent(base), { maxLength: 200000 });
if (!response.ok) return { success: false, status: response.status };
const data = response.json();
return { base: base, rates: data.rates };
```

## Limits

- Each run has a time limit and a statement budget; long loops and huge downloads fail. Keep results small.
- `fetch` refuses hosts outside `hostsAllow` and private addresses.
- Java classes are denied by default. Do not use `Java.type`, the helpers above cover almost everything.
