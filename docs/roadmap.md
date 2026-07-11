# Roadmap

> **当前主路线图**请看：[agent-platform-roadmap.md](agent-platform-roadmap.md)  
> 本文保留历史条目与索引，避免丢失已完成工作的上下文。

---

## 当前主线

Husky 定位为通用 Agent 平台 / Runtime。下一阶段重点：

1. **P0** Agent 一等公民（内部 Scene → Agent 语义收敛）
2. **P1** Agent 定义补全（per-agent model、capability↔backend、rate-limit enforce）
3. **P2** 子 Agent 并入 `agents.*` 同一抽象
4. **P3** 平台控制面（管理 API、重载、绑定可视化）

详情、目标抽象与成功标准见 [agent-platform-roadmap.md](agent-platform-roadmap.md)。

---

## 已完成 / 归档

### Agent / Channel / Binding 三层配置模型

**状态：主体已完成**（公开配置入口已切换；内部 Scene 命名收敛见主路线图 P0）。

目标：把 scene 与渠道路由的心智模型整理为更简单的三层结构：

- **Agent 定义**：描述一个 agent 是谁、会什么、怎么运行，对外配置入口为 `agents.*`。
- **Channel 定义**：描述渠道实例如何连接，例如 Feishu、Slack、Telegram、HTTP、TUI 的 token、app id、socket/webhook 等。
- **Agent-Channel Binding**：描述哪个 agent 接入哪些 channel instance，支持一个 agent 绑定多个渠道实例。

简要方向（已落实为产品原则）：

- 保持 agent 与 channel 解耦。
- 一个 agent 可以绑定多个不同渠道，也可以绑定多个同类型渠道实例。
- 初期不引入复杂优先级、全局 default-agent、多 route 规则；同一个 channel instance 被多个 agent 绑定时视为配置错误。
- 对外只保留 `agents.*`、`channels.*.instances.*`、`agent-channel-bindings.*` 三层配置入口。

详细方案见 [agent_channel_binding_plan.md](agent_channel_binding_plan.md)。  
未完成 follow-up（管理 API、UI、显式 agent 选择等）已并入 [agent-platform-roadmap.md](agent-platform-roadmap.md) 的 P3。
