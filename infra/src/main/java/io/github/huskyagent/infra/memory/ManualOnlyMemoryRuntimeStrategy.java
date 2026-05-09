package io.github.huskyagent.infra.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * manual-only memory strategy: no automatic prompt injection or turn sync.
 * Memory is only read/written when the agent explicitly calls a memory tool.
 * Automatic prefetch is suppressed; tool-triggered search still executes normally.
 */
@Component
@RequiredArgsConstructor
public class ManualOnlyMemoryRuntimeStrategy implements MemoryRuntimeStrategy {

    private final DefaultMemoryRuntimeStrategy defaultStrategy;

    @Override
    public String id() {
        return "manual-only";
    }

    @Override
    public MemoryLoadResult loadForPrompt(MemoryLoadRequest request) {
        return MemoryLoadResult.empty();
    }

    @Override
    public MemoryResult search(MemorySearchRequest request) {
        if (request.trigger() == MemorySearchTrigger.PREFETCH) {
            return MemoryResult.empty("manual-only");
        }
        return defaultStrategy.search(request);
    }

    @Override
    public MemoryWriteResult write(MemoryWriteRequest request) {
        return defaultStrategy.write(request);
    }

    @Override
    public MemoryTurnResult afterTurn(MemoryTurnRequest request) {
        return new MemoryTurnResult(0);
    }
}
