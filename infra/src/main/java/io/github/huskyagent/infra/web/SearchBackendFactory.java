package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.config.WebConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchBackendFactory {

    private final BraveSearchBackend braveBackend;
    private final WebConfig config;

    public SearchBackend getBackend() {
        String backend = config.resolveBackend();
        return switch (backend) {
            case "brave" -> braveBackend;
            // case "tavily" -> tavilyBackend;
            default -> throw new IllegalStateException("No web search backend configured. Set web.brave-api-key or BRAVE_SEARCH_API_KEY env var.");
        };
    }
}