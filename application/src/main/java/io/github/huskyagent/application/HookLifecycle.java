package io.github.huskyagent.application;

import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fires lifecycle hooks when the application starts and shuts down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HookLifecycle {

    private final HookRegistry hookRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        hookRegistry.fireAfter(HookEvent.STARTUP, null, Map.of());
        log.info("Agent started, STARTUP hooks fired");
    }

    @PreDestroy
    public void onShutdown() {
        hookRegistry.fireAfter(HookEvent.SHUTDOWN, null, Map.of());
        log.info("Agent shutting down, SHUTDOWN hooks fired");
    }
}
