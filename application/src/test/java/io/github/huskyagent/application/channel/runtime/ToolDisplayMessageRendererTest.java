package io.github.huskyagent.application.channel.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ToolDisplayMessageRendererTest {

    private final ToolDisplayMessageRenderer renderer = new ToolDisplayMessageRenderer();

    @Test
    void rendersStartedToolWithArgsPreview() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "web_search", "query=test", null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now());

        String text = renderer.render(event);

        assertEquals("正在调用工具：web_search\n参数：query=test", text);
    }

    @Test
    void rendersCompletedToolWithDuration() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "web_search", null, null,
                ToolDisplayStatus.COMPLETED, 1016, null, Instant.now());

        assertEquals("工具完成：web_search（1.0s）", renderer.render(event));
    }

    @Test
    void rendersFailedToolWithRedactedError() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "web_search", null, null,
                ToolDisplayStatus.FAILED, 12, "api_key=abc123 timed out", Instant.now());

        String text = renderer.render(event);

        assertEquals("工具失败：web_search（12ms）\n原因：api_key=*** timed out", text);
    }

    @Test
    void truncatesLongPreview() {
        String longPreview = "a".repeat(200);
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "terminal", longPreview, null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now());

        String text = renderer.render(event);

        assertTrue(text.endsWith("..."));
        assertTrue(text.length() < 190);
    }

    @Test
    void returnsNullForMissingToolName() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "", null, null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now());

        assertNull(renderer.render(event));
    }
}
