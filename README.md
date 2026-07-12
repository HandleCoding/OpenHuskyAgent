<div align="center">

<img src="assets/logo/banner.png" alt="Husky" width="100%" />

# Husky

**Self-hosted agent runtime.** Define agents with tools, memory, skills, and policies — then expose them over TUI, HTTP SSE, or messaging channels.

[中文](README.zh-CN.md) · English

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)
![LangGraph4j](https://img.shields.io/badge/LangGraph4j-1.8.12-blue)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## Overview

Husky is an open-source **agent platform** written in Java. A single process runs a ReAct graph (model → tools → observe) and routes each request through a resolved **agent** + **runtime policy**.

| You want… | Use… |
|-----------|------|
| Local personal assistant | TUI + full-capability `assistant` agent |
| App / product backend | `POST /api/chat` (SSE) + a narrow `chatbot` agent |
| Team messaging bots | Feishu / Telegram / Slack instances bound to agents |

**LLM providers:** any OpenAI Chat Completions–compatible endpoint (OpenAI, DeepSeek, …), plus Anthropic Messages protocol via `llm.providers` / `LLM_PROVIDERS_*`. Outbound config uses `OPENAI_*` (and optional `llm.providers.*`); that is **not** an inbound OpenAI server API.

## Quick start (from source)

**Requirements:** JDK 17+, Git, an API key for an OpenAI-compatible chat model.

```bash
git clone https://github.com/HandleCoding/OpenHuskyAgent.git
cd OpenHuskyAgent
mkdir -p ~/.husky
cp .env.example ~/.husky/.env
# Edit ~/.husky/.env — at least OPENAI_API_KEY (and BASE_URL / MODEL if not OpenAI default)
```

```bash
./mvnw -B -ntp -DskipTests package
bin/husky serve
```

```bash
# another terminal
curl -s http://localhost:18088/actuator/health
bin/husky tui --server ws://localhost:18088/api/tui
```

`bin/husky` loads `~/.husky/.env` first; if missing, it falls back to the repo `.env`.

### Other install paths

| Path | When |
|------|------|
| [Linux installer](https://github.com/HandleCoding/OpenHuskyAgent/blob/main/install.sh) | VPS / one-shot deploy (`bash install.sh`, later `husky update`) |
| Homebrew | `brew tap HandleCoding/husky && brew install HandleCoding/husky/husky` then `husky init` / `husky serve` |

Background process (no systemd):

```bash
bin/husky start    # PID: ~/.husky/husky.pid , logs: ~/.husky/logs/husky-serve.log
bin/husky status
bin/husky logs
bin/husky stop
```

Dev (service + TUI): `bin/husky dev`.

## Runtime layout (`~/.husky`)

| Path | Purpose |
|------|---------|
| `.env` | Secrets, model endpoint, ports, feature flags, channel tokens |
| `config/application.yml` | Agents, channel instances, bindings (`HUSKY_CONFIG_FILE`) |
| `config/mcp-servers.json` | MCP servers (when `MCP_ENABLED=true`) |
| `db/` | SQLite sessions / checkpoints |
| `memory/` | File-backed memory (`MEMORY.md`, `USER.md`, …) |
| `skills/` | User / installed skills |
| `logs/` | Service logs when using `husky start` |

## Configuration

### Environment (`.env`)

| Variable | Default | Notes |
|----------|---------|--------|
| `OPENAI_API_KEY` | — | **Required** for model calls |
| `OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI-compatible **outbound** LLM base |
| `OPENAI_COMPLETIONS_PATH` | `/v1/chat/completions` | Completions path on that base |
| `OPENAI_MODEL` | `gpt-5.4` | Default model name for seeded `main` provider |
| `OPENAI_TEMPERATURE` | `0.7` | |
| `AUXILIARY_MODEL` | falls back to main | Summaries, compression, vision (OpenAI-compatible path) |
| `HUSKY_PORT` | `18088` | |
| `HUSKY_DATA_DIR` | `~/.husky` | |
| `HUSKY_CONFIG_FILE` | `$HUSKY_DATA_DIR/config/application.yml` | Agents / channels / bindings |
| `AUTH_ENABLED` | `true` | API key auth for `/api/chat` |
| `HUSKY_API_KEYS` | example / generated | Comma-separated keys; change before public deploy |
| `BROWSER_ENABLED` | `false` | Playwright tools |
| `MCP_ENABLED` | `false` | Load MCP servers |
| `WEB_BACKEND` | `auto` | `auto` / `brave` / `tavily` / `none` |

Optional: `LLM_PROVIDERS_MAIN_PROTOCOL=anthropic_messages` and related vars to run the main agent on Anthropic Messages wire (e.g. DeepSeek dual endpoint). See comments in `.env.example` patterns used in local deploys.

### Agents & channels (YAML)

Edit `$HUSKY_CONFIG_FILE` (or package defaults in `service/src/main/resources/application.yml`).

- **`agents.<id>`** — tools, approval, memory, model, rate limits, …
- **`channels.*.instances.*`** — Feishu / Telegram / Slack connection details  
- **`agent-channel-bindings.<agentId>`** — which channel instances that agent owns  

Capability lists are **fail-closed**: empty `[]` = none, `["*"]` = all, concrete ids = only those. Invalid ids fail **startup**.

Details: [docs/integrators.md](docs/integrators.md), [docs/agent_channel_binding_plan.md](docs/agent_channel_binding_plan.md).

## Use the platform

### TUI

```bash
bin/husky tui --server ws://localhost:18088/api/tui
```

Streaming, tool traces, approvals, multi-turn sessions over WebSocket JSON-RPC.

### HTTP API (Chatbot SSE)

Primary **inbound** HTTP surface for apps:

```bash
curl -N \
  -H "X-Api-Key: $HUSKY_API_KEY" \
  -H "X-User-Id: demo-user" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"Hello"}' \
  http://localhost:18088/api/chat
```

| Input | Required | Purpose |
|-------|----------|---------|
| `message` | yes | User text |
| `sessionId` | no | Continue a server session (omit first turn) |
| `X-Api-Key` | if auth on | Must match `HUSKY_API_KEYS` |
| `X-User-Id` | if auth on | Stable end-user id |
| `X-Agent` | no | Override agent when routing allows |

SSE event names include `token`, `reasoning`, `tool_started`, `tool_completed`, `tool_failed`, `done`, `error`.

### Messaging channels

| Channel | Enable (typical) | Notes |
|---------|------------------|--------|
| Feishu | `FEISHU_ASSISTANT_ENABLED=true` + app credentials | WebSocket or webhook instances |
| Telegram | `TELEGRAM_ASSISTANT_ENABLED=true` + bot token | One process per bot token |
| Slack | `SLACK_ASSISTANT_ENABLED=true` + bot/app tokens + bot user id | Socket Mode |

Bind each instance under `agent-channel-bindings`. Channel-specific options live in env and `channels.*` YAML.

## Architecture

```text
Transport → Channel → Instance → Agent → Runtime Scope → ReAct Graph
```

| Module | Role |
|--------|------|
| `service/` | Spring Boot, HTTP SSE, WebSocket, channel adapters, auth, Actuator |
| `application/` | Orchestration, session/policy resolution, queues, sub-agents, TUI RPC |
| `domain/` | ReAct graph, prompts, context compression, hooks, approvals |
| `infra/` | Tools, memory, MCP, browser, checkpoints, **LlmTransport** (OpenAI + Anthropic wires) |
| `client/` | Standalone TUI (no Spring) |

Dependency direction: `service → application → domain → infra`.

## Features (short)

- ReAct loop with streaming and tool calls  
- Parallel safe tools; approval queue for sensitive tools  
- Multi-agent tools via `delegate_task` (policy-capped)  
- Memory, skills, knowledge, MCP, browser/vision (optional)  
- Context compression + SQLite checkpoints  
- Per-agent rate limits and fail-closed allowlists  

## Development

```bash
./mvnw -B -ntp test                    # default suite (excludes live-api & browser)
./mvnw -B -ntp -DskipTests package
./mvnw -B -ntp test -pl domain         # one module
./mvnw -B -ntp test -pl service -Dtest=ToolRegistrationIntegrationTest

# Optional profiles
OPENAI_API_KEY=... ./mvnw -B -ntp test -P live-api-tests
./mvnw -B -ntp exec:java -pl infra -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
./mvnw -B -ntp test -P browser-tests
```

## Production notes

- Rotate `HUSKY_API_KEYS`; keep `AUTH_ENABLED=true` on public networks.  
- Do not expose full `assistant` toolsets on the internet; use a narrow agent (e.g. `chatbot`).  
- Set `TUI_WS_ALLOWED_ORIGINS` in production (avoid `*`).  
- Keep Actuator and TUI off the public internet without extra controls.  
- Do not commit `.env`, keys, DBs, or private MCP configs.  

Security: [SECURITY.md](SECURITY.md).

## Docs

| Doc | Content |
|-----|---------|
| [docs/integrators.md](docs/integrators.md) | Deploy as backend, agents, allowlists, SSE, checklist |
| [docs/agent_channel_binding_plan.md](docs/agent_channel_binding_plan.md) | Agents / channels / bindings model |
| [docs/agent-platform-roadmap.md](docs/agent-platform-roadmap.md) | Platform roadmap |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Do not include secrets or customer data in issues/PRs.

## License

[MIT](LICENSE)
