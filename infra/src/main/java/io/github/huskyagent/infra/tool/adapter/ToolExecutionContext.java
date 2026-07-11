package io.github.huskyagent.infra.tool.adapter;

import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.execute.ExecutionBackend;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.workspace.Workspace;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolExecutionContext(
        String sessionId,
        SessionScope sessionScope,
        List<ToolDefinition> visibleTools,
        Set<Toolset> visibleToolsets,
        Set<String> visibleSkillNames,
        Set<String> visiblePromptSections,
        ToolRuntimeEnvironment runtimeEnvironment
) {
    public ToolExecutionContext(String sessionId,
                                SessionScope sessionScope,
                                List<ToolDefinition> visibleTools,
                                Set<Toolset> visibleToolsets,
                                Set<String> visibleSkillNames,
                                Set<String> visiblePromptSections) {
        this(sessionId, sessionScope, visibleTools, visibleToolsets, visibleSkillNames, visiblePromptSections, null);
    }

    public static ToolExecutionContext minimal(String sessionId, List<ToolDefinition> visibleTools) {
        return new ToolExecutionContext(
                sessionId,
                null,
                List.copyOf(visibleTools),
                visibleTools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet()),
                null,
                Set.of(),
                null);
    }

    public static ToolExecutionContext scoped(SessionScope sessionScope, List<ToolDefinition> visibleTools) {
        return scoped(sessionScope, visibleTools, null);
    }

    public static ToolExecutionContext scoped(SessionScope sessionScope, List<ToolDefinition> visibleTools,
                                             ToolRuntimeEnvironment runtimeEnvironment) {
        String sessionId = sessionScope != null ? sessionScope.getSessionId() : null;
        return new ToolExecutionContext(
                sessionId,
                sessionScope,
                List.copyOf(visibleTools),
                visibleTools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet()),
                sessionScope != null ? sessionScope.getVisibleSkillNames() : null,
                Set.of(),
                runtimeEnvironment);
    }

    public boolean hasRuntimeEnvironment() {
        return runtimeEnvironment != null;
    }

    public String backendType() {
        if (runtimeEnvironment != null) {
            return runtimeEnvironment.backendType();
        }
        String backendType = sessionScope != null ? sessionScope.getBackendType() : null;
        String normalized = backendType != null ? backendType.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isEmpty() ? "local" : normalized;
    }

    public boolean isLocalBackend() {
        return "local".equals(backendType());
    }

    public boolean hasFilesystem() {
        if (runtimeEnvironment != null) {
            return runtimeEnvironment.hasFilesystem();
        }
        if (sessionScope != null && sessionScope.getFilesystemAvailable() != null) {
            return sessionScope.getFilesystemAvailable();
        }
        return isLocalBackend();
    }

    public Workspace workspace() {
        if (runtimeEnvironment == null) {
            throw new IllegalStateException("No runtime environment is available for file tools");
        }
        return runtimeEnvironment.workspace();
    }

    public ExecutionBackend executionBackend() {
        if (runtimeEnvironment == null) {
            throw new IllegalStateException("No runtime environment is available for terminal tools");
        }
        return runtimeEnvironment.executionBackend();
    }
}
