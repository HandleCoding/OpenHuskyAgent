package io.github.huskyagent.infra.memory;

import java.time.LocalDateTime;
import java.util.Map;

public record MemoryEntry(
    String id,
    String content,
    double score,
    LocalDateTime timestamp,
    String source,
    Map<String, Object> metadata
) {

    public static MemoryEntry of(String id, String content, double score, String source) {
        return new MemoryEntry(id, content, score, null, source, Map.of());
    }

    public static MemoryEntry of(String id, String content, double score, LocalDateTime timestamp, String source) {
        return new MemoryEntry(id, content, score, timestamp, source, Map.of());
    }

    public static MemoryEntry of(String id, String content, double score, LocalDateTime timestamp,
                                  String source, Map<String, Object> metadata) {
        return new MemoryEntry(id, content, score, timestamp, source, metadata);
    }
}