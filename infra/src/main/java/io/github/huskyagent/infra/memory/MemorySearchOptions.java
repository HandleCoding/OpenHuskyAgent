package io.github.huskyagent.infra.memory;

public record MemorySearchOptions(
    int topK,
    double minScore,
    SortBy sortBy,
    long timeRangeStart,
    long timeRangeEnd
) {

    public static MemorySearchOptions defaultOptions() {
        return new MemorySearchOptions(5, 0.3, SortBy.RELEVANCE, 0, Long.MAX_VALUE);
    }

    public static MemorySearchOptions ofTopK(int topK) {
        return new MemorySearchOptions(topK, 0.3, SortBy.RELEVANCE, 0, Long.MAX_VALUE);
    }

    public static MemorySearchOptions ofTimeRange(long start, long end) {
        return new MemorySearchOptions(5, 0.3, SortBy.RELEVANCE, start, end);
    }

    public boolean hasTimeRange() {
        return timeRangeStart > 0 || timeRangeEnd < Long.MAX_VALUE;
    }
}