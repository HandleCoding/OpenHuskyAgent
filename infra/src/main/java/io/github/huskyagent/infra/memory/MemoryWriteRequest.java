package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;

import java.util.List;
import java.util.Map;

public record MemoryWriteRequest(
        SessionScope scope,
        List<MemoryProvider> providers,
        String toolName,
        Map<String, Object> arguments
) {
}
