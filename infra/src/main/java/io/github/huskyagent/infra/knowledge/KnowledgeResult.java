package io.github.huskyagent.infra.knowledge;

import java.time.Instant;
import java.util.Map;

public record KnowledgeResult(
        String id,
        String title,
        String snippet,
        String source,
        String provider,
        double score,
        Instant updatedAt,
        Map<String, Object> metadata
) {}
