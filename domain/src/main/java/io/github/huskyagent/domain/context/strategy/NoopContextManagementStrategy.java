package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.domain.context.ContextManagementRequest;
import io.github.huskyagent.domain.context.ContextManagementResult;
import io.github.huskyagent.domain.context.ContextManagementStrategy;
import org.springframework.stereotype.Component;

@Component
public class NoopContextManagementStrategy implements ContextManagementStrategy {
    @Override
    public String id() {
        return "none";
    }

    @Override
    public ContextManagementResult prepare(ContextManagementRequest request) {
        return ContextManagementResult.unchanged(request.persistedMessages(), id(), "none");
    }
}
