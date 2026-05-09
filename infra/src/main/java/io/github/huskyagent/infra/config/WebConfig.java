package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "web")
public class WebConfig {

    /** Brave Search API endpoint URL */
    private String braveApiUrl = "https://api.search.brave.com/res/v1/web/search";

    /** Tavily API endpoint URL */
    private String tavilyApiUrl = "https://api.tavily.com/search";

    private String backend = "auto";

    private String braveApiKey;

    private String tavilyApiKey;

    private int defaultSearchLimit = 5;

    private int maxSearchLimit = 20;

    private int maxFetchSizeBytes = 2_000_000;

    private int summarizeThresholdChars = 5000;

    private int maxOutputChars = 5000;

    private int requestTimeoutSeconds = 30;

    private ProxySettings proxy = new ProxySettings();

    private List<String> blockedDomains = List.of();

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

    public String resolveBraveApiKey() {
        if (braveApiKey != null && !braveApiKey.isBlank()) return braveApiKey;
        String env = System.getenv("BRAVE_SEARCH_API_KEY");
        return env != null ? env : "";
    }

    public String resolveTavilyApiKey() {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) return tavilyApiKey;
        String env = System.getenv("TAVILY_API_KEY");
        return env != null ? env : "";
    }

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