<div align="center">

<img src="assets/logo/banner.png" alt="Husky" width="100%" />

# Husky

**自托管 Agent 运行时。** 用配置定义 agent（工具、记忆、技能、策略），通过 TUI、HTTP SSE 或消息渠道对外提供服务。

中文 · [English](README.md)

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)
![LangGraph4j](https://img.shields.io/badge/LangGraph4j-1.8.12-blue)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## 简介

Husky 是用 Java 写的开源 **Agent 平台**。单进程运行 ReAct 图（模型 → 工具 → 观察），每次请求解析 **Agent** 与 **Runtime Policy** 再执行。

| 你想… | 用法 |
|------|------|
| 本地个人助手 | TUI + 全能力 `assistant` agent |
| 产品 / 应用后端 | `POST /api/chat`（SSE）+ 能力收紧的 `chatbot` agent |
| 团队消息机器人 | 飞书 / Telegram / Slack 实例绑定到 agent |

**模型接入：** 任意 OpenAI Chat Completions 兼容端点（OpenAI、DeepSeek 等），以及通过 `llm.providers` / `LLM_PROVIDERS_*` 的 Anthropic Messages 协议。`OPENAI_*` 只配置 **出站** 调模型，**不是**入站 OpenAI 服务。

## 快速开始（源码）

**依赖：** JDK 17+、Git、一个 OpenAI 兼容聊天模型的 API Key。

```bash
git clone https://github.com/HandleCoding/OpenHuskyAgent.git
cd OpenHuskyAgent
mkdir -p ~/.husky
cp .env.example ~/.husky/.env
# 编辑 ~/.husky/.env — 至少 OPENAI_API_KEY（非默认端点时再改 BASE_URL / MODEL）
```

```bash
./mvnw -B -ntp -DskipTests package
bin/husky serve
```

```bash
# 另一个终端
curl -s http://localhost:18088/actuator/health
bin/husky tui --server ws://localhost:18088/api/tui
```

`bin/husky` 优先读 `~/.husky/.env`；不存在时回退到仓库内 `.env`。

### 其它安装方式

| 方式 | 场景 |
|------|------|
| [Linux 安装脚本](https://github.com/HandleCoding/OpenHuskyAgent/blob/main/install.sh) | VPS 一键部署（`bash install.sh`，之后 `husky update`） |
| Homebrew | `brew tap HandleCoding/husky && brew install HandleCoding/husky/husky`，再 `husky init` / `husky serve` |

后台运行（无 systemd）：

```bash
bin/husky start    # PID: ~/.husky/husky.pid ，日志: ~/.husky/logs/husky-serve.log
bin/husky status
bin/husky logs
bin/husky stop
```

本地联调：`bin/husky dev`（服务 + TUI）。

## 运行时目录（`~/.husky`）

| 路径 | 用途 |
|------|------|
| `.env` | 密钥、模型端点、端口、开关、渠道 token |
| `config/application.yml` | Agents、渠道实例、绑定（`HUSKY_CONFIG_FILE`） |
| `config/mcp-servers.json` | MCP（需 `MCP_ENABLED=true`） |
| `db/` | SQLite session / checkpoint |
| `memory/` | 文件记忆（`MEMORY.md`、`USER.md` 等） |
| `skills/` | 用户 / 安装的 skills |
| `logs/` | `husky start` 时的服务日志 |

## 配置

### 环境变量（`.env`）

| 变量 | 默认 | 说明 |
|------|------|------|
| `OPENAI_API_KEY` | — | 调模型 **必填** |
| `OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI 兼容 **出站** base |
| `OPENAI_COMPLETIONS_PATH` | `/v1/chat/completions` | 相对 path |
| `OPENAI_MODEL` | `gpt-5.4` | 种子 `main` provider 默认模型名 |
| `OPENAI_TEMPERATURE` | `0.7` | |
| `AUXILIARY_MODEL` | 回落主模型 | 摘要、压缩、vision（OpenAI 兼容路径） |
| `HUSKY_PORT` | `18088` | |
| `HUSKY_DATA_DIR` | `~/.husky` | |
| `HUSKY_CONFIG_FILE` | `$HUSKY_DATA_DIR/config/application.yml` | agents / channels / bindings |
| `AUTH_ENABLED` | `true` | `/api/chat` API Key 鉴权 |
| `HUSKY_API_KEYS` | 示例 / 生成 | 逗号分隔；公网务必更换 |
| `BROWSER_ENABLED` | `false` | Playwright 工具 |
| `MCP_ENABLED` | `false` | 加载 MCP |
| `WEB_BACKEND` | `auto` | `auto` / `brave` / `tavily` / `none` |

可选：主 agent 走 Anthropic Messages（如 DeepSeek 双端点）时用 `LLM_PROVIDERS_MAIN_PROTOCOL=anthropic_messages` 等变量。

### Agents 与渠道（YAML）

编辑 `$HUSKY_CONFIG_FILE`（或看打包默认 `service/src/main/resources/application.yml`）。

- **`agents.<id>`** — 工具、审批、记忆、模型、限流等  
- **`channels.*.instances.*`** — 飞书 / Telegram / Slack 连接信息  
- **`agent-channel-bindings.<agentId>`** — agent 绑定哪些实例  

能力列表 **fail-closed**：空 `[]` = 无权限，`["*"]` = 全部，具体 id = 仅这些。非法 id 会在 **启动时** 失败。

详见：[docs/integrators.zh-CN.md](docs/integrators.zh-CN.md)、[docs/agent_channel_binding_plan.md](docs/agent_channel_binding_plan.md)。

## 使用

### TUI

```bash
bin/husky tui --server ws://localhost:18088/api/tui
```

流式输出、工具轨迹、审批、多轮会话（WebSocket JSON-RPC）。

### HTTP API（Chatbot SSE）

应用侧 **入站** 一等 HTTP：

```bash
curl -N \
  -H "X-Api-Key: $HUSKY_API_KEY" \
  -H "X-User-Id: demo-user" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"你好"}' \
  http://localhost:18088/api/chat
```

| 输入 | 必填 | 说明 |
|------|------|------|
| `message` | 是 | 用户文本 |
| `sessionId` | 否 | 续聊（首轮省略） |
| `X-Api-Key` | 鉴权开时 | 对应 `HUSKY_API_KEYS` |
| `X-User-Id` | 鉴权开时 | 稳定用户 id |
| `X-Agent` | 否 | 路由允许时指定 agent |

SSE 事件名：`token`、`reasoning`、`tool_started`、`tool_completed`、`tool_failed`、`done`、`error` 等。

### 消息渠道

| 渠道 | 典型开关 | 说明 |
|------|----------|------|
| 飞书 | `FEISHU_ASSISTANT_ENABLED=true` + 应用凭证 | WebSocket 或 webhook 实例 |
| Telegram | `TELEGRAM_ASSISTANT_ENABLED=true` + bot token | 每个 token 只跑一个进程 |
| Slack | `SLACK_ASSISTANT_ENABLED=true` + bot/app token + bot user id | Socket Mode |

实例在 `agent-channel-bindings` 中绑定；细项见 env 与 `channels.*` YAML。

## 架构

```text
Transport → Channel → Instance → Agent → Runtime Scope → ReAct Graph
```

| 模块 | 职责 |
|------|------|
| `service/` | Spring Boot、HTTP SSE、WebSocket、渠道适配、鉴权、Actuator |
| `application/` | 编排、session/policy、队列、子 agent、TUI RPC |
| `domain/` | ReAct 图、prompt、上下文压缩、hooks、审批 |
| `infra/` | 工具、记忆、MCP、浏览器、checkpoint、**LlmTransport**（OpenAI / Anthropic 协议） |
| `client/` | 独立 TUI（无 Spring） |

依赖方向：`service → application → domain → infra`。

## 能力摘要

- ReAct 循环、流式输出、工具调用  
- 安全工具并行；敏感工具审批队列  
- `delegate_task` 匿名子 agent（策略封顶）  
- 记忆、skills、知识库、MCP、浏览器/视觉（可选）  
- 上下文压缩 + SQLite checkpoint  
- 按 agent 限流与 fail-closed allowlist  

## 开发

```bash
./mvnw -B -ntp test                    # 默认测试（排除 live-api、browser）
./mvnw -B -ntp -DskipTests package
./mvnw -B -ntp test -pl domain
./mvnw -B -ntp test -pl service -Dtest=ToolRegistrationIntegrationTest

OPENAI_API_KEY=... ./mvnw -B -ntp test -P live-api-tests
./mvnw -B -ntp exec:java -pl infra -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
./mvnw -B -ntp test -P browser-tests
```

## 生产注意

- 更换强随机 `HUSKY_API_KEYS`；公网保持 `AUTH_ENABLED=true`。  
- 公网不要暴露全能力 `assistant`，用收窄 agent（如 `chatbot`）。  
- 生产设置 `TUI_WS_ALLOWED_ORIGINS`（避免 `*`）。  
- Actuator / TUI 不要裸奔公网。  
- 勿提交 `.env`、密钥、数据库、私有 MCP 配置。  

安全披露见 [SECURITY.md](SECURITY.md)。

## 文档

| 文档 | 内容 |
|------|------|
| [docs/integrators.zh-CN.md](docs/integrators.zh-CN.md) | 后端部署、agent、allowlist、SSE、清单 |
| [docs/agent_channel_binding_plan.md](docs/agent_channel_binding_plan.md) | agents / channels / bindings |
| [docs/agent-platform-roadmap.md](docs/agent-platform-roadmap.md) | 平台路线图 |

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)、[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。Issue/PR 中不要包含密钥或客户数据。

## 许可证

[MIT](LICENSE)
