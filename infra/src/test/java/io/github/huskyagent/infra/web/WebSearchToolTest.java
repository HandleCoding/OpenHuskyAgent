package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.http.HttpClientFactory;
import io.github.huskyagent.infra.http.NoProxyMatcher;
import io.github.huskyagent.infra.http.ProxyResolver;
import io.github.huskyagent.infra.config.ProxyProperties;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.impl.WebSearchTool;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private WebConfig config;

    @BeforeEach
    void setUp() {
        config = new WebConfig();
        config.setBraveApiKey("test-api-key");
    }

    @Test
    void testGetToolsWhenBackendAvailable() {
        WebSearchTool tool = new WebSearchTool(new SearchBackendFactory(newBraveSearchBackend(), config), config);

        List<ToolDefinition> tools = tool.getTools();
        assertEquals(1, tools.size());
        assertEquals("web_search", tools.get(0).name());
        assertEquals(Toolset.WEB, tools.get(0).toolset());
    }

    @Test
    void testGetToolsWhenBackendNotAvailable() {
        config.setBackend("none");
        WebSearchTool tool = new WebSearchTool(new SearchBackendFactory(newBraveSearchBackend(), config), config);

        List<ToolDefinition> tools = tool.getTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void testHandleMissingQuery() {
        WebSearchTool tool = new WebSearchTool(new SearchBackendFactory(newBraveSearchBackend(), config), config);
        ToolResult result = tool.handle(Map.of());

        assertFalse(result.success());
        assertTrue(result.error().contains("required"));
    }

    @Test
    void testHandleBlankQuery() {
        WebSearchTool tool = new WebSearchTool(new SearchBackendFactory(newBraveSearchBackend(), config), config);
        ToolResult result = tool.handle(Map.of("query", "  "));

        assertFalse(result.success());
        assertTrue(result.error().contains("required"));
    }

    private BraveSearchBackend newBraveSearchBackend() {
        ProxyProperties properties = new ProxyProperties();
        properties.setEnvEnabled(false);
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());
        return new BraveSearchBackend(config, new HttpClientFactory(resolver));
    }
}