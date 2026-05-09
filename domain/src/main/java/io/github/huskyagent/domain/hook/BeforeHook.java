package io.github.huskyagent.domain.hook;

public interface BeforeHook extends AgentHook {

    HookResult before(HookContext context);
}
