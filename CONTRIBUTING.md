# Contributing to Spring AI Playground

Thanks for your interest in the project. Spring AI Playground is maintainer-driven. The README section [Contributing & Scope](README.md#contributing--scope) decides what is in scope and what is not. This page explains how to contribute within that scope, and how the planned local tool contributions will work.

## How to contribute today

- **Report a bug.** Use the **Bug Report** issue template: what went wrong, the steps to reproduce it, and the version and environment (app version, how you run it, OS).
- **Report a docs problem.** Use the **Documentation Issue** template for an error, missing information or an unclear explanation you cannot fix yourself. Give the page URL.
- **Fix the docs.** Open a PR against `docs/` for a targeted fix: a typo, a wrong statement, a broken link. Check commands, paths and version numbers against the current code. Open a Discussion first for README changes or new sections.
- **Ask or share.** Use [GitHub Discussions](https://github.com/spring-ai-community/spring-ai-playground/discussions): Q&A for questions, Show and tell for tools, setups and examples you built. Questions are not Issues.

Anything else is out of scope for now, including code PRs and feature proposals; the full list is under Out of Scope in the README. Local tools are the one planned exception, described next.

## Planned: local tool contributions

This is an advance notice of our plan. We intend to open one kind of external contribution: **local tools**, the sandboxed JavaScript tools you build in Tool Studio. We are now designing and building what it needs: the acceptance rules, the CI that checks each contribution, and the review pipeline behind it. Until those are ready it is not open; we will open it in a later release. Please do not send tool PRs until this page says it is open.

### How it will work

1. **Build the tool in the app.** Write it yourself in Tool Studio, let an AI agent write it through the [management MCP tools](docs/features/mcp-server/management-tools.md), or do both together.
2. **Earn the Local Pass.** No pass, no PR. The app records what it checked: spec hash, computed risk level, test result and the hosts the tool called.
3. **Open the PR.** One tool per PR, a single Safe Tool Spec JSON file in a separate community folder with the app's record next to it. An agent may open the PR and be credited as a co-author; say which agent was involved.
4. **CI runs the Local Pass again.** CI does not trust the record alone. It re-runs the Local Pass in the same sandbox, recomputes the record and checks the rules below.
5. **An LLM reviews it.** Every PR that passes CI gets an LLM review: does the description match what the code does, where do the parameters go, does it duplicate a bundled tool. A flagged PR goes back to the author with the reasons.
6. **The maintainer approves.** A PR reaches the maintainer only after it passes both CI and the LLM review. The final review and merge stay with the maintainer.
7. **It ships with the app.** Accepted tools go out in the next release as a Community bundle, name-pinned by the integrity manifest and credited to the author. There is no runtime marketplace.

### Draft rules

These are the rules we are building the CI around. They may change before contributions open.

- A local tool only. Code changes, presets and MCP server catalog entries stay out of scope.
- One tool per PR, and nothing else in the diff.
- Risk level L3 at most: pure compute, network to named hosts or in the SSRF-guarded `strict` mode, or file read. No file write, no open network, no `*` hosts, no sandbox class changes, no destructive operations.
- Secrets only as `${ENV_VAR}` references, never a literal key.
- English metadata. Korean may appear only in test values and in input examples, including examples inside a parameter description or a code comment.
- Readable code. No `Java.type`, `eval`, `new Function`, dynamic import, minified or obfuscated code.
- The description says what the tool does. It never instructs the agent how to behave.
- Every required parameter has a test value, so the Local Pass can be re-run.
- Licensed under Apache-2.0, like the rest of the project.

### Prepare a tool today

You can already build a tool that would qualify:

- [Tutorial 1 - Author and Validate a Tool](docs/tutorials/1-author-tool.md) builds one by hand in Tool Studio and earns its Local Pass.
- [Tutorial 18 - Let an AI Author a Tool](docs/tutorials/18-ai-authors-a-tool.md) has an AI client write, test and publish one through the management tools.
- [Connecting an AI client](docs/features/mcp-server/management-tools.md#connecting-an-ai-client) registers Claude Code, Claude Desktop, Cursor, the ChatGPT desktop app (Codex) or another MCP client with the built-in server.
- [Safe Tool Specification](docs/safe-tool-specification.md) defines the JSON a contribution is made of.

Until contributions open, share tools you built in [Discussions > Show and tell](https://github.com/spring-ai-community/spring-ai-playground/discussions/categories/show-and-tell). When they open, this page gets the final rules and the submission steps, and the README notice changes to say so.
