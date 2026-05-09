package io.github.huskyagent.infra.memory;

/**
 * 记忆检索选项
 */
public record MemorySearchOptions(
    int topK,
    double minScore,
    SortBy sortBy,
    long timeRangeStart,
    long timeRangeEnd
) {

    /**
     * 默认选项
     */
    public static MemorySearchOptions defaultOptions() {
        return new MemorySearchOptions(5, 0.3, SortBy.RELEVANCE, 0, Long.MAX_VALUE);
    }

    /**
     * 创建指定 TopK 的选项
     */
    public static MemorySearchOptions ofTopK(int topK) {
        return new MemorySearchOptions(topK, 0.3, SortBy.RELEVANCE, 0, Long.MAX_VALUE);
    }

    /**
     * 创建带时间范围的选项
     */
    public static MemorySearchOptions ofTimeRange(long start, long end) {
        return new MemorySearchOptions(5, 0.3, SortBy.RELEVANCE, start, end);
    }

    /**
     * 是否有时间范围筛选
     */
    public boolean hasTimeRange() {
        return timeRangeStart > 0 || timeRangeEnd < Long.MAX_VALUE;
    }
}