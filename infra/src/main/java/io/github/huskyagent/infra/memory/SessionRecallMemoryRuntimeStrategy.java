package io.github.huskyagent.infra.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * session-recall memory strategy: suited for group-chat / Feishu bot contexts.
 * No persistent notes (MEMORY.md) injected into prompt; only current-session
 * conversation history is recalled. AfterTurn syncs only to ScopedMemoryProvider
 * (SessionMemoryProvider), not to BuiltinMemoryProvider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRecallMemoryRuntimeStrategy implements MemoryRuntimeStrategy {

    private final MemoryScopeResolver memoryScopeResolver;
    private final DefaultMemoryRuntimeStrategy defaultStrategy;

    @Override
    public String id() {
        return "session-recall";
    }

    @Override
    public MemoryLoadResult loadForPrompt(MemoryLoadRequest request) {
        return MemoryLoadResult.empty();
    }

    @Override
    public MemoryResult search(MemorySearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return MemoryResult.empty("aggregated");
        }
        if (request.scope() != null && "DISABLED".equals(request.scope().getMemoryPolicy())) {
            return MemoryResult.empty("disabled");
        }

        // only query ScopedMemoryProvider, always in current-session scope
        MemoryScope currentScope = memoryScopeResolver.resolve(request.scope(), "current");

        List<MemoryEntry> allEntries = new ArrayList<>();
        for (MemoryProvider provider : request.providers()) {
            if (!(provider instanceof ScopedMemoryProvider scopedProvider) || !provider.isAvailable()) {
                continue;
            }
            try {
                MemoryResult result = scopedProvider.prefetch(request.query(), request.options(), currentScope);
                if (result != null && !result.isEmpty()) {
                    allEntries.addAll(result.entries());
                }
            } catch (Exception e) {
                log.error("Prefetch failed from provider: {}", provider.getName(), e);
            }
        }

        allEntries.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (allEntries.size() > request.options().topK()) {
            allEntries = allEntries.subList(0, request.options().topK());
        }
        return MemoryResult.of(allEntries, "aggregated");
    }

    @Override
    public MemoryWriteResult write(MemoryWriteRequest request) {
        return defaultStrategy.write(request);
    }

    @Override
    public MemoryTurnResult afterTurn(MemoryTurnRequest request) {
        if (request.scope() != null
                && ("DISABLED".equals(request.scope().getMemoryPolicy()) || !request.scope().isMemoryWriteAllowed())) {
            return new MemoryTurnResult(0);
        }

        int synced = 0;
        for (MemoryProvider provider : request.providers()) {
            if (!(provider instanceof ScopedMemoryProvider scopedProvider) || !provider.isAvailable()) {
                continue;
            }
            try {
                scopedProvider.syncTurn(request.user(), request.assistant(),
                        memoryScopeResolver.resolve(request.scope(), "current"));
                synced++;
            } catch (Exception e) {
                log.error("SyncTurn failed for provider: {}", provider.getName(), e);
            }
        }
        return new MemoryTurnResult(synced);
    }
}
