package io.github.huskyagent.domain.hook;

public interface AfterHook extends AgentHook {

    void after(HookContext context);
}
