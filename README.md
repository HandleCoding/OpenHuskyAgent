<div align="center">

<img src="assets/logo/banner.png" alt="Husky - Your AI Workforce" width="100%" />

# Husky — Open-Source Agent Runtime Platform

**Metadata-driven, compute/storage decoupled, and built for multiple scenarios. Deploy it as a personal assistant, an online chatbot backend, or an enterprise agent platform.**

**Compared with Claude Managed Agents: self-hosted, fully open source, and runtime policies are customizable.**

[中文](README.zh-CN.md) · English

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--M4-brightgreen)
![LangGraph4j](https://img.shields.io/badge/LangGraph4j-1.8.12-blue)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## What Is Husky?

Husky is a Java agent runtime built with Spring Boot, Spring AI, and LangGraph4j. It runs a ReAct loop with streaming output, tool calls, approvals, memory, knowledge retrieval, skills, MCP, browser automation, and multi-channel delivery.

The core idea is metadata-driven agent compute: the service starts once, then each request resolves its behavior from the request source, principal, channel, scene, session, visible tools, skills, memory policy, knowledge sources, MCP visibility, workspace/checkpoint storage policy, approval policy, and execution backend.

Husky can be used as:

| Use case | What you get |
|----------|--------------|
| Personal AI assistant | TUI, local files, terminal/process tools, browser tools, memory, approvals |
| Online chatbot backend | HTTP SSE `/api/chat`, API key auth, per-user sessions, scene-filtered tools |
| Enterprise assistant | Feishu, Telegram, and Slack multi-instance bots, channel bindings, knowledge sources, audit tags |
| Agent platform | Layered Java modules, tool providers, scenes, hooks, runtime policies, MCP |

## Quick Start

The goal is to get Husky working once in under a minute: install, start the service, verify health, then open the TUI.

### Step 1: Pick Your Install Path

#### Linux / VPS Quick Install

Use the installer when you want the fastest setup on a Linux host.

Safer install path:

```bash
curl -fsSLO https://raw.githubusercontent.com/HandleCoding/OpenHuskyAgent/main/install.sh
less install.sh
bash install.sh
```

Convenience shortcut for trusted environments:

```bash
curl -fsSL https://raw.githubusercontent.com/HandleCoding/OpenHuskyAgent/main/install.sh | bash
```

Useful options:

```bash
bash install.sh --non-interactive
bash install.sh --install-dir="$HOME/openHusky" --port=18088
bash install.sh --upgrade
husky update
```

The installer clones the repository into `~/openHusky` by default, installs JDK 17+ when needed, builds the service and TUI client JARs, writes runtime config to `~/.husky/.env`, creates the `~/.husky/config`, `~/.husky/skills`, `~/.husky/db`, `~/.husky/logs`, and `~/.husky/memory` directories, generates a random `HUSKY_API_KEYS` value, and can install a systemd service.

`bin/husky` prefers `~/.husky/.env` and only falls back to the repo-local `.env` when the user-level config file is missing.

#### macOS Quick Install (Homebrew)

For the smoothest macOS install path, use the official Homebrew tap.

```bash
brew tap HandleCoding/husky
brew install HandleCoding/husky/husky
husky init
husky serve
```

After `husky init`, edit `~/.husky/.env` and set `OPENAI_API_KEY` at minimum. Then choose one startup mode:

- Foreground: `husky serve`
- Background: `husky start`

To upgrade a Homebrew install later:

```bash
brew update
brew upgrade HandleCoding/husky/husky
```

Homebrew installs `openjdk@17` automatically, installs the `husky` launcher into your PATH, and keeps the runtime bundle under Homebrew-managed `libexec`.

#### macOS / Windows / Any Platform From Source

Use the source path when you are developing locally or not on Linux.

Requirements:

- JDK 17+
- Git
- An OpenAI-compatible chat model endpoint
- Optional: Brave or Tavily API key for web search
- Optional: Playwright Chromium for browser tools
- Optional: Docker for Docker execution backend experiments

```bash
git clone https://github.com/HandleCoding/OpenHuskyAgent.git
cd OpenHuskyAgent
mkdir -p ~/.husky
cp .env.example ~/.husky/.env
```

If you prefer a repo-local config for source-only work, `bin/husky` still falls back to `.env` when `~/.husky/.env` does not exist.

### Step 2: Set Minimal Configuration

Edit `~/.husky/.env` and set at least:

```bash
OPENAI_API_KEY=your-key
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL=gpt-5.4
```

`OPENAI_API_KEY` is the only strictly required value for a first run if the default base URL and model work for your provider.

### Step 3: Build And Start Husky

```bash
./mvnw -B -ntp clean install
bin/husky serve
```

Success signal: the service keeps running in the current terminal and starts listening on port `18088` unless you changed `HUSKY_PORT`.

For local development, `bin/husky dev` starts the service and TUI together, but `serve` + `tui` is the clearest first-run path.

### Step 4: Verify The Service

```bash
curl http://localhost:18088/actuator/health
```

Success signal: you should get JSON with `"status":"UP"`.

### Step 5: Open The TUI

In another terminal:

```bash
husky tui --server ws://localhost:18088/api/tui
```

Success signal: the TUI connects without errors and you can send a prompt immediately.

## `~/.husky` Layout

After installation, Husky keeps user-level config and runtime data under `~/.husky`:

| Path | Purpose |
|------|---------|
| `~/.husky/.env` | Main runtime configuration, including model settings, API keys, ports, and feature toggles |
| `~/.husky/config/` | User-managed configuration files such as `mcp-servers.json` |
| `~/.husky/skills/` | Installed or user-authored skills loaded from the Husky data directory |
| `~/.husky/db/` | Runtime SQLite data such as session state and checkpoints |
| `~/.husky/logs/` | Optional log output and writable runtime log directory |
| `~/.husky/memory/` | Persistent file-backed memory directory |
| `~/.husky/memory/MEMORY.md` | Shared persistent notes managed by `memory_*` tools |
| `~/.husky/memory/USER.md` | User profile memory managed by `user_*` tools |

Configuration lives in `~/.husky/.env` and `~/.husky/config/`; persistent memory content lives separately under `~/.husky/memory/`.

Memory strategy notes:

- `default` loads file-backed memory from `~/.husky/memory/` into prompts automatically.
- `manual-only` keeps `MEMORY.md` and `USER.md` editable through memory tools without auto-injecting them into prompts.
- `session-recall` uses session recall instead of persistent file-backed memory for prompt/session recall.

You can back up or move `~/.husky` independently from the checked-out repository in `~/openHusky`.

### Upgrade An Existing Install

The recommended local upgrade path depends on how you installed Husky.

For a Homebrew install on macOS:

```bash
brew update
brew upgrade HandleCoding/husky/husky
```

For a git checkout managed with `install.sh`, use:

```bash
husky update
```

`husky update` wraps `bash install.sh --upgrade`, refuses to run on a dirty checkout, and prints the active code/config/memory paths after the upgrade.

If you want the lower-level command explicitly, this still works:

```bash
bash install.sh --upgrade
```

## Minimal Configuration

Husky uses two user-editable runtime files after `husky init`:

- `.env` for secrets, model endpoints, feature flags, and channel credentials.
- `${HUSKY_DATA_DIR}/config/application.yml` for agents, channel instances, and agent-channel-bindings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `OPENAI_API_KEY` | empty | OpenAI-compatible API key; required for model calls |
| `OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI-compatible endpoint |
| `OPENAI_COMPLETIONS_PATH` | `/v1/chat/completions` | Chat completions path |
| `OPENAI_MODEL` | `gpt-5.4` | Main chat model |
| `OPENAI_TEMPERATURE` | `0.7` | Main model temperature |
| `AUXILIARY_*` | blank/main fallback | Optional model for summaries, compression, web summaries, and vision |
| `HUSKY_PORT` | `18088` | HTTP/WebSocket service port |
| `HUSKY_DATA_DIR` | `~/.husky` | Runtime data directory for DBs, skills, MCP config, and logs |
| `HUSKY_CONFIG_FILE` | `${HUSKY_DATA_DIR}/config/application.yml` | Runtime YAML for agents, channels, and bindings |
| `AUTH_ENABLED` | `true` | Enables API key auth for `/api/chat` |
| `HUSKY_API_KEYS` | generated/example | Comma-separated Chatbot API keys; replace before public deployment |
| `TUI_WS_ALLOWED_ORIGINS` | `*` | WebSocket origins; wildcard is local/dev only |
| `WEB_BACKEND` | `auto` | `auto`, `brave`, `tavily`, or `none` |
| `BRAVE_SEARCH_API_KEY` | empty | Enables Brave search |
| `TAVILY_API_KEY` | empty | Enables Tavily search |
| `PROXY_*` / `HUSKY_PROXY_URL` | env-driven | Shared outbound HTTP proxy settings |
| `WEB_PROXY_*` | empty | Web-specific proxy override |
| `BROWSER_ENABLED` | `false` | Enables Playwright browser tools |
| `MCP_ENABLED` | `false` | Enables MCP server loading |
| `MCP_CONFIG_PATH` | `${HUSKY_DATA_DIR}/config/mcp-servers.json` | MCP server config path |
| `SKILLHUB_API_KEY` | empty | Enables authenticated SkillHub operations |

Browser and MCP integrations are disabled by default. Enable them only when configured intentionally.

### Background Service Options

`husky serve` keeps the service in the current terminal. On macOS or other environments without `systemd`, use the lightweight background commands:

```bash
husky start
husky status
husky logs
husky stop
```

This mode writes its PID to `~/.husky/husky.pid` and logs to `~/.husky/logs/husky-serve.log`.

For long-running Linux server deployments, `systemd` is still the recommended path:

```bash
sudo systemctl start husky-agent
sudo systemctl status husky-agent
journalctl -u husky-agent -f
```

## Use Husky

### TUI Personal Assistant

```bash
husky tui --server ws://localhost:18088/api/tui
```

The TUI uses WebSocket JSON-RPC and supports streaming text, tool-call display, approval prompts, and session interaction.

### Chatbot SSE API

```bash
curl -N \
  -H 'X-Api-Key: <your-api-key>' \
  -H 'X-User-Id: demo-user' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"Search the latest Spring AI docs"}' \
  http://localhost:18088/api/chat
```

Request fields:

| Field | Required | Purpose |
|-------|----------|---------|
| `message` | yes | User input |
| `sessionId` | no | Existing server-issued session id; omit on first request |

Headers:

| Header | Required | Purpose |
|--------|----------|---------|
| `X-Api-Key` | yes when auth is enabled | Chatbot API authentication |
| `X-User-Id` | yes | Stable end-user identity for session ownership |
| `X-Agent` | no | Explicit agent id for HTTP chatbot requests |

SSE events include `token`, `reasoning`, `message`, `tool_started`, `tool_completed`, `tool_failed`, `done`, and `error`.

### Feishu Channel

Husky supports Feishu WebSocket/webhook adapters with multiple app instances under `channels.feishu.instances.*`. Bind instances to agents through `agent-channel-bindings.*`, allowing different bots/accounts to expose different prompts, tools, memory, and approval policies.

### Telegram Channel

Husky supports a disabled-by-default Telegram long-polling adapter with multiple bot instances under `channels.telegram.instances.*`. Configure `TELEGRAM_ASSISTANT_ENABLED=true`, `TELEGRAM_ASSISTANT_BOT_TOKEN`, and preferably `TELEGRAM_ASSISTANT_BOT_USERNAME` so `agent-channel-bindings.*` can route the bot to the intended agent.

Telegram v1 supports text messages, group mention gating, forum topic threads, typing indicators, inline approval buttons, and clarification buttons/replies. Long polling is single-consumer per bot token, so run only one Husky process per enabled Telegram token.

### Slack Channel

Husky supports a disabled-by-default Slack Socket Mode adapter with multiple bot instances under `channels.slack.instances.*`. Configure `SLACK_ASSISTANT_ENABLED=true`, `SLACK_ASSISTANT_BOT_TOKEN`, `SLACK_ASSISTANT_APP_TOKEN`, and `SLACK_ASSISTANT_BOT_USER_ID` so `agent-channel-bindings.*` can route the bot to the intended agent. When Slack is enabled, the bot user id is required because Socket Mode routing fails closed instead of silently falling back to a global default.

Slack v1 supports text messages, DM and channel/thread routing, channel mention gating, threaded replies, Block Kit approval buttons, and clarification buttons/replies. Create a Slack app with Socket Mode enabled, an app-level token with `connections:write`, bot scopes such as `app_mentions:read`, `chat:write`, `im:history`, and the channel/group history scopes you enable, event subscriptions for `app_mention`, `message.im`, and optional channel/group message events, plus Interactivity enabled for Block Kit callbacks. To DM the bot, enable App Home -> Messages Tab and allow users to send messages from that tab. Invite the bot to private channels before testing there; `SLACK_ASSISTANT_SEND_TYPING_STATUS=false` by default because Slack normal messages do not have a true typing indicator API.

## Capabilities

- **ReAct graph runtime** — LangGraph4j drives model -> tool -> observation loops with interrupt/resume approvals.
- **Streaming by channel** — TUI WebSocket, HTTP SSE, and Feishu adapters render the same runtime events differently.
- **Agent runtime policy** — agents control prompts, toolsets, exact allow/deny lists, MCP servers, knowledge sources, skills, approval, backend, working directory, memory, audit, and rate limits.
- **Built-in tools** — file read/write/edit/delete/move, apply patch, file search/listing, terminal/process, todo, web search/fetch, browser, memory, knowledge, skills, delegate, MCP, and vision tools.
- **Parallel tool execution** — safe tools from the same model turn can run concurrently; approval-required tools are routed through a serial confirmation queue.
- **Memory and checkpoints** — SQLite-backed sessions, graph checkpoints, memory tools, context compression, and model-context-length policies.
- **Knowledge layer** — agent-scoped `knowledge_search` and `knowledge_fetch` over configured local knowledge sources.
- **Skill system** — built-in skills, user-installed skills, SkillHub search/install, and progressive `skill_list` / `skill_view` loading.
- **MCP integration** — stdio, SSE, and Streamable-HTTP MCP servers with agent-level visibility controls.
- **Browser and vision** — Playwright browser automation and local/remote image analysis when enabled.
- **Sub-agent delegation** — `delegate_task` can run child agents for isolated or parallel work.
- **Observability** — audit logs, metrics, session stats, redaction, and `/actuator/husky`.

## Architecture

Husky uses a layered Java architecture:

| Module | Responsibility |
|--------|----------------|
| `service/` | Spring Boot entry point, HTTP SSE, TUI WebSocket, Feishu adapters, auth, Actuator |
| `application/` | Agent orchestration, channel adapters, agent/session resolution, runtime queues, JSON-RPC methods, sub-agent runner |
| `domain/` | ReAct graph, graph state, approvals, prompt builder, context manager, sessions, hooks, channel events |
| `infra/` | Tools, memory, knowledge, MCP, browser, workspace, checkpoint store, AI clients, execution backends, config, observability |
| `client/` | Independent terminal TUI client with no Spring dependency |

Dependency direction is intentionally one-way: `service -> application -> domain -> infra`. The `client` module is separate and talks to the service through WebSocket JSON-RPC.

Runtime behavior follows this model:

```text
Transport -> Channel -> Channel Instance -> Agent -> Runtime Scope -> ReAct Graph
```

A request resolves its principal, channel identity, concrete account/bot binding, agent config, and session id. The resulting runtime scope controls prompt sections, visible tools, skills, memory, knowledge sources, MCP servers, approval mode, execution backend, workspace/checkpoint storage, audit tags, and graph cache keys.

This keeps Husky usable as a local personal assistant while allowing enterprise deployments to replace memory, knowledge, file/workspace, MCP, and checkpoint providers with remote implementations.

## Configuration Reference

Packaged defaults live in `service/src/main/resources/application.yml`. Installed/runtime deployments should edit `${HUSKY_DATA_DIR}/config/application.yml`, which is loaded as an external Spring config override by `bin/husky`.

| Area | Important keys |
|------|----------------|
| LLM | `spring.ai.openai.*`, `agent.auxiliary.*` |
| Agent loop | `agent.graph.max-react-loops`, `agent.llm.*`, `agent.tool.*`, `agent.checkpoint.enabled` |
| Context | `context.threshold-percent`, `context.context-length`, `context.model-context-lengths`, `context.tail-token-budget` |
| Channels | `channels.feishu.instances.*`, `channels.telegram.instances.*`, `channels.slack.instances.*`, `tui.ws.*`, `chatbot.enabled` |
| Agents and bindings | `agents.*`, `agent-channel-bindings.*`, `toolsets`, `allowed-tools`, `denied-tools`, `approval`, `backend`, `working-dir`, `memory`, `storage` |
| Execution | `execution.backend.docker.*`, `execution.backend.idle-ttl-seconds` |
| Web | `web.backend`, `web.proxy.*`, `BRAVE_SEARCH_API_KEY`, `TAVILY_API_KEY` |
| Browser | `browser.enabled`, `browser.headless`, `browser.timeout-seconds`, `browser.allow-private-network` |
| MCP | `mcp.enabled`, `mcp.config-path`, agent `allowed-mcp-servers` / `denied-mcp-servers` |
| Knowledge | `knowledge.enabled`, `knowledge.local-sources`, limits for snippets/documents/depth |
| Skills | `skill.builtin-dir`, `skill.dir`, `skill.managed-dirs`, `skillhub.*` |
| Auth | `auth.enabled`, `auth.api-keys` |
| Observability | `management.endpoints.web.exposure.include`, `husky.observability.*` |

Storage defaults to local. Non-local workspace/checkpoint providers are extension points; unsupported remote types fail fast instead of silently falling back to local behavior.

## Security And Production Hardening

Before exposing Husky beyond local development:

- Replace `HUSKY_API_KEYS` with strong random values.
- Keep `AUTH_ENABLED=true` for public `/api/chat` endpoints.
- Set `TUI_WS_ALLOWED_ORIGINS` to trusted origins; do not expose the TUI WebSocket with wildcard `*`.
- Treat `approval: none` as no-approval mode; do not combine it with dangerous toolsets in online agents.
- Restrict terminal, process, file mutation/search, browser automation, `skill_install`, `skill_manage`, and dangerous MCP tools in internet-facing agents.
- Use `allowed-mcp-servers`, `denied-mcp-servers`, `allowed-tools`, and `denied-tools` to scope MCP and tool visibility.
- Keep Actuator endpoints on a trusted network or behind authenticated reverse proxy access.
- Do not commit `.env`, local databases, private MCP configs, API keys, logs, or generated runtime data.

Report vulnerabilities privately. See `SECURITY.md`.

## Development

```bash
# Default tests; excludes live API and browser groups
./mvnw -B -ntp test

# Build packages without tests
./mvnw -B -ntp -DskipTests package

# Module tests
./mvnw -B -ntp test -pl infra
./mvnw -B -ntp test -pl domain
./mvnw -B -ntp test -pl application
./mvnw -B -ntp test -pl service
./mvnw -B -ntp test -pl client

# Single test class
./mvnw -B -ntp test -Dtest=ToolRegistrationIntegrationTest -pl service

# Explicit live API compatibility tests
OPENAI_API_KEY=... ./mvnw -B -ntp test -P live-api-tests

# Explicit browser tests
./mvnw -B -ntp exec:java -pl infra -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
./mvnw -B -ntp test -P browser-tests
```

The installer uses `-DskipTests` for speed. Contributors should run the default test command before opening PRs.

## Contributing

Please read:

- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`

Issues and pull requests should avoid secrets, private prompts, local `.env` content, and customer data.

## License

Husky is released under the MIT License. See `LICENSE`.
