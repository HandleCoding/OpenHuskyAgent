package io.github.huskyagent.infra.memory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MemoryRuntimeStrategyResolver {
    private final Map<String, MemoryRuntimeStrategy> strategies;

    public MemoryRuntimeStrategyResolver(List<MemoryRuntimeStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(MemoryRuntimeStrategy::id, Function.identity()));
    }

    public MemoryRuntimeStrategy resolve(String id) {
        String effectiveId = id != null && !id.isBlank() ? id : "default";
        MemoryRuntimeStrategy strategy = strategies.get(effectiveId);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown memory strategy: " + effectiveId);
        }
        return strategy;
    }

    public Set<String> ids() {
        return strategies.keySet();
    }
}
