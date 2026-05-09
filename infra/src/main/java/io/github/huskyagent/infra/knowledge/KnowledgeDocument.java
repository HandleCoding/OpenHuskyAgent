package io.github.huskyagent.infra.knowledge;

import java.time.Instant;
import java.util.Map;

public record KnowledgeDocument(
        String id,
        String title,
        String content,
        String source,
        String provider,
        Instant updatedAt,
        Map<String, Object> metadata
) {}
