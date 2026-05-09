package io.github.huskyagent.domain.hook;

import java.util.List;

public interface HookRegistry {

    void register(AgentHook hook);

    void unregister(String hookName);

    HookResult fireBefore(HookEvent event, String sessionId, java.util.Map<String, Object> data);

    void fireAfter(HookEvent event, String sessionId, java.util.Map<String, Object> data);

    List<AgentHook> getHooks();

    List<AgentHook> getHooks(HookEvent event);
}
