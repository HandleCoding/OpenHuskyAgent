package io.github.huskyagent.infra.knowledge;

import java.util.Map;

public record KnowledgeQuery(
        String query,
        int topK,
        String source,
        Map<String, Object> metadata
) {
    public static KnowledgeQuery of(String query, int topK, String source) {
        return new KnowledgeQuery(query, topK, source, Map.of());
    }
}
