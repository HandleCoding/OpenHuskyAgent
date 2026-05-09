package io.github.huskyagent.infra.memory;

/**
 * 记忆检索排序方式
 */
public enum SortBy {
    /**
     * 按相关性评分排序
     */
    RELEVANCE,

    /**
     * 按时间排序（最新优先）
     */
    RECENCY
}