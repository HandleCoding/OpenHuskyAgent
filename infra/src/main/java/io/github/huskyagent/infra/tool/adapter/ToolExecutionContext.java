package io.github.huskyagent.infra.tool.adapter;

import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolExecutionContext(
        String sessionId,
        SessionScope sessionScope,
        List<ToolDefinition> visibleTools,
        Set<Toolset> visibleToolsets,
        Set<String> visibleSkillNames,
        Set<String> visiblePromptSections
) {

    public static ToolExecutionContext minimal(String sessionId, List<ToolDefinition> visibleTools) {
        return new ToolExecutionContext(
                sessionId,
                null,
                List.copyOf(visibleTools),
                visibleTools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet()),
                null,
                Set.of());
    }

    public static ToolExecutionContext scoped(SessionScope sessionScope, List<ToolDefinition> visibleTools) {
        String sessionId = sessionScope != null ? sessionScope.getSessionId() : null;
        return new ToolExecutionContext(
                sessionId,
                sessionScope,
                List.copyOf(visibleTools),
                visibleTools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet()),
                sessionScope != null ? sessionScope.getVisibleSkillNames() : null,
                Set.of());
    }
}
