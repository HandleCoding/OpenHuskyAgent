# Husky Agent 平台路线图

> 当前主路线图。历史条目见 [roadmap.md](roadmap.md)；Agent / Channel / Binding 详细方案见 [agent_channel_binding_plan.md](agent_channel_binding_plan.md)。

## 1. 定位与目标

### 1.1 产品定位

Husky 是一个 **自托管、配置驱动、多 Channel 的通用 Agent Runtime / 轻量 Agent 平台**。

它不是单一聊天机器人应用，而是：

- **一个引擎**服务多种 Agent 定义；
- **能力可切片**（tools / MCP / skills / knowledge / memory / approval / backend 等）；
- **交付可插拔**（TUI、HTTP、OpenAI 兼容 API、飞书 / Telegram / Slack 等）。

### 1.2 总目标

把「场景开关」时代的残留抽象收干净，让平台心智统一为：

```text
Agent 定义谁、会什么、怎么跑
  → Channel 定义怎么连
  → Binding 定义谁接到哪条线
  → Session / RuntimeScope 定义这一次怎么执行
```

成功时，贡献者与使用者只需要稳定掌握四个词：**Agent、Channel、Binding、Session**。

### 1.3 设计原则

1. **不推倒重来**：保留已验证的三层配置与请求时解析 RuntimePolicy 的路径。
2. **Agent 一等公民**：对外对内都叫 Agent；不再长期保留 Scene 双名。
3. **定义静态、范围动态**：Agent 定义是配置；`RuntimePolicy` / `CapabilityView` / `RuntimeScope` 是每次请求算出的运行视图。
4. **能力相对执行环境可证明**：backend / workspace 决定哪些工具能真正出现。
5. **渐进演进**：先收敛语义与命名，再补定义维度，再统一多 Agent，最后做控制面。

---

## 2. 当前状态（基线）

### 2.1 已完成

| 领域 | 状态 |
|------|------|
| Agent / Channel / Binding 三层公开配置 | 主体完成（`agents.*`、`channels.*.instances.*`、`agent-channel-bindings.*`） |
| ReAct 运行时（模型调用、工具分发/并行、审批、checkpoint） | 可用 |
| 多 Channel 交付（TUI / HTTP SSE / OpenAI 兼容 / 飞书 / Telegram / Slack） | 可用 |
| 工具与扩展（文件、终端、搜索、浏览器、MCP、Skill、Memory、Knowledge） | 可用 |
| 运行控制（`/stop`、`/new` bypass 取消、session 隔离） | 可用 |
| 安装与分发（install 脚本、Homebrew、`husky` CLI） | 可用 |

请求路径（已成立）：

```text
Channel 入站
  → Agent-Channel Binding 解析 agentId
  → 加载 Agent 定义（内部仍多为 SceneConfig）
  → 组装 RuntimePolicy + CapabilityView
  → 组成 RuntimeScope
  → 运行同一套 ReAct Graph
```

### 2.2 过渡态问题

| 问题 | 表现 | 影响 |
|------|------|------|
| 命名只改了一半 | 配置写 `agents.*`，代码/DB/日志仍是 `SceneConfig` / `sceneId` / `scene_id` | 心智分裂，后续 feature 易继续分叉 |
| Agent 定义是政策大包 | prompt、tools、backend、memory、audit、rate-limit 全塞在一个对象里 | 难演进、难校验、难文档化 |
| 模型基本全局 | 主要依赖进程级 OpenAI 配置 | 无法稳定表达「不同 agent 用不同模型」 |
| 子 Agent 旁路 | `agent.delegation` / `SubAgentConfig` 与 `agents.*` 不是同一套抽象 | 多 Agent 协作不是平台一等能力 |
| 资源归属模糊 | MCP / Skills / Knowledge 多为全局注册 + agent 过滤 | 能用，但「agent 拥有什么」语义不清晰 |
| 控制面薄弱 | 缺 list agents/bindings API、热更新、管理 UI | 仍偏配置文件运维，不像完整平台 |
| 部分策略未闭环 | 如 rate-limit 已进配置但未真正 enforce | 治理能力名不副实 |

### 2.3 与既有文档的关系

- [agent_channel_binding_plan.md](agent_channel_binding_plan.md)：三层配置 **主体已完成**；其中 follow-up（管理 API、绑定可视化、显式 agent 选择、多 route）并入本路线图 **P3** 及以后。
- [roadmap.md](roadmap.md)：保留为历史索引；**当前主路线图以本文为准**。

---

## 3. 目标抽象模型

### 3.1 保留什么

- 公开三层：`agents.*` / `channels.*.instances.*` / `agent-channel-bindings.*`
- 运行时三件套：`RuntimePolicy`、`CapabilityView`、`RuntimeScope`
- 默认同一套 ReAct 引擎服务多种 Agent（不为每个 agent 先上异构工作流引擎）

### 3.2 收敛什么

| 现状 | 目标 |
|------|------|
| `SceneConfig` / `SceneResolver` / `ChannelSceneRouter` | `AgentDefinition`（或等价命名）/ `AgentResolver` / channel→agent 路由 |
| `sceneId`、`scene_id` | `agentId`、兼容迁移后的存储字段 |
| memory scope `SCENE` | agent 语义命名（如 `AGENT`），配置与文档一致 |
| 日志 / 错误里的 scene | 统一为 agent |

原则：**产品语义与内部领域语言都叫 Agent**；不再把 Scene 当作长期别名。

### 3.3 Agent 定义的概念拆分

实现上可以仍是一个配置对象、分阶段拆类；**领域语言**上应分清：

```text
AgentDefinition
├── identity      # id、systemPrompt、promptFiles / prompt 策略
├── capabilities  # toolsets、tools allow/deny、MCP、skills、knowledge
├── model         # model / provider / 参数 profile（当前缺口）
├── governance    # approval、audit、rateLimit
├── execution     # backend、workingDir、storage、隔离相关
└── cognition     # memory、context compaction
```

说明：

- **Capabilities**：agent 能干什么；
- **Execution**：在哪干、隔离到什么程度；
- **Cognition**：怎么记、怎么压缩上下文；
- **Governance**：谁批准、怎么审计限流；
- **Model**：用哪个模型与推理参数。

Capabilities 必须能相对 Execution **计算**出来（例如无持久文件系统的 backend，不应暴露文件类工具）。

### 3.4 资源归属模型

中期明确为：

1. **平台级共享目录**：MCP servers、Skills、Knowledge sources、Model profiles；
2. **Agent 引用 + 过滤 / 覆盖**：allow、deny、参数覆盖、每 agent model profile。

避免看起来「每个 agent 可配」，实际关键能力全是进程全局单例且语义不清。

### 3.5 子 Agent

目标模型：子 agent **不是另一种东西**，只是另一个 `agents.*` 定义，外加委托策略。

示意：

```yaml
agents:
  researcher:
    toolsets: [WEB, SEARCH, MCP]
    approval: none

  coder:
    toolsets: [CORE, TERMINAL, SEARCH]
    approval: required

  orchestrator:
    toolsets: [DELEGATE]
    delegation:
      can-delegate-to: [researcher, coder]
      max-concurrent-children: 3
```

全局 `agent.delegation` 逐步降级为默认值，而不是唯一配置面。

---

## 4. 分阶段怎么干

### P0 — Agent 一等公民（命名与语义收敛）

**目标**：新人读代码时不再需要「scene ≈ agent」这句翻译。

| 事项 | 说明 |
|------|------|
| 领域命名收敛 | `SceneConfig` → Agent 定义类型；`SceneResolver` → Agent 解析；路由类去 Scene 语义 |
| API / 日志 / 错误信息 | `Unknown scene` 等改为 agent；文档与注释同步 |
| 存储兼容 | `scene_id` 可先双写/双读或列重命名迁移；对外语义统一为 agentId |
| memory scope 命名 | `SCENE` → agent 语义；配置兼容旧值一段时间 |
| 测试与 fixtures | 测试名、fixture 字段与断言改为 agent 语言 |

**完成标准**：

- 公开文档与主要 Java 类型不再把 Scene 当作产品概念；
- 新增 agent 只需理解 `agents.*` + binding，不必查 scene 对照表。

**非目标**：不在本阶段改行为语义，尽量纯重命名 + 兼容层。

---

### P1 — Agent 定义补全

**目标**：Agent 定义覆盖平台真正需要的关键维度，且能力与执行环境一致。

| 事项 | 说明 |
|------|------|
| per-agent model / profile | agent 可指定 model（及必要的 provider/参数）；缺省回落全局 |
| backend ↔ capability 对齐 | 文件工具、MCP 本地性等相对 backend 可计算；承接当前 runtime backend WIP |
| rate-limit enforce | 配置进入 policy 后真正生效，而不是只解析 |
| 资源语义文档化 | 明确 MCP / Skills / Knowledge「全局目录 + agent 引用/过滤」 |
| 定义校验 | 启动或加载时校验未知 toolset、无效 MCP server id、无效 skill id 等 |

**完成标准**：

- 至少能稳定配置两个行为明显不同的 agent（不同模型、不同工具、不同 backend/approval）；
- Docker/无盘等 execution 下，不可用工具不会出现在模型可见集合中；
- rate-limit 有可验证的行为与测试。

---

### P2 — 统一多 Agent 抽象

**目标**：主 agent 与子 agent 使用同一配置面与同一解析路径。

| 事项 | 说明 |
|------|------|
| 子 agent 引用 `agents.*` | 委托目标是已注册 agent，而不是仅靠全局旁路参数 |
| agent 级 delegation 策略 | `can-delegate-to`、并发、超时、blocked toolsets 等可落在 agent 上 |
| 运行时继承规则 | 明确子 run 继承哪些 policy（cwd、backend、memory、可见工具）以及哪些必须收紧 |
| 全局 delegation 配置 | 保留为默认值 / 上限，不再是唯一真相源 |

**完成标准**：

- 文档中可以用同一套 `agents.*` 描述编排型 agent 与执行型 agent；
- 子 agent 的能力切片可测、可预期。

---

### P3 — 平台控制面

**目标**：从「改 YAML 重启」走向「可观察、可管理的 Agent 平台」。

| 事项 | 说明 |
|------|------|
| 管理 API | list agents、bindings、有效 capabilities（解析后的视图） |
| 显式 agent 选择 | 仅在 HTTP / OpenAI 兼容等契约需要处支持显式 agent（延续 binding plan follow-up） |
| 配置重载 | 安全热加载或受控重载 agents/bindings（含校验失败 fail-closed） |
| 可视化 / UI | agent 详情上展示已连接 channel instances（可先 API，后 UI） |

**完成标准**：

- 运维无需翻代码即可回答：有哪些 agent、绑了哪些 channel、各自暴露什么能力；
- 配置错误在加载期失败，而不是运行期静默跑偏。

---

### 明确不做 / 暂缓

以下事项 **不作为近期主线**，避免范围膨胀：

1. 多图引擎 / 复杂 DAG 工作流产品化；
2. 同一 channel instance 上复杂多 agent 智能路由与优先级引擎；
3. domain / infra 大规模分层重写（可随 P0/P1 局部改善，不做大拆）；
4. 完整多租户 SaaS 控制面（org、计费、marketplace 等）。

若产品目标升级到「多租户 Agent 云」，再单独立项，不混进本路线图的 P0–P3。

---

## 5. 建议执行顺序

```text
P0 命名与语义收敛
  → P1 定义补全（model / capability-execution / rate-limit）
  → P2 子 Agent 并入同一抽象
  → P3 控制面 API 与可管理性
```

并行建议：

- **P0 可与当前 backend-aware capability WIP 并行**，但合并时统一用 agent 语义命名，避免再引入 scene 新 API。
- **P1 的 model profile** 可在 P0 重命名基本完成后立即做，收益高。
- **P3** 不阻塞 P1/P2 的运行时正确性工作。

---

## 6. 成功标准（总览）

路线图阶段性完成时，应满足：

1. **心智统一**：文档、配置、代码、日志都以 Agent 为中心；Scene 不再是产品概念。
2. **配置闭环**：新增一个 agent ≈ 写 `agents.<id>` + binding +（可选）资源引用。
3. **能力可证明**：可见 tools/skills 与 backend/workspace/审批策略一致。
4. **多 Agent 一致**：委托出的子 agent 与主 agent 同一抽象与配置面。
5. **可运维**：能列出 agent、binding 与有效能力；关键治理策略可 enforce。

---

## 7. 相关文档

| 文档 | 角色 |
|------|------|
| [agent-platform-roadmap.md](agent-platform-roadmap.md) | **当前主路线图（本文）** |
| [roadmap.md](roadmap.md) | 历史 roadmap 索引 |
| [agent_channel_binding_plan.md](agent_channel_binding_plan.md) | 三层配置详细方案（主体已完成） |
| [../bypass_plan.md](../bypass_plan.md) | `/stop` 与 bypass 命令机制（运行控制专题） |

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-11 | 初版：基于 Agent 一等公民化讨论，沉淀 P0–P3 平台演进路线 |
