package io.github.huskyagent.infra.web;

/**
 * 搜索后端接口
 * 支持 Brave Search、Tavily 等搜索引擎的统一抽象
 */
public interface SearchBackend {

    /**
     * 执行搜索并返回标准化结果
     *
     * @param query 搜索关键词
     * @param limit 最大结果数量
     * @return 搜索结果
     */
    WebSearchResult search(String query, int limit);
}