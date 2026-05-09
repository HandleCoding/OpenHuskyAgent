package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.web.WebContentProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaExposesJinaOption() {
        WebConfig config = new WebConfig();
        config.setBraveApiKey("test-key");
        WebFetchTool tool = new WebFetchTool(new CapturingProcessor(), config);

        List<ToolDefinition> definitions = tool.getTools();

        assertEquals(1, definitions.size());
        assertTrue(definitions.get(0).description().contains("useJina=true"));
        assertTrue(definitions.get(0).parametersSchema().get("properties").has("useJina"));
    }

    @Test
    void handleUsesOriginalUrlByDefault() throws Exception {
        WebConfig config = new WebConfig();
        CapturingProcessor processor = new CapturingProcessor();
        WebFetchTool tool = new WebFetchTool(processor, config);

        ToolResult result = tool.handle(Map.of("url", "example.com", "summarize", false));

        assertTrue(result.success());
        assertEquals("https://example.com", processor.lastUrl);
        assertFalse(processor.lastSummarize);
        Map<?, ?> json = objectMapper.readValue(result.content(), Map.class);
        assertEquals("https://example.com", json.get("url"));
        assertEquals("https://example.com", json.get("fetchedUrl"));
        assertEquals(false, json.get("usedJina"));
    }

    @Test
    void handlePrefixesUrlWithJinaReaderWhenEnabled() throws Exception {
        WebConfig config = new WebConfig();
        CapturingProcessor processor = new CapturingProcessor();
        WebFetchTool tool = new WebFetchTool(processor, config);

        ToolResult result = tool.handle(Map.of("url", "https://example.com/page", "useJina", true));

        assertTrue(result.success());
        assertEquals("https://r.jina.ai/https://example.com/page", processor.lastUrl);
        Map<?, ?> json = objectMapper.readValue(result.content(), Map.class);
        assertEquals("https://example.com/page", json.get("url"));
        assertEquals("https://r.jina.ai/https://example.com/page", json.get("fetchedUrl"));
        assertEquals(true, json.get("usedJina"));
    }

    @Test
    void jinaReaderUrlPrefixesOriginalUrl() {
        WebFetchTool tool = new WebFetchTool(new CapturingProcessor(), new WebConfig());

        assertEquals("https://r.jina.ai/https://example.com", tool.jinaReaderUrl("https://example.com"));
    }

    private static class CapturingProcessor extends WebContentProcessor {
        private String lastUrl;
        private boolean lastSummarize;

        CapturingProcessor() {
            super(null, new WebConfig(), null);
        }

        @Override
        public FetchResult fetchUrl(String url, boolean summarize) {
            lastUrl = url;
            lastSummarize = summarize;
            return new FetchResult(url, "Example", "Markdown Content", 16, false, null);
        }
    }
}
