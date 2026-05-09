package io.github.huskyagent.infra.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMemoryRuntimeStrategy implements MemoryRuntimeStrategy {
    private final MemoryScopeResolver memoryScopeResolver;

    @Override
    public String id() {
        return "default";
    }

    @Override
    public MemoryLoadResult loadForPrompt(MemoryLoadRequest request) {
        if (request.scope() != null && ("DISABLED".equals(request.scope().getMemoryPolicy())
                || "NONE".equals(request.scope().getMemoryPromptMode()))) {
            return MemoryLoadResult.empty();
        }

        StringBuilder sb = new StringBuilder();
        MemoryScope promptScope = memoryScopeResolver.resolve(request.scope(), "current");
        String promptMode = request.scope().getMemoryPromptMode();
        for (MemoryProvider provider : request.providers()) {
            if (!provider.isAvailable()) {
                continue;
            }
            try {
                String prompt = provider instanceof ScopedMemoryProvider scopedProvider
                        ? scopedProvider.buildSystemPrompt(promptScope, promptMode)
                        : provider.buildSystemPrompt(promptMode);
                if (prompt != null && !prompt.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(prompt);
                }
            } catch (Exception e) {
                log.error("Failed to build system prompt from provider: {}", provider.getName(), e);
            }
        }
        return new MemoryLoadResult(sb.toString());
    }

    @Override
    public MemoryResult search(MemorySearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return MemoryResult.empty("aggregated");
        }
        if (request.scope() != null && "DISABLED".equals(request.scope().getMemoryPolicy())) {
            return MemoryResult.empty("disabled");
        }
        if ("all".equals(request.requestedScope()) && request.scope() != null
                && !request.scope().isAllowCrossSessionMemorySearch()) {
            return MemoryResult.empty("cross-session-disabled");
        }

        List<MemoryEntry> allEntries = new ArrayList<>();
        for (MemoryProvider provider : request.providers()) {
            if (!provider.isAvailable()) {
                continue;
            }
            try {
                MemoryResult result = provider instanceof ScopedMemoryProvider scopedProvider
                        ? scopedProvider.prefetch(request.query(), request.options(),
                                memoryScopeResolver.resolve(request.scope(), request.requestedScope()))
                        : provider.prefetch(request.query(), request.options());
                if (result != null && !result.isEmpty()) {
                    allEntries.addAll(result.entries());
                    log.debug("Prefetch from {}: {} entries", provider.getName(), result.size());
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
        if (request.scope() != null) {
            if ("DISABLED".equals(request.scope().getMemoryPolicy())) {
                return MemoryWriteResult.failure("Memory is disabled for the current scene");
            }
            if (!request.scope().isMemoryWriteAllowed()) {
                return MemoryWriteResult.failure("Memory writes are disabled for the current scene");
            }
        }

        for (MemoryProvider provider : request.providers()) {
            if (provider instanceof BuiltinMemoryProvider builtinMemoryProvider) {
                return MemoryWriteResult.success(builtinMemoryProvider.handleToolCall(request.toolName(), request.arguments()));
            }
        }
        return MemoryWriteResult.failure("Builtin memory provider is not enabled for the current scene");
    }

    @Override
    public MemoryTurnResult afterTurn(MemoryTurnRequest request) {
        if (request.scope() != null
                && ("DISABLED".equals(request.scope().getMemoryPolicy()) || !request.scope().isMemoryWriteAllowed())) {
            return new MemoryTurnResult(0);
        }

        int synced = 0;
        for (MemoryProvider provider : request.providers()) {
            if (!provider.isAvailable()) {
                continue;
            }
            try {
                if (provider instanceof ScopedMemoryProvider scopedProvider) {
                    scopedProvider.syncTurn(request.user(), request.assistant(),
                            memoryScopeResolver.resolve(request.scope(), "current"));
                } else {
                    provider.syncTurn(request.user(), request.assistant());
                }
                synced++;
            } catch (Exception e) {
                log.error("SyncTurn failed for provider: {}", provider.getName(), e);
            }
        }
        return new MemoryTurnResult(synced);
    }
}
