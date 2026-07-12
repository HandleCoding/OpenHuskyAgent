# 集成指南 — 把 Husky 当作 Agent 后端部署

面向希望 **自托管运行 Husky**，并在自己的 AI 产品（Bot、内部工具、SaaS 功能）里调用它的团队。

安装与首次启动见根目录 [README.zh-CN.md](../README.zh-CN.md)。

## 你能得到什么

| 入口 | 路径 | 适用场景 |
|------|------|----------|
| Chatbot SSE | `POST /api/chat` | 自建流式 UI / 应用后端（**一等 HTTP API**） |
| TUI WebSocket | `ws://…/api/tui` | 本地操作台 |
| Channel bots | 飞书 / Telegram / Slack | 终端用户消息产品 |

所有入口共用同一套 ReAct 运行时。行为由 **agent id**（以及 channel binding）决定。

## 配置文件

| 文件 | 作用 |
|------|------|
| `~/.husky/.env`（或 `HUSKY_DATA_DIR/.env`） | 密钥、模型端点、功能开关、渠道 token |
| `${HUSKY_CONFIG_FILE}`（默认 `~/.husky/config/application.yml`） | `agents.*`、`channels.*.instances.*`、`agent-channel-bindings.*` |
| 打包默认 | `service/src/main/resources/application.yml`（会被上面文件覆盖） |

面向 API 托管的最小 `.env`：

```bash
OPENAI_API_KEY=...
OPENAI_BASE_URL=https://api.openai.com   # 任意 OpenAI 兼容 *出站 LLM* 端点
OPENAI_MODEL=gpt-5.4
AUTH_ENABLED=true
HUSKY_API_KEYS=生成足够长的随机串
HUSKY_PORT=18088
```

说明：`OPENAI_*` 配置的是 **出站调用模型** 的端点；对外 **入站** HTTP 是下面的 Chatbot SSE，不是 OpenAI 协议。

## Agent / Channel / Binding

公开运行时配置是三层：

1. **`agents.<id>`** — agent 是谁、会什么工具、模型、审批、记忆、限流。
2. **`channels.<type>.instances.<name>`** — 渠道如何连接（token、bot id）。
3. **`agent-channel-bindings.<agentId>`** — 该 agent 绑定哪些实例。

细节见 [agent_channel_binding_plan.md](agent_channel_binding_plan.md)。

### Allowlist 语义（fail-closed）

适用于 `toolsets`、`skills`、`knowledge-sources`、`allowed-mcp-servers` 等资源列表：

| 配置值 | 含义 |
|--------|------|
| 省略 / `[]` | **无权限** |
| `["*"]` 或 `["all"]` | **全部**已注册资源 / 全部 toolset |
| 具体 id 列表 | 仅这些 id |

`denied-*` 为额外拒绝。denied 为空 = 无额外拒绝。

**启动校验：** 未知的 skill、knowledge source、MCP server、memory provider、toolset、LLM provider、非法 rate-limit、或 `working-dir: fixed` 却没有路径 → **进程启动失败**。上线前先修好 YAML。

**注意：** `memory.providers` **不支持** `*`。省略/空 = 全部已注册 memory provider。

### 示例：公网 API agent vs 全能力助手

```yaml
agents:
  # 本地全能力操作者（TUI）— 尽量不要直接暴露公网
  assistant:
    toolsets: ["*"]
    skills: ["*"]
    knowledge-sources: ["*"]
    allowed-mcp-servers: ["*"]
    approval: required

  # 给外部 HTTP 客户端用的收窄 agent
  chatbot:
    system-prompt: |
      你是产品助手。优先用搜索类工具，不要使用 shell。
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

### 每 agent 模型 / provider

```yaml
agents:
  researcher:
    model: deepseek-chat
    # 或：
    # model:
    #   provider: deepseek
    #   name: deepseek-chat
    #   temperature: 0.2
```

Provider 配置在 `llm.providers.*`（`main` 可由 `spring.ai.openai.*` / `OPENAI_*` 种子生成）。

### 限流

`rate-limit-enabled: true` 时，该 agent 的每个入站用户轮次消耗令牌桶配额。客户端应把限流响应当作背压，而不是模型错误。

### 匿名子代理（`delegate_task`）

全局默认：`agent.delegation.*`。按 agent 覆盖：`agents.<id>.delegation.*`（数值取更严；blocked toolsets 取并集）。工具参数受有效策略上限约束。

## Chatbot SSE API（一等 HTTP）

```bash
export HUSKY_URL=http://localhost:18088
export HUSKY_KEY=your-api-key

curl -N \
  -H "X-Api-Key: $HUSKY_KEY" \
  -H 'X-User-Id: app-user-1' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"你好"}' \
  "$HUSKY_URL/api/chat"
```

| 字段 / 头 | 是否必须 | 作用 |
|-----------|----------|------|
| `message` | 是 | 用户输入 |
| `sessionId` | 否 | 续聊服务端 session（首轮省略） |
| `X-Api-Key` | 鉴权开启时 | 与 `HUSKY_API_KEYS` 一致 |
| `X-User-Id` | 鉴权开启时 | 稳定终端用户身份 |
| `X-Agent` | 否 | 部署允许时指定 agent |

SSE 事件包括 `token`、`reasoning`、`message`、`tool_started`、`tool_completed`、`tool_failed`、`done`、`error`。

## 生产清单

- [ ] 使用强随机 `HUSKY_API_KEYS`，不要用示例值。
- [ ] 非本机网络保持 `AUTH_ENABLED=true`。
- [ ] 公网流量打到**能力收紧**的 agent（`chatbot` 风格），不要直接暴露全 toolset。
- [ ] 非必要关闭 Browser / MCP。
- [ ] 公网 agent 开启 rate limit。
- [ ] 反向代理 TLS；Actuator 与 TUI WebSocket 不要裸奔公网。
- [ ] 生产环境 `TUI_WS_ALLOWED_ORIGINS` 不要是 `*`。
- [ ] 为 `HUSKY_DATA_DIR` 准备持久盘（SQLite session/checkpoint、memory 文件）。
- [ ] 配置能干净启动：allowlist 写错会 fail-closed。
- [ ] 健康检查：`GET /actuator/health` 返回 `UP`。
