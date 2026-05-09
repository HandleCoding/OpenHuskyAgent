package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.AfterHook;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.memory.BuiltinMemoryProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class MemorySnapshotCleanupHook implements AfterHook {

    private final BuiltinMemoryProvider memoryProvider;

    @Override
    public String name() {
        return "memory-snapshot-cleanup";
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.SESSION_END);
    }

    @Override
    public void after(HookContext context) {
        memoryProvider.clearSessionSnapshot(context.sessionId());
    }
}
