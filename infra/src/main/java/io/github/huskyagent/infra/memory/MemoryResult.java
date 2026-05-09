package io.github.huskyagent.infra.memory;

import java.util.List;

/**
 * 记忆检索结果
 */
public record MemoryResult(
    List<MemoryEntry> entries,
    String providerName,
    boolean fromCache
) {

    /**
     * 创建空结果
     */
    public static MemoryResult empty(String providerName) {
        return new MemoryResult(List.of(), providerName, false);
    }

    /**
     * 创建结果
     */
    public static MemoryResult of(List<MemoryEntry> entries, String providerName) {
        return new MemoryResult(entries, providerName, false);
    }

    /**
     * 创建缓存结果
     */
    public static MemoryResult cached(List<MemoryEntry> entries, String providerName) {
        return new MemoryResult(entries, providerName, true);
    }

    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    /**
     * 获取条目数量
     */
    public int size() {
        return entries != null ? entries.size() : 0;
    }
}