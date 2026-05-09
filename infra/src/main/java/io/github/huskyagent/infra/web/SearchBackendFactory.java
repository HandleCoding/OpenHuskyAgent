package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.config.WebConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 搜索后端工厂
 * 根据 WebConfig 配置选择对应的搜索后端
 */
@Component
@RequiredArgsConstructor
public class SearchBackendFactory {

    private final BraveSearchBackend braveBackend;
    private final WebConfig config;

    /**
     * 获取当前配置的搜索后端
     *
     * @return 对应的 SearchBackend 实例
     * @throws IllegalStateException 如果没有可用的后端
     */
    public SearchBackend getBackend() {
        String backend = config.resolveBackend();
        return switch (backend) {
            case "brave" -> braveBackend;
            // 后续添加 Tavily:
            // case "tavily" -> tavilyBackend;
            default -> throw new IllegalStateException("No web search backend configured. Set web.brave-api-key or BRAVE_SEARCH_API_KEY env var.");
        };
    }
}