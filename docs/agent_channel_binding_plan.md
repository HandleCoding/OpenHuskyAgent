# Agent / Channel / Binding Final Configuration

## Summary

Husky's public runtime configuration is now a three-layer model:

- `agents.*` defines what an agent is and how it runs.
- `channels.*.instances.*` defines how each channel instance connects.
- `agent-channel-bindings.*` defines which agent owns which channel instances.

This is a breaking external configuration model. `scenes`, `channel-bindings`, and channel instance `default-scene` are not valid public configuration entries.

## Target Shape

```yaml
agents:
  assistant:
    system-prompt: ""
    toolsets: []
    approval: required

channels:
  feishu:
    instances:
      assistant-bot:
        enabled: ${FEISHU_ASSISTANT_ENABLED:false}
        app-id: ${FEISHU_ASSISTANT_APP_ID:}
        app-secret: ${FEISHU_ASSISTANT_APP_SECRET:}

agent-channel-bindings:
  assistant:
    - feishu:assistant-bot
    - slack:assistant-bot
    - tui:local

  chatbot:
    - http:chatbot
```

## Semantics

- One agent can bind multiple channel refs.
- One agent can bind multiple instances of the same channel type.
- One channel ref can bind to only one agent.
- Duplicate channel refs across agents are startup/configuration errors.
- Missing bindings fail closed unless a supported HTTP/OpenAI-compatible entrypoint explicitly supplies an agent id.
- There is no global default agent in final v1.

## Runtime Resolution

```text
Inbound channel identity
  -> channelType:instanceId / platform account id
  -> agent-channel-bindings
  -> agent id
  -> internal SceneConfig
  -> RuntimePolicy
  -> RuntimeScope
  -> ReAct graph
```

Internally, Java types such as `SceneConfig` and `SceneResolver` may still exist as implementation details. Public configuration and user-facing docs should use Agent terminology.

## Channel Identity Lookup

The binding layer resolves `channelType:instanceId` into the platform account id used by inbound channel messages:

- Feishu: `app-id`, falling back to `bot-open-id`.
- Slack: `bot-user-id`.
- Telegram: `bot-username`, normalized without a leading `@`.
- HTTP and TUI: the instance id.

Enabled channel refs with blank platform account ids are invalid. Disabled referenced instances are ignored.

## Removed Public Entries

The final model intentionally removes:

- `scenes.default-scene`
- `scenes.configs.*`
- `channel-bindings.default-scene`
- `channel-bindings.bindings.*`
- `channel-bindings.allow-explicit-scene-override-for`
- `channels.*.instances.*.default-scene`

Deployments should migrate directly to `agents.*` and `agent-channel-bindings.*`.

## Follow-Up Work

- Add management APIs for listing agents and bindings.
- Add UI surfaces for connected channels on an agent detail page.
- Consider explicit agent selection for selected HTTP/OpenAI-compatible endpoints only where the transport contract calls for it.
- Consider multi-agent routing per channel instance later, using explicit route rules instead of implicit defaults.
