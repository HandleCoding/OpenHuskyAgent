package io.github.huskyagent.infra.knowledge;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KnowledgeToolProvider implements ToolProvider {
    private final KnowledgeManager knowledgeManager;

    record SearchArgs(
            @JsonPropertyDescription("Search query for external knowledge sources")
            String query,
            @JsonPropertyDescription("Knowledge source/provider id to search, such as 'local-docs'. Omit to search all enabled sources.")
            String source,
            @JsonPropertyDescription("Maximum number of results to return. Default follows knowledge.default-top-k.")
            Integer top_k
    ) {}

    record FetchArgs(
            @JsonPropertyDescription("Document id returned by knowledge_search")
            String id
    ) {}

    public KnowledgeToolProvider(KnowledgeManager knowledgeManager) {
        this.knowledgeManager = knowledgeManager;
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
                ToolDefinition.of("knowledge_search",
                        "Search external knowledge sources configured for the current scene. Use this for project docs, business knowledge, runbooks, and other factual references outside the conversation memory.",
                        Toolset.KNOWLEDGE, SearchArgs.class, this::search),
                ToolDefinition.of("knowledge_fetch",
                        "Fetch the full content for a document returned by knowledge_search. Use the returned document id exactly.",
                        Toolset.KNOWLEDGE, FetchArgs.class, this::fetch)
        );
    }

    private ToolResult search(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return ToolResult.failure("query is required");
        }
        String source = (String) args.get("source");
        int topK = args.get("top_k") instanceof Number n ? n.intValue() : 0;
        List<KnowledgeResult> results = knowledgeManager.search(KnowledgeQuery.of(query, topK, source), enabledSources());
        if (results.isEmpty()) {
            return ToolResult.success("No matching knowledge documents found");
        }
        List<Map<String, Object>> payload = results.stream().map(this::toMap).toList();
        return ToolResult.success(Map.of("results", payload, "total", payload.size()));
    }

    private ToolResult fetch(Map<String, Object> args) {
        String id = (String) args.get("id");
        if (id == null || id.isBlank()) {
            return ToolResult.failure("id is required");
        }
        return knowledgeManager.fetch(id, enabledSources())
                .map(document -> ToolResult.success(toMap(document)))
                .orElseGet(() -> ToolResult.failure("Knowledge document not found or not enabled for the current scene"));
    }

    private Set<String> enabledSources() {
        return SessionContext.getScope() != null ? SessionContext.getScope().getKnowledgeSourceIds() : Set.of();
    }

    private Map<String, Object> toMap(KnowledgeResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", result.id());
        map.put("title", result.title());
        map.put("snippet", result.snippet());
        map.put("source", result.source());
        map.put("provider", result.provider());
        map.put("score", result.score());
        map.put("updatedAt", result.updatedAt());
        map.put("metadata", result.metadata());
        return map;
    }

    private Map<String, Object> toMap(KnowledgeDocument document) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", document.id());
        map.put("title", document.title());
        map.put("content", document.content());
        map.put("source", document.source());
        map.put("provider", document.provider());
        map.put("updatedAt", document.updatedAt());
        map.put("metadata", document.metadata());
        return map;
    }
}
