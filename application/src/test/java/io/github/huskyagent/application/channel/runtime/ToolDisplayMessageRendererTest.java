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

        assertEquals("Calling tool: web_search\nArguments: query=test", text);
    }

    @Test
    void rendersCompletedToolWithDuration() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "web_search", null, null,
                ToolDisplayStatus.COMPLETED, 1016, null, Instant.now());

        assertEquals("Tool completed: web_search (1.0s)", renderer.render(event));
    }

    @Test
    void rendersFailedToolWithRedactedError() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "web_search", null, null,
                ToolDisplayStatus.FAILED, 12, "api_key=abc123 timed out", Instant.now());

        String text = renderer.render(event);

        assertEquals("Tool failed: web_search (12ms)\nReason: api_key=*** timed out", text);
    }

    @Test
    void truncatesLongPreview() {
        String longPreview = "a".repeat(200);
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "terminal", longPreview, null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now());

        String text = renderer.render(event);

        assertTrue(text.endsWith("..."));
        assertTrue(text.length() <= "Calling tool: terminal\nArguments: ".length() + 160);
    }

    @Test
    void returnsNullForMissingToolName() {
        ToolDisplayEvent event = new ToolDisplayEvent(
                "s1", "call-1", "", null, null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now());

        assertNull(renderer.render(event));
    }
}
