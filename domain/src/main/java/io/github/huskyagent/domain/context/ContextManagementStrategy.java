package io.github.huskyagent.domain.context;

public interface ContextManagementStrategy {
    String id();

    ContextManagementResult prepare(ContextManagementRequest request);
}
