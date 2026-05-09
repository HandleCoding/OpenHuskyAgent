package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.web.SearchBackend;
import io.github.huskyagent.infra.web.SearchBackendFactory;
import io.github.huskyagent.infra.web.WebSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Web 搜索工具
 * 通过 Brave Search API 搜索互联网内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements ToolProvider {

    private final SearchBackendFactory backendFactory;
    private final WebConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ToolDefinition> getTools() {
        if (!config.isBackendAvailable()) {
            log.warn("No web search backend configured, web_search tool will not be registered");
            return List.of();
        }
        log.info("Registered web_search tool (backend: {})", config.resolveBackend());
        return List.of(buildDefinition());
    }

    private ToolDefinition buildDefinition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        ObjectNode queryNode = props.putObject("query");
        queryNode.put("type", "string");
        queryNode.put("description", "Search query to look up on the web");

        ObjectNode limitNode = props.putObject("limit");
        limitNode.put("type", "integer");
        limitNode.put("description", "Maximum number of results (default 5, max 20)");
        limitNode.put("default", config.getDefaultSearchLimit());

        ArrayNode required = schema.putArray("required");
        required.add("query");

        String backend = config.resolveBackend();
        String[] envVars = "brave".equals(backend)
            ? new String[]{"BRAVE_SEARCH_API_KEY"}
            : new String[]{"TAVILY_API_KEY"};

        return ToolDefinition.of(
                "web_search",
                "Search the web for information. Returns titles, URLs, and descriptions of relevant pages.",
                Toolset.WEB,
                schema,
                this::handle)
                .withEnabled(config.isBackendAvailable())
                .withRequiredEnvVars(envVars)
                .withEmoji("🔍")
                .withMaxResultSize(config.getMaxOutputChars() * 20);
    }

    public ToolResult handle(Map<String, Object> args) {
        String query = (String) args.get("query");
        int limit = args.containsKey("limit")
            ? Math.min(((Number) args.get("limit")).intValue(), config.getMaxSearchLimit())
            : config.getDefaultSearchLimit();

        if (query == null || query.isBlank()) {
            return ToolResult.failure("query is required", false, "Provide a search query string");
        }

        try {
            SearchBackend backend = backendFactory.getBackend();
            WebSearchResult result = backend.search(query, limit);

            if (!result.success()) {
                return ToolResult.failure(result.error(), true, "Check backend API key and connectivity");
            }

            List<Map<String, Object>> webEntries = result.entries().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", e.title());
                    m.put("url", e.url());
                    m.put("description", e.description());
                    m.put("position", e.position());
                    return m;
                })
                .collect(Collectors.toList());

            return ToolResult.success(Map.of(
                "success", true,
                "data", Map.of("web", webEntries)
            ));

        } catch (IllegalStateException e) {
            return ToolResult.failure(e.getMessage(), false, "Configure a search backend API key");
        } catch (Exception e) {
            log.error("web_search failed: {}", e.getMessage());
            return ToolResult.failure("Search failed: " + e.getMessage(), true,
                "Check backend API key and connectivity");
        }
    }
}
