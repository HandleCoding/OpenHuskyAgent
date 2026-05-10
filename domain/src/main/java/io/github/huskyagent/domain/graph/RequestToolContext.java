package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RequestToolContext(List<ToolDefinition> toolDefinitions,
                                 List<ToolCallback> toolCallbacks,
                                 SpringAIToolService toolService,
                                 Map<String, ToolDefinition> toolDefinitionMap,
                                 Set<String> approvalToolNames) {

    public static final String METADATA_KEY = "requestToolContext";

    public RequestToolContext {
        toolDefinitions = List.copyOf(toolDefinitions != null ? toolDefinitions : List.of());
        toolCallbacks = List.copyOf(toolCallbacks != null ? toolCallbacks : List.of());
        toolDefinitionMap = Map.copyOf(toolDefinitionMap != null ? toolDefinitionMap : Map.of());
        approvalToolNames = Set.copyOf(approvalToolNames != null ? approvalToolNames : Set.of());
    }

    public static RequestToolContext of(List<ToolDefinition> toolDefinitions, List<ToolCallback> toolCallbacks) {
        Map<String, ToolDefinition> toolDefinitionMap = new HashMap<>();
        for (ToolDefinition definition : toolDefinitions) {
            toolDefinitionMap.put(definition.name(), definition);
        }
        return new RequestToolContext(
                toolDefinitions,
                toolCallbacks,
                new SpringAIToolService(toolCallbacks),
                toolDefinitionMap,
                GraphUtils.collectApprovalToolNames(toolDefinitions));
    }

    public static RequestToolContext from(RunnableConfig config) {
        if (config == null) {
            throw new IllegalStateException("Request tool context is missing: runnable config is null");
        }
        return config.metadata(METADATA_KEY)
                .filter(RequestToolContext.class::isInstance)
                .map(RequestToolContext.class::cast)
                .orElseThrow(() -> new IllegalStateException("Request tool context is missing from runnable metadata"));
    }
}
