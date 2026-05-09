package io.github.huskyagent.infra.memory;

import java.util.List;

public record MemoryResult(
    List<MemoryEntry> entries,
    String providerName,
    boolean fromCache
) {

    public static MemoryResult empty(String providerName) {
        return new MemoryResult(List.of(), providerName, false);
    }

    public static MemoryResult of(List<MemoryEntry> entries, String providerName) {
        return new MemoryResult(entries, providerName, false);
    }

    public static MemoryResult cached(List<MemoryEntry> entries, String providerName) {
        return new MemoryResult(entries, providerName, true);
    }

    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    public int size() {
        return entries != null ? entries.size() : 0;
    }
}