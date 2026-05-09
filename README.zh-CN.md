<div align="center">

<img src="assets/logo/banner.png" alt="Husky - Your AI Workforce" width="100%" />

# Husky — 开源 Agent 运行时平台

**元数据驱动、存算分离、多场景支持。可作为个人助手、在线聊天机器人后端、或企业 Agent 平台部署。**

**与 Claude Managed Agents 相比：自托管、完全开源、可定制运行时策略。**

中文 · [English](README.md)

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--M4-brightgreen)
![LangGraph4j](https://img.shields.io/badge/LangGraph4j-1.8.12-blue)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## Husky 是什么？

Husky 是一个基于 Java、Spring Boot、Spring AI 和 LangGraph4j 构建的 Agent Runtime。它提供 ReAct 循环、流式输出、工具调用、审批、记忆、知识检索、技能、MCP、浏览器自动化和多 Channel 交付能力。

Husky 的核心思想是元数据驱动的通用 Agent 计算：服务启动一次，每次请求根据来源、用户身份、Channel、Scene、Session、可见工具、Skill、Memory Policy、Knowledge Source、MCP 可见性、Workspace/Checkpoint Storage Policy、审批策略和执行后端来决定本次运行行为。

Husky 可以用于：

| 场景 | Husky 提供什么 |
|------|----------------|
| 个人 AI 助手 | TUI、本地文件、终端/进程工具、浏览器工具、记忆、审批 |
| 在线 Chatbot 后端 | HTTP SSE `/api/chat`、API Key 鉴权、多用户 session、scene 过滤工具 |
| 企业助手 | 飞书多实例 bot、channel binding、知识库、审计标签 |
| Agent 平台 | 分层 Java 模块、ToolProvider、Scene、Hook、RuntimePolicy、MCP |

## 快速开始

目标是用不到一分钟先成功跑起来一次：安装、启动服务、验证健康状态、再打开 TUI。

### 第一步：选择安装路径

#### Linux / VPS 快速安装

如果你想在 Linux 主机上尽快完成部署，优先使用安装脚本。

更安全的安装方式：

```bash
curl -fsSLO https://raw.githubusercontent.com/HandleCoding/OpenHuskyAgent/main/install.sh
less install.sh
bash install.sh
```

可信环境下的快捷方式：

```bash
curl -fsSL https://raw.githubusercontent.com/HandleCoding/OpenHuskyAgent/main/install.sh | bash
```

常用参数：

```bash
bash install.sh --non-interactive
bash install.sh --install-dir="$HOME/openHusky" --port=18088
bash install.sh --upgrade
husky update
```

安装脚本默认会把仓库 clone 到 `~/openHusky`，按需安装 JDK 17+，构建 service 和 TUI client JAR，把运行配置写到 `~/.husky/.env`，创建 `~/.husky/config`、`~/.husky/skills`、`~/.husky/db`、`~/.husky/logs` 和 `~/.husky/memory` 目录，生成随机 `HUSKY_API_KEYS`，并可选安装 systemd 服务。

`bin/husky` 会优先读取 `~/.husky/.env`，只有在用户级配置不存在时才回退到仓库内的 `.env`。

#### macOS / Windows / 通用源码安装

如果你是在本地开发，或系统不是 Linux，使用源码方式。

前置要求：

- JDK 17+
- Git
- 一个 OpenAI-compatible chat model endpoint
- 可选：Brave 或 Tavily API key，用于 web search
- 可选：Playwright Chromium，用于 browser tools
- 可选：Docker，用于 Docker execution backend 实验

```bash
git clone https://github.com/HandleCoding/OpenHuskyAgent.git
cd OpenHuskyAgent
mkdir -p ~/.husky
cp .env.example ~/.husky/.env
```

如果你只是做纯源码调试，也可以使用仓库内 `.env`；当 `~/.husky/.env` 不存在时，`bin/husky` 会自动回退到它。

### 第二步：设置最小配置

编辑 `~/.husky/.env`，至少设置：

```bash
OPENAI_API_KEY=your-key
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL=gpt-5.4
```

如果默认 base URL 和 model 就适用于你的 provider，那么第一次运行严格必填的只有 `OPENAI_API_KEY`。

### 第三步：构建并启动 Husky

```bash
./mvnw -B -ntp clean install
bin/husky serve
```

成功信号：当前终端会持续运行服务，并默认监听 `18088` 端口，除非你改了 `HUSKY_PORT`。

本地开发时也可以使用 `bin/husky dev` 一次启动 service 和 TUI，但首次体验更推荐按 `serve` + `tui` 的路径理解整个流程。

### 第四步：验证服务可用

```bash
curl http://localhost:18088/actuator/health
```

成功信号：返回的 JSON 中应包含 `"status":"UP"`。

### 第五步：打开 TUI

另开一个终端：

```bash
bin/husky tui --server ws://localhost:18088/api/tui
```

成功信号：TUI 无报错完成连接，并且你可以立刻发送第一条提示词。

## `~/.husky` 目录结构

安装完成后，Husky 会把用户级配置和运行时数据统一放在 `~/.husky` 下：

| 路径 | 用途 |
|------|------|
| `~/.husky/.env` | 主运行时配置，包含模型设置、API keys、端口和功能开关 |
| `~/.husky/config/` | 用户维护的配置文件，例如 `mcp-servers.json` |
| `~/.husky/skills/` | 从 Husky 数据目录加载的已安装或自定义 skills |
| `~/.husky/db/` | 运行时 SQLite 数据，例如 session 状态和 checkpoints |
| `~/.husky/logs/` | 可选日志输出目录，以及需要可写权限的运行时日志目录 |
| `~/.husky/memory/` | 持久文件型记忆目录 |
| `~/.husky/memory/MEMORY.md` | 由 `memory_*` tools 管理的共享持久笔记 |
| `~/.husky/memory/USER.md` | 由 `user_*` tools 管理的用户画像记忆 |

`~/.husky/.env` 和 `~/.husky/config/` 属于配置层；持久记忆内容单独放在 `~/.husky/memory/` 下。

记忆策略说明：

- `default`：会自动把 `~/.husky/memory/` 下的文件型记忆注入 prompt。
- `manual-only`：`MEMORY.md` 和 `USER.md` 仍可通过 memory tools 读写，但不会自动注入 prompt。
- `session-recall`：prompt / session recall 不使用持久文件型记忆，而是走会话召回。

你可以单独备份或迁移 `~/.husky`，而不影响 `~/openHusky` 中的仓库代码。

### 升级现有安装

推荐的本机升级方式是：

```bash
husky update
```

`husky update` 底层仍然复用 `bash install.sh --upgrade`，但会先拒绝脏工作区，并在升级结束后打印当前代码目录、配置目录和 memory 目录。

如果你想显式走底层命令，仍可使用：

```bash
bash install.sh --upgrade
```

## 最小配置

大多数部署只需要 `.env`：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OPENAI_API_KEY` | empty | OpenAI-compatible API key；模型调用必填 |
| `OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI-compatible endpoint |
| `OPENAI_COMPLETIONS_PATH` | `/v1/chat/completions` | Chat completions path |
| `OPENAI_MODEL` | `gpt-5.4` | 主 chat model |
| `OPENAI_TEMPERATURE` | `0.7` | 主模型 temperature |
| `AUXILIARY_*` | blank/main fallback | 可选辅助模型，用于摘要、压缩、网页总结和 vision |
| `HUSKY_PORT` | `18088` | HTTP/WebSocket 服务端口 |
| `HUSKY_DATA_DIR` | `~/.husky` | Runtime data directory，存放 DB、skills、MCP config 和 logs |
| `AUTH_ENABLED` | `true` | 启用 `/api/chat` API key 鉴权 |
| `HUSKY_API_KEYS` | generated/example | Chatbot API keys，多个用逗号分隔；公网部署前必须替换 |
| `TUI_WS_ALLOWED_ORIGINS` | `*` | WebSocket origins；wildcard 仅适合本地开发 |
| `WEB_BACKEND` | `auto` | `auto`、`brave`、`tavily` 或 `none` |
| `BRAVE_SEARCH_API_KEY` | empty | 启用 Brave search |
| `TAVILY_API_KEY` | empty | 启用 Tavily search |
| `PROXY_*` / `HUSKY_PROXY_URL` | env-driven | 共享出站 HTTP proxy 配置 |
| `WEB_PROXY_*` | empty | Web 专用 proxy 覆盖 |
| `BROWSER_ENABLED` | `false` | 启用 Playwright browser tools |
| `MCP_ENABLED` | `false` | 启用 MCP server loading |
| `MCP_CONFIG_PATH` | `${HUSKY_DATA_DIR}/config/mcp-servers.json` | MCP server config path |
| `SKILLHUB_API_KEY` | empty | 启用需要认证的 SkillHub 操作 |

Browser 和 MCP 默认关闭，需要明确配置后再开启。

### 后台运行方式

`husky serve` 会以前台方式占用当前终端。如果你没有使用 `systemd`，可以用轻量后台命令：

```bash
husky start
husky status
husky logs
husky stop
```

这套命令会把 PID 写到 `~/.husky/husky.pid`，把日志写到 `~/.husky/logs/husky-serve.log`。

对于长期运行的 Linux 服务器部署，仍然推荐 `systemd`：

```bash
sudo systemctl start husky-agent
sudo systemctl status husky-agent
journalctl -u husky-agent -f
```

## 使用 Husky

### TUI 个人助手

```bash
bin/husky tui --server ws://localhost:18088/api/tui
```

TUI 使用 WebSocket JSON-RPC，支持流式文本、工具调用展示、审批提示和 session 交互。

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

请求字段：

| Field | 必填 | 说明 |
|-------|------|------|
| `message` | 是 | 用户输入 |
| `sessionId` | 否 | 服务端返回的已有 session id；首次请求不传 |

请求 Header：

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Api-Key` | auth 开启时必填 | Chatbot API 鉴权 |
| `X-User-Id` | 是 | 稳定的终端用户身份，用于 session 归属 |
| `X-Scene` | 否 | channel binding 允许时可覆盖 scene |

SSE events 包括 `token`、`reasoning`、`message`、`tool_started`、`tool_completed`、`tool_failed`、`done` 和 `error`。

### 飞书 Channel

Husky 支持飞书 WebSocket/webhook adapter，并支持 `channels.feishu.instances.*` 下的多 app 实例。每个实例可通过 `channel-bindings.*` 绑定到不同 scene，从而暴露不同 prompt、tools、memory 和 approval policy。

## 能力

- **ReAct Graph Runtime** — LangGraph4j 驱动 model -> tool -> observation 循环，并支持 interrupt/resume approval。
- **多 Channel 流式输出** — TUI WebSocket、HTTP SSE 和 Feishu adapter 渲染同一套 runtime events。
- **Scene Runtime Policy** — scene 控制 prompt、toolsets、精确 allow/deny、MCP servers、knowledge sources、skills、approval、backend、working directory、memory、audit 和 rate limit。
- **内置工具** — 文件读写编辑删除移动、apply patch、文件搜索/listing、terminal/process、todo、web search/fetch、browser、memory、knowledge、skills、delegate、MCP 和 vision tools。
- **并行工具执行** — 同一轮模型输出中的 safe tools 可并发执行；需要审批的工具进入串行确认队列。
- **记忆与 Checkpoint** — SQLite-backed sessions、graph checkpoints、memory tools、context compression 和模型上下文长度策略。
- **Knowledge Layer** — 基于配置知识源暴露 scene-scoped `knowledge_search` 和 `knowledge_fetch`。
- **Skill System** — 内置 skills、用户安装 skills、SkillHub search/install，以及渐进式 `skill_list` / `skill_view` 加载。
- **MCP Integration** — 支持 stdio、SSE 和 Streamable-HTTP MCP servers，并由 scene 控制可见性。
- **Browser 和 Vision** — 启用后支持 Playwright browser automation 和本地/远程图片分析。
- **Sub-agent Delegation** — `delegate_task` 可启动 child agents 完成隔离或并行任务。
- **Observability** — audit logs、metrics、session stats、redaction 和 `/actuator/husky`。

## 架构

Husky 使用分层 Java 架构：

| 模块 | 职责 |
|------|------|
| `service/` | Spring Boot 入口、HTTP SSE、TUI WebSocket、Feishu adapters、auth、Actuator |
| `application/` | Agent 编排、channel adapters、scene/session resolution、runtime queues、JSON-RPC methods、sub-agent runner |
| `domain/` | ReAct graph、graph state、approvals、prompt builder、context manager、sessions、hooks、channel events |
| `infra/` | Tools、memory、knowledge、MCP、browser、workspace、checkpoint store、AI clients、execution backends、config、observability |
| `client/` | 独立 terminal TUI client，不依赖 Spring |

依赖方向保持单向：`service -> application -> domain -> infra`。`client` 模块独立，通过 WebSocket JSON-RPC 与 service 通信。

Runtime 行为遵循：

```text
Transport -> Channel -> Channel Instance -> Scene -> Runtime Scope -> ReAct Graph
```

每个请求会解析 principal、channel identity、具体 account/bot binding、scene config 和 session id。生成的 runtime scope 决定 prompt sections、visible tools、skills、memory、knowledge sources、MCP servers、approval mode、execution backend、workspace/checkpoint storage、audit tags 和 graph cache keys。

这让 Husky 既能作为本地个人助手使用，也能让企业部署通过 provider 替换 memory、knowledge、file/workspace、MCP 和 checkpoint 等存储实现。

## 配置参考

主要 runtime 默认值在 `service/src/main/resources/application.yml`。

| 领域 | 重要配置 |
|------|----------|
| LLM | `spring.ai.openai.*`、`agent.auxiliary.*` |
| Agent loop | `agent.graph.max-react-loops`、`agent.llm.*`、`agent.tool.*`、`agent.checkpoint.enabled` |
| Context | `context.threshold-percent`、`context.context-length`、`context.model-context-lengths`、`context.tail-token-budget` |
| Channels | `channel-bindings.*`、`channels.feishu.instances.*`、`tui.ws.*`、`chatbot.enabled` |
| Scenes | `scenes.default-scene`、`scenes.configs.*.toolsets`、`allowed-tools`、`denied-tools`、`approval`、`backend`、`working-dir`、`memory`、`storage` |
| Execution | `execution.backend.docker.*`、`execution.backend.idle-ttl-seconds` |
| Web | `web.backend`、`web.proxy.*`、`BRAVE_SEARCH_API_KEY`、`TAVILY_API_KEY` |
| Browser | `browser.enabled`、`browser.headless`、`browser.timeout-seconds`、`browser.allow-private-network` |
| MCP | `mcp.enabled`、`mcp.config-path`、scene `allowed-mcp-servers` / `denied-mcp-servers` |
| Knowledge | `knowledge.enabled`、`knowledge.local-sources`、snippet/document/depth limits |
| Skills | `skill.builtin-dir`、`skill.dir`、`skill.managed-dirs`、`skillhub.*` |
| Auth | `auth.enabled`、`auth.api-keys` |
| Observability | `management.endpoints.web.exposure.include`、`husky.observability.*` |

Storage 默认是 local。非本地 workspace/checkpoint provider 是扩展点；不支持的 remote type 会 fail fast，不会静默退回本地行为。

## 安全与生产加固

在把 Husky 暴露到本地开发以外的环境前：

- 用强随机值替换 `HUSKY_API_KEYS`。
- 公网 `/api/chat` 保持 `AUTH_ENABLED=true`。
- 设置 `TUI_WS_ALLOWED_ORIGINS` 为可信 origin，不要用 wildcard `*` 暴露 TUI WebSocket。
- 把 `approval: none` 视为免审批模式；在线 scene 不要把它和危险 toolsets 组合使用。
- 面向公网的 scene 应限制 terminal、process、文件修改/搜索、browser automation、`skill_install`、`skill_manage` 和危险 MCP tools。
- 使用 `allowed-mcp-servers`、`denied-mcp-servers`、`allowed-tools` 和 `denied-tools` 收紧 MCP 和 tool visibility。
- Actuator endpoints 应放在可信网络内，或放在带认证的反向代理之后。
- 不要提交 `.env`、本地数据库、私有 MCP 配置、API keys、logs 或生成的 runtime data。

安全问题请私下报告，见 `SECURITY.md`。

## 开发

```bash
# 默认测试；排除 live API 和 browser groups
./mvnw -B -ntp test

# 跳过测试构建 package
./mvnw -B -ntp -DskipTests package

# 模块测试
./mvnw -B -ntp test -pl infra
./mvnw -B -ntp test -pl domain
./mvnw -B -ntp test -pl application
./mvnw -B -ntp test -pl service
./mvnw -B -ntp test -pl client

# 单个测试类
./mvnw -B -ntp test -Dtest=ToolRegistrationIntegrationTest -pl service

# 显式运行 live API 兼容性测试
OPENAI_API_KEY=... ./mvnw -B -ntp test -P live-api-tests

# 显式运行 browser tests
./mvnw -B -ntp exec:java -pl infra -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
./mvnw -B -ntp test -P browser-tests
```

安装脚本为了速度使用 `-DskipTests`。贡献者提交 PR 前应运行默认测试命令。

## 贡献

请阅读：

- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`

Issue 和 Pull Request 中不要包含 secrets、私有 prompts、本地 `.env` 内容或客户数据。

## 许可证

Husky 使用 MIT License 发布，见 `LICENSE`。
