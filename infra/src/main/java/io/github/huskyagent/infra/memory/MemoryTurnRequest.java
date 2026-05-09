package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;

import java.util.List;

public record MemoryTurnRequest(
        SessionScope scope,
        List<MemoryProvider> providers,
        String user,
        String assistant
) {
}
