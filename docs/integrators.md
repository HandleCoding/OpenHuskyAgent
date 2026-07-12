# Integrator Guide — Deploy Husky as an Agent Backend

This guide is for teams that want to **run Husky as a self-hosted service** and call it from their own AI products (bots, internal tools, SaaS features).

For install and first run, see the root [README.md](../README.md).

## What you get

| Surface | Path | When to use |
|---------|------|-------------|
| Chatbot SSE | `POST /api/chat` | First-party streaming UI / app backends (**primary HTTP API**) |
| TUI WebSocket | `ws://…/api/tui` | Local operator console |
| Channel bots | Feishu / Telegram / Slack | End-user messaging products |

All surfaces share the same ReAct runtime. Behavior is selected by **agent id** (and channel binding where applicable).

## Configuration files

| File | Role |
|------|------|
| `~/.husky/.env` (or `HUSKY_DATA_DIR/.env`) | Secrets, model endpoint, feature flags, channel tokens |
| `${HUSKY_CONFIG_FILE}` (default `~/.husky/config/application.yml`) | `agents.*`, `channels.*.instances.*`, `agent-channel-bindings.*` |
| Packaged defaults | `service/src/main/resources/application.yml` (overridden by the files above) |

Minimal `.env` for API hosting:

```bash
OPENAI_API_KEY=...
OPENAI_BASE_URL=https://api.openai.com   # or any OpenAI-compatible *LLM* endpoint
OPENAI_MODEL=gpt-5.4
AUTH_ENABLED=true
HUSKY_API_KEYS=generate-a-long-random-key
HUSKY_PORT=18088
```

Note: `OPENAI_*` configures the **outbound LLM** (model provider). The public **inbound** HTTP API is Chatbot SSE below—not an OpenAI wire protocol.

## Agent / Channel / Binding

Public runtime config is three layers:

1. **`agents.<id>`** — who the agent is, tools, model, approval, memory, limits.
2. **`channels.<type>.instances.<name>`** — how a channel connects (tokens, bot ids).
3. **`agent-channel-bindings.<agentId>`** — which instances that agent owns.

Details: [agent_channel_binding_plan.md](agent_channel_binding_plan.md).

### Allowlist semantics (fail-closed)

Applies to `toolsets`, `skills`, `knowledge-sources`, `allowed-mcp-servers` (and similar resource lists):

| Config value | Meaning |
|--------------|---------|
| omitted / `[]` | **None** (no access) |
| `["*"]` or `["all"]` | **All** registered / all toolsets |
| concrete ids | Only those ids |

`denied-*` lists are extra denials. Empty denied = no extra denials.

**Startup validation:** unknown concrete skill, knowledge source, MCP server, memory provider, toolset, LLM provider id, invalid rate-limit, or `working-dir: fixed` without a path **fails process boot**. Fix the YAML before deploying.

**Note:** `memory.providers` does **not** use `*`. Omit / empty = all registered memory providers.

### Example: public API agent vs full assistant

```yaml
agents:
  # Full local operator (TUI) — keep off the public internet if possible
  assistant:
    toolsets: ["*"]
    skills: ["*"]
    knowledge-sources: ["*"]
    allowed-mcp-servers: ["*"]
    approval: required

  # Narrow agent for external HTTP clients
  chatbot:
    system-prompt: |
      You are a helpful product assistant. Prefer search tools over shell access.
    toolsets:
      - CORE
      - SEARCH
      - WEB
    skills: []
    knowledge-sources: []
    allowed-mcp-servers: []
    approval: none
    rate-limit-enabled: true
    rate-limit-requests-per-minute: 30
    rate-limit-burst: 10

agent-channel-bindings:
  assistant:
    - tui:local
  chatbot:
    - http:chatbot
```

### Per-agent model / provider

Optional on each agent:

```yaml
agents:
  researcher:
    model: deepseek-chat                    # string form
    # or:
    # model:
    #   provider: deepseek                  # key under llm.providers.*
    #   name: deepseek-chat
    #   temperature: 0.2
```

Providers are configured under `llm.providers.*` (plus the seeded `main` provider from `spring.ai.openai.*` / `OPENAI_*`).

### Rate limits

When `rate-limit-enabled: true`, each inbound user turn consumes a token-bucket quota for that agent. Clients should treat rate-limit responses as backpressure, not as model failures.

### Anonymous sub-agents (`delegate_task`)

Global defaults: `agent.delegation.*`. Per-agent overrides: `agents.<id>.delegation.*` (stricter ceilings win; blocked toolsets are unioned). Tool call params (`max_steps`, `timeout_seconds`, `required_toolsets`) are capped by the effective policy.

## Chatbot SSE API (primary HTTP)

```bash
export HUSKY_URL=http://localhost:18088
export HUSKY_KEY=your-api-key

curl -N \
  -H "X-Api-Key: $HUSKY_KEY" \
  -H 'X-User-Id: app-user-1' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"Hello"}' \
  "$HUSKY_URL/api/chat"
```

| Field / header | Required | Purpose |
|----------------|----------|---------|
| `message` | yes | User input |
| `sessionId` | no | Continue a server-issued session (omit on first turn) |
| `X-Api-Key` | when auth on | Shared with `HUSKY_API_KEYS` |
| `X-User-Id` | when auth on | Stable end-user identity |
| `X-Agent` | no | Explicit agent id when routing allows it |

SSE events include `token`, `reasoning`, `message`, `tool_started`, `tool_completed`, `tool_failed`, `done`, and `error`.

## Production checklist

- [ ] Strong random `HUSKY_API_KEYS`; never ship the example key.
- [ ] `AUTH_ENABLED=true` for any non-local network.
- [ ] Public traffic hits a **narrow** agent (`chatbot`-style), not full local toolsets.
- [ ] Browser / MCP off unless required (`BROWSER_ENABLED`, `MCP_ENABLED`).
- [ ] Rate limits on public agents.
- [ ] Reverse proxy TLS; do not expose Actuator or TUI WebSocket to the open internet without origin and network controls.
- [ ] `TUI_WS_ALLOWED_ORIGINS` is not `*` in production.
- [ ] Persistent disk for `HUSKY_DATA_DIR` (SQLite sessions/checkpoints, memory files).
- [ ] Config boots cleanly: unknown allowlist ids fail at startup by design.
- [ ] Health: `GET /actuator/health` returns `UP`.

## Related docs

- [README.md](../README.md) — install, channels, architecture
- [agent_channel_binding_plan.md](agent_channel_binding_plan.md) — bindings
