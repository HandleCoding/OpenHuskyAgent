package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;

import java.util.List;

public record MemoryLoadRequest(
        SessionScope scope,
        List<MemoryProvider> providers
) {
}
