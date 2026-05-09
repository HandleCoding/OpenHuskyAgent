package io.github.huskyagent.infra.memory;

public interface ScopedMemoryProvider extends MemoryProvider {
    default String buildSystemPrompt(MemoryScope scope, String promptMode) {
        return buildSystemPrompt(promptMode);
    }

    MemoryResult prefetch(String query, MemorySearchOptions options, MemoryScope scope);

    void syncTurn(String user, String assistant, MemoryScope scope);
}
