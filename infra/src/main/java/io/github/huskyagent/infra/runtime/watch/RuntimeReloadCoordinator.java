package io.github.huskyagent.infra.runtime.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一协调资源 reload 与缓存失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeReloadCoordinator {

    private final List<RuntimeResourceReloadHandler> handlers;
    private final RuntimeReloadInvalidation invalidation;

    public void onPathsChanged(Map<RuntimeResourceType, Set<Path>> changedByType) {
        if (changedByType == null || changedByType.isEmpty()) {
            return;
        }

        Map<RuntimeResourceType, RuntimeResourceReloadHandler> handlerMap = new EnumMap<>(RuntimeResourceType.class);
        for (RuntimeResourceReloadHandler handler : handlers) {
            handlerMap.put(handler.descriptor().type(), handler);
        }

        boolean clearPromptCache = false;
        boolean clearGraphCache = false;

        for (Map.Entry<RuntimeResourceType, Set<Path>> entry : changedByType.entrySet()) {
            RuntimeResourceReloadHandler handler = handlerMap.get(entry.getKey());
            if (handler == null) {
                log.debug("No reload handler registered for {}", entry.getKey());
                continue;
            }
            RuntimeReloadOutcome outcome = handler.reload(entry.getValue());
            if (!outcome.success()) {
                log.warn("Runtime reload failed for {}: {}", outcome.type(), outcome.summary());
                continue;
            }
            log.info("Runtime reload succeeded for {}: {}", outcome.type(), outcome.summary());
            clearPromptCache = clearPromptCache || outcome.clearPromptCache();
            clearGraphCache = clearGraphCache || outcome.clearGraphCache();
        }

        if (clearPromptCache) {
            invalidation.clearPromptCache();
        }
        if (clearGraphCache) {
            invalidation.clearGraphCache();
        }
    }
}
