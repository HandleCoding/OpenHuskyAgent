package io.github.huskyagent.infra.memory;

public interface MemoryProvider {

    String getName();

    boolean isAvailable();

    /** Initializes the provider for the current session or workspace context. */
    void initialize(MemoryContext context);

    /** Builds memory content that should be injected into the system prompt. */
    String buildSystemPrompt();

    default String buildSystemPrompt(String promptMode) {
        return buildSystemPrompt();
    }

    /** Prefetches relevant memories before the model call. */
    MemoryResult prefetch(String query, MemorySearchOptions options);

    /** Persists turn-level memory updates after a user/assistant exchange. */
    default void syncTurn(String user, String assistant) {
    }
}
