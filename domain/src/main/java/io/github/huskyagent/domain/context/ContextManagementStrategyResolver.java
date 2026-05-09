package io.github.huskyagent.domain.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ContextManagementStrategyResolver {
    private final Map<String, ContextManagementStrategy> strategies;

    public ContextManagementStrategyResolver(List<ContextManagementStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(ContextManagementStrategy::id, Function.identity()));
    }

    public ContextManagementStrategy resolve(String id) {
        String effectiveId = id != null && !id.isBlank() ? id : "default";
        ContextManagementStrategy strategy = strategies.get(effectiveId);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown context strategy: " + effectiveId);
        }
        return strategy;
    }

    public boolean exists(String id) {
        String effectiveId = id != null && !id.isBlank() ? id : "default";
        return strategies.containsKey(effectiveId);
    }

    public Set<String> ids() {
        return strategies.keySet();
    }
}
