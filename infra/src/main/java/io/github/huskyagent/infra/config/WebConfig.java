package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Web 工具配置
 * 支持 Brave Search 和 Tavily 搜索后端
 */
@Data
@Component
@ConfigurationProperties(prefix = "web")
public class WebConfig {

    /** Brave Search API endpoint URL */
    private String braveApiUrl = "https://api.search.brave.com/res/v1/web/search";

    /** Tavily API endpoint URL */
    private String tavilyApiUrl = "https://api.tavily.com/search";

    /** 搜索后端: "brave", "tavily", 或 "auto"(自动检测) */
    private String backend = "auto";

    /** Brave Search API key (也可通过 BRAVE_SEARCH_API_KEY 环境变量设置) */
    private String braveApiKey;

    /** Tavily API key (也可通过 TAVILY_API_KEY 环境变量设置) */
    private String tavilyApiKey;

    /** 默认搜索结果数量 */
    private int defaultSearchLimit = 5;

    /** 最大搜索结果数量 */
    private int maxSearchLimit = 20;

    /** 最大抓取内容大小 (字节)，超过则拒绝 */
    private int maxFetchSizeBytes = 2_000_000;

    /** 触发 LLM 摘要的最小内容长度 (字符) */
    private int summarizeThresholdChars = 5000;

    /** 摘要后最大输出长度 (字符) */
    private int maxOutputChars = 5000;

    /** HTTP 请求超时 (秒) */
    private int requestTimeoutSeconds = 30;

    /** 出站网络代理 (web_search, web_fetch, browser 共用) */
    private ProxySettings proxy = new ProxySettings();

    /** 域名黑名单 */
    private List<String> blockedDomains = List.of();

    /** 自动检测后端: config值 → API key检测 → 环境变量 → "none" */
    public String resolveBackend() {
        if (!"auto".equals(backend)) {
            return backend.toLowerCase();
        }
        if (braveApiKey != null && !braveApiKey.isBlank()) return "brave";
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) return "tavily";
        String braveEnv = System.getenv("BRAVE_SEARCH_API_KEY");
        String tavilyEnv = System.getenv("TAVILY_API_KEY");
        if (braveEnv != null && !braveEnv.isBlank()) return "brave";
        if (tavilyEnv != null && !tavilyEnv.isBlank()) return "tavily";
        return "none";
    }

    /** Brave API key: config → env → 空 */
    public String resolveBraveApiKey() {
        if (braveApiKey != null && !braveApiKey.isBlank()) return braveApiKey;
        String env = System.getenv("BRAVE_SEARCH_API_KEY");
        return env != null ? env : "";
    }

    /** Tavily API key: config → env → 空 */
    public String resolveTavilyApiKey() {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) return tavilyApiKey;
        String env = System.getenv("TAVILY_API_KEY");
        return env != null ? env : "";
    }

    /** 后端是否可用 */
    public boolean isBackendAvailable() {
        String b = resolveBackend();
        if ("brave".equals(b)) return !resolveBraveApiKey().isEmpty();
        if ("tavily".equals(b)) return !resolveTavilyApiKey().isEmpty();
        return false;
    }

    @Data
    public static class ProxySettings {
        private Boolean enabled;
        private String url;
        private String noProxy;
    }
}