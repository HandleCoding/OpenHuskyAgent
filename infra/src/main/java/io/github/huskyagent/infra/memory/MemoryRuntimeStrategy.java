package io.github.huskyagent.infra.memory;

public interface MemoryRuntimeStrategy {
    String id();

    MemoryLoadResult loadForPrompt(MemoryLoadRequest request);

    MemoryResult search(MemorySearchRequest request);

    MemoryWriteResult write(MemoryWriteRequest request);

    MemoryTurnResult afterTurn(MemoryTurnRequest request);
}
