package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ClarifyTool implements ToolProvider {

    public static final String NAME = "clarify";

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of(
                NAME,
                "Ask the user a clarification question when their intent is ambiguous, a decision is needed, or you need to confirm assumptions before continuing. Provide up to 4 options when useful; omit options for an open-ended question.",
                Toolset.CORE,
                ClarifyArgs.class,
                this::handle));
    }

    private ToolResult handle(Map<String, Object> args) {
        return ToolResult.success("Clarification request submitted.");
    }

    record ClarifyArgs(
            @JsonPropertyDescription("The clear, specific question to ask the user")
            String question,
            @JsonPropertyDescription("Optional answer choices, up to 4 items. Leave empty for an open-ended question.")
            List<String> options
    ) {}
}
