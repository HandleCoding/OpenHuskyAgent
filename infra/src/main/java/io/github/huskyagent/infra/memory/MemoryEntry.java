package io.github.huskyagent.infra.memory;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单条记忆条目
 */
public record MemoryEntry(
    String id,
    String content,
    double score,
    LocalDateTime timestamp,
    String source,
    Map<String, Object> metadata
) {

    /**
     * 创建简单条目
     */
    public static MemoryEntry of(String id, String content, double score, String source) {
        return new MemoryEntry(id, content, score, null, source, Map.of());
    }

    /**
     * 创建带时间戳的条目
     */
    public static MemoryEntry of(String id, String content, double score, LocalDateTime timestamp, String source) {
        return new MemoryEntry(id, content, score, timestamp, source, Map.of());
    }

    /**
     * 创建带元数据的条目
     */
    public static MemoryEntry of(String id, String content, double score, LocalDateTime timestamp,
                                  String source, Map<String, Object> metadata) {
        return new MemoryEntry(id, content, score, timestamp, source, metadata);
    }
}