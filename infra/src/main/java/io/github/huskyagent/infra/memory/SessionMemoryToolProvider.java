package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionMemoryToolProvider implements ToolProvider {

    private final MemoryManager memoryManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionMemoryToolProvider(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(createSessionSearchTool());
    }

    private ToolDefinition createSessionSearchTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
            .put("type", "string")
            .put("description", "Search query for historical messages");
        properties.putObject("top_k")
            .put("type", "integer")
            .put("description", "Maximum number of results to return (default 5)");
        properties.putObject("scope")
            .put("type", "string")
            .put("description", "Search scope: 'current' (default) = only this session, 'all' = sessions allowed by the current scene memory policy")
            .putArray("enum")
            .add("current")
            .add("all");

        schema.putArray("required").add("query");

        return ToolDefinition.contextual(
            "session_search",
            "Search through conversation history using full-text search. " +
            "Use scope='current' (default) to search only this session, or scope='all' to search sessions allowed by the current scene memory policy. " +
            "Use when the user references something discussed earlier, asks 'do you remember...', or needs to recall a previous decision or file path.",
            Toolset.MEMORY,
            schema,
            (args, context) -> {
                SessionScope sessionScope = context != null ? context.sessionScope() : null;
                if (sessionScope == null) {
                    return ToolResult.failure("Memory tool requires runtime scope");
                }
                if ("DISABLED".equals(sessionScope.getMemoryPolicy())) {
                    return ToolResult.failure("Memory is disabled for the current scene");
                }
                if (!memoryManager.isProviderEnabled(sessionScope, SessionMemoryProvider.NAME)) {
                    return ToolResult.failure("Session memory provider is not enabled for the current scene");
                }
                String query = (String) args.get("query");
                int topK = args.containsKey("top_k") ? ((Number) args.get("top_k")).intValue() : 5;
                String scope = args.containsKey("scope") ? (String) args.get("scope") : "current";

                if ("all".equals(scope) && !sessionScope.isAllowCrossSessionMemorySearch()) {
                    return ToolResult.failure("Cross-session memory search is disabled for the current scene");
                }

                if (query == null || query.isBlank()) {
                    return ToolResult.failure("Query is required");
                }

                MemorySearchOptions options = MemorySearchOptions.ofTopK(topK);
                MemoryResult result = memoryManager.searchFromTool(sessionScope, SessionMemoryProvider.NAME, query, options, scope);
                List<MemoryEntry> entries = result.entries();

                if (entries.isEmpty()) {
                    return ToolResult.success("No matching messages found");
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Found %d matching messages:\n\n".formatted(entries.size()));
                for (MemoryEntry entry : entries) {
                    sb.append("- Score: %.2f\n".formatted(entry.score()));
                    sb.append("- Time: %s\n".formatted(
                        entry.timestamp() != null ? entry.timestamp().toString() : "unknown"));
                    sb.append("- Content: %s\n\n".formatted(entry.content()));
                }

                return ToolResult.success(sb.toString());
            }
        );
    }
}
