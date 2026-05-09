package io.github.huskyagent.infra.tool;

import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    @Test
    void testSuccessResult() {
        ToolResult result = ToolResult.success("test content");

        assertTrue(result.success());
        assertEquals("test content", result.content());
        assertNull(result.error());
    }

    @Test
    void testSuccessWithObject() {
        Map<String, Object> data = Map.of("key", "value", "number", 42);
        ToolResult result = ToolResult.success(data);

        assertTrue(result.success());
        assertNotNull(result.content());
        assertTrue(result.content().contains("key"));
        assertTrue(result.content().contains("value"));
    }

    @Test
    void testFailureResult() {
        ToolResult result = ToolResult.failure("something went wrong");

        assertFalse(result.success());
        assertNull(result.content());
        assertEquals("something went wrong", result.error());
    }

    @Test
    void testToJson() {
        ToolResult success = ToolResult.success("content");
        String json = success.toJson();

        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"content\":\"content\""));

        ToolResult failure = ToolResult.failure("error msg");
        json = failure.toJson();

        assertTrue(json.contains("\"success\":false"));
        assertTrue(json.contains("\"error\":\"error msg\""));
    }

    @Test
    void testTruncate() {
        String longContent = "a".repeat(1000);
        ToolResult result = ToolResult.success(longContent);

        ToolResult truncated = result.truncate(100);

        assertTrue(truncated.content().length() < 200);  // 100 + suffix
        assertTrue(truncated.content().contains("truncated"));
        assertTrue(truncated.content().contains("1000"));
    }

    @Test
    void testTruncateShortContent() {
        ToolResult result = ToolResult.success("short");

        ToolResult truncated = result.truncate(100);

        assertEquals("short", truncated.content());
    }
}