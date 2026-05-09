package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;

import java.util.List;

public record MemorySearchRequest(
        SessionScope scope,
        List<MemoryProvider> providers,
        String query,
        MemorySearchOptions options,
        String requestedScope,
        MemorySearchTrigger trigger
) {
}
