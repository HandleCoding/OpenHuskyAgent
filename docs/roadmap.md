# Roadmap

## Agent / Channel / Binding 三层配置模型

目标：把当前 scene 与渠道路由的心智模型整理为更简单的三层结构：

- Agent 定义：描述一个 agent 是谁、会什么、怎么运行，对外配置入口为 `agents.*`。
- Channel 定义：描述渠道实例如何连接，例如 Feishu、Slack、Telegram、HTTP、TUI 的 token、app id、socket/webhook 等。
- Agent-Channel Binding：描述哪个 agent 接入哪些 channel instance，支持一个 agent 绑定多个渠道实例。

简要方向：

- 保持 agent/scene 与 channel 解耦。
- 一个 agent 可以绑定多个不同渠道，也可以绑定多个同类型渠道实例。
- 初期不引入复杂优先级、全局 default-agent、多 route 规则；同一个 channel instance 被多个 agent 绑定时先视为配置错误。
- 对外只保留 `agents.*`、`channels.*.instances.*`、`agent-channel-bindings.*` 三层配置入口。

详细方案见 [agent_channel_binding_plan.md](agent_channel_binding_plan.md)。
