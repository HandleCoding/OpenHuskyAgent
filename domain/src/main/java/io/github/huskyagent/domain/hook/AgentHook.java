package io.github.huskyagent.domain.hook;

import java.util.Set;

public interface AgentHook {

    String name();

    Set<HookEvent> supportedEvents();

    default int order() { return 100; }
}
