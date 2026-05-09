package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.AfterHook;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Unregisters session meta on SESSION_END so the factory no longer holds SceneConfig references.
 * The backend (e.g. Docker container) is kept alive and cleaned up by the idle TTL reaper,
 * allowing it to survive across multiple conversation turns of the same session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxCleanupHook implements AfterHook {

    private final ExecutionBackendFactory backendFactory;

    @Override
    public String name() {
        return "sandbox-cleanup";
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.SESSION_END);
    }

    @Override
    public void after(HookContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null) return;
        backendFactory.unregisterSession(sessionId);
    }
}
