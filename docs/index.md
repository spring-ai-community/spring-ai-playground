Description: Spring AI Playground is a cross-platform desktop app for AI agent tools with a desktop launcher, Tool Studio, MCP, Agentic Chat, RAG, and safe local execution.

# Spring AI Playground

## Safe Local Execution Layer for AI Agent Tools

Spring AI Playground is a cross-platform desktop app for building, testing, validating, and executing MCP tools in a controlled local environment.

> **No pass, no run.**

The desktop app is the recommended default experience, but Docker and local source execution are still supported when you want a server-style deployment or a development workflow.

Unlike many playgrounds that stop at prompt testing, this project connects AI conversations to real actions while making the tools it manages inside the app safer and easier to inspect before reuse:

- build JavaScript tools directly in the app
- test-run new or updated built-in tools before publishing them
- expose them immediately through MCP without restart or redeploy
- validate retrieval pipelines against your own documents
- run agentic chat that combines tool use and grounded context

<div style="text-align: center;">
  <b>Agentic Chat Demo</b><br/>
  Tool-enabled agentic AI built with Spring AI and MCP
</div>

<div style="text-align: center;">
  <a href="assets/images/agentic-chat-demo.gif">
    <img src="assets/images/agentic-chat-demo.gif" width="800" alt="Spring AI Playground Agentic Chat Demo"/>
  </a>
</div>

## :material-rocket-launch: Quick Start

The recommended default is the desktop app distributed from GitHub Releases.

Spring AI Playground is a standalone desktop app, so you can install it and start building MCP tools without setting up a Java project, Docker environment, or source build first.

### 1. Download the Desktop App

Choose the installer for your platform from the latest release:

[![Windows](https://img.shields.io/badge/Windows-NSIS%20Installer-0078D6?logo=windows&logoColor=white)](https://github.com/spring-ai-community/spring-ai-playground/releases/latest/download/Spring.AI.Playground.exe)
[![macOS Apple Silicon](https://img.shields.io/badge/macOS-Apple%20Silicon%20arm64-000000?logo=apple&logoColor=white)](https://github.com/spring-ai-community/spring-ai-playground/releases/latest/download/Spring.AI.Playground-arm64.dmg)
[![macOS Intel](https://img.shields.io/badge/macOS-Intel%20x64-555555?logo=apple&logoColor=white)](https://github.com/spring-ai-community/spring-ai-playground/releases/latest/download/Spring.AI.Playground-x64.dmg)
[![Linux DEB](https://img.shields.io/badge/Linux-DEB-A81D33?logo=debian&logoColor=white)](https://github.com/spring-ai-community/spring-ai-playground/releases/latest/download/Spring.AI.Playground.deb)
[![Linux RPM](https://img.shields.io/badge/Linux-RPM-EE0000?logo=redhat&logoColor=white)](https://github.com/spring-ai-community/spring-ai-playground/releases/latest/download/Spring.AI.Playground.rpm)

Or browse all available assets on the [Releases page](https://github.com/spring-ai-community/spring-ai-playground/releases).

### 2. Install and Launch

Install the package the same way you would for a normal desktop application, then launch **Spring AI Playground** from your applications menu.

The desktop app bundles the backend runtime together with a launcher that provides provider starter templates, YAML override editing, environment-variable based secret handling, and one-click launch.

If you install the app, you can run Spring AI Playground immediately without setting up Docker or running the server manually.

> **Install notes by platform**  
> Depending on your platform, the first install may include an OS security prompt for unsigned or not-yet-reputation-established builds.
>
> - macOS: copy the app into **Applications**, then use **System Settings > Privacy & Security > Open Anyway** if Gatekeeper blocks launch.
> - Windows: if Microsoft Defender SmartScreen warns that the app is unrecognized, click **More info** and then **Run anyway** only if you trust the release source.
> - Linux: the `.deb` and `.rpm` packages usually install without a separate unsigned-app override flow, but your distribution may still ask for normal package-install confirmation.
>
> For more detailed macOS Gatekeeper guidance, Windows SmartScreen notes, Linux package-install details, and first-launch configuration screens, see [Getting Started](getting-started.md).

<div style="text-align: center;">
  <b>First-Launch Configuration Screen</b><br/>
  Desktop launcher overview with the built-in config editor
</div>

<div style="text-align: center;">
  <a href="assets/images/launcher-openai.png">
    <img src="assets/images/launcher-openai.png" width="760" alt="Spring AI Playground first-launch configuration screen"/>
  </a>
</div>

<div style="text-align: center;">
  <b>Ollama Model Manager</b><br/>
  Review recommended models, search exact Ollama names, and manage downloaded models
</div>

<div style="text-align: center;">
  <a href="assets/images/lancher-ollama-config.png">
    <img src="assets/images/lancher-ollama-config.png" width="760" alt="Spring AI Playground Ollama model manager"/>
  </a>
</div>

### 3. Start with the Built-in Desktop Runtime

The desktop build is intended to be the easiest way to get started without setting up Docker or running the server manually.

### 4. Optional: Use Docker Instead

If you prefer container-based startup, run:

```bash
docker run -d -p 8282:8282 --name spring-ai-playground \
  -e SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -v spring-ai-playground:/home \
  --restart unless-stopped \
  ghcr.io/spring-ai-community/spring-ai-playground:latest
```

Then open `http://localhost:8282`.

## :material-view-grid-outline: What You Can Do

- [:material-robot-outline: AI Models](getting-started.md#model-configuration): switch between Ollama, OpenAI, and OpenAI-compatible runtime paths.
- [:material-tools: Tool Studio](features.md#tool-studio): build low-code tools in JavaScript and expose them instantly through MCP.
- [:material-connection: MCP Server](features.md#mcp-server): inspect external MCP servers and consume built-in MCP tools.
- [:material-database-search: RAG](features.md#vector-database): upload content, chunk it, embed it, index it, and validate retrieval quality.
- [:material-chat-processing: Agentic Chat](features.md#agentic-chat): combine grounded context, built-in tools, and explicitly trusted MCP connections in one interaction flow.

## :material-lightbulb-on-outline: Why This Project Exists

Spring AI Playground is intentionally positioned as a tool-first environment for building, testing, validating, and operationalizing MCP tools in a practical workflow.

Its current focus is:

- providing a UI-driven environment for building, testing, and validating MCP tools in a practical workflow
- making test-before-publish the default path for built-in local tool exposure
- testing tool execution flows, environment-backed tool configuration, and RAG integration in one place
- making tools easier to inspect, easier to test, and easier to operationalize before they are reused elsewhere
- supporting practical single-agent workflows through Agentic Chat with tools and grounded context. See [Agentic Chat Architecture Overview](features.md#agentic-chat-architecture-overview).
- promoting validated built-in tools into reusable MCP-hosted runtimes that can be shared across multiple MCP-compatible hosts and clients

It is intentionally opinionated and scope-limited in its current stage. The goal is a stable, reproducible platform for practical MCP tool work rather than a feature-complete agent orchestration product.

## :material-book-open-page-variant: Documentation Flow

- Getting Started: install the desktop app first, configure models, and understand alternative runtimes
- Features: understand the architecture and the main product areas
- Tutorials: follow real workflows for tools, MCP, vector search, and agentic chat

## Further Reading

- [Getting Started](getting-started.md): install the desktop app, configure models, and understand alternative runtimes
- [Features](features.md): understand the architecture and the main product areas
- [Tutorials](tutorials.md): follow end-to-end workflows for tools, MCP, vector search, and agentic chat
