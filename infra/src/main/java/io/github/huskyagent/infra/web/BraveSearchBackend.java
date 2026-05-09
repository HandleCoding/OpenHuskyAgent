package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.http.HttpClientFactory;
import io.github.huskyagent.infra.http.ProxyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BraveSearchBackend implements SearchBackend {

    private final WebConfig config;
    private final HttpClientFactory httpClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public WebSearchResult search(String query, int limit) {
        String apiKey = config.resolveBraveApiKey();
        if (apiKey.isEmpty()) {
            return WebSearchResult.failure("Brave Search API key not configured. Set web.brave-api-key or BRAVE_SEARCH_API_KEY env var.");
        }

        try {
            String url = config.getBraveApiUrl() + "?q=" + encodeQuery(query) + "&count=" + limit;

            URI uri = URI.create(url);
            HttpClient client = httpClientFactory.create(
                uri,
                Duration.ofSeconds(config.getRequestTimeoutSeconds()),
                webProxyOptions()
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Brave Search API returned status {}: {}", response.statusCode(), response.body());
                return WebSearchResult.failure("Brave Search API error (HTTP " + response.statusCode() + ")");
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("Brave Search request failed: {}", e.getMessage());
            return WebSearchResult.failure("Search request failed: " + e.getMessage());
        }
    }

    private WebSearchResult parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode webNode = root.path("web");
            JsonNode resultsNode = webNode.path("results");

            if (!resultsNode.isArray()) {
                log.warn("Brave Search response missing web.results array");
                return WebSearchResult.success(List.of());
            }

            List<WebSearchResult.SearchEntry> entries = new ArrayList<>();
            int position = 1;
            for (JsonNode result : resultsNode) {
                String title = result.path("title").asText("");
                String resultUrl = result.path("url").asText("");
                String description = result.path("description").asText("");
                if (title.isEmpty() && resultUrl.isEmpty()) continue;

                entries.add(new WebSearchResult.SearchEntry(
                    title, resultUrl, description, position++
                ));
            }

            return WebSearchResult.success(entries);

        } catch (Exception e) {
            log.error("Failed to parse Brave Search response: {}", e.getMessage());
            return WebSearchResult.failure("Failed to parse search results");
        }
    }

    private ProxyResolver.ServiceProxyOptions webProxyOptions() {
        WebConfig.ProxySettings proxy = config.getProxy();
        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        if (proxy != null) {
            options.setEnabled(proxy.getEnabled());
            options.setUrl(proxy.getUrl());
            options.setNoProxy(proxy.getNoProxy());
        }
        return options;
    }

    private String encodeQuery(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}