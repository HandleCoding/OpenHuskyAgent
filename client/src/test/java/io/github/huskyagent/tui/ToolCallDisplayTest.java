package io.github.huskyagent.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallDisplayTest {

    @BeforeEach
    void resetState() throws Exception {
        Field lastStarted = ToolCallDisplay.class.getDeclaredField("lastStartedContent");
        lastStarted.setAccessible(true);
        lastStarted.set(null, null);

        Field startedMap = ToolCallDisplay.class.getDeclaredField("startedLinesByCallId");
        startedMap.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) startedMap.get(null);
        map.clear();

        Field subAgentPanels = ToolCallDisplay.class.getDeclaredField("subAgentPanels");
        subAgentPanels.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, ?> panels = (Map<Integer, ?>) subAgentPanels.get(null);
        panels.clear();

        Field subAgentTotalRenderedLines = ToolCallDisplay.class.getDeclaredField("subAgentTotalRenderedLines");
        subAgentTotalRenderedLines.setAccessible(true);
        subAgentTotalRenderedLines.setInt(null, 0);

        Field pendingMainAgentLines = ToolCallDisplay.class.getDeclaredField("pendingMainAgentLines");
        pendingMainAgentLines.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> pending = (List<String>) pendingMainAgentLines.get(null);
        pending.clear();
    }

    @Test
    @DisplayName("concurrent started events are tracked by toolCallId")
    void startedLinesTrackedByToolCallId() throws Exception {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);

        ToolCallDisplay.printStatus("STARTED", "read_file", "a.txt", 0, null, "call-a", out, out::flush);
        ToolCallDisplay.printStatus("STARTED", "read_file", "b.txt", 0, null, "call-b", out, out::flush);

        Field startedMap = ToolCallDisplay.class.getDeclaredField("startedLinesByCallId");
        startedMap.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) startedMap.get(null);
        assertEquals(2, map.size());

        ToolCallDisplay.printStatus("COMPLETED", "read_file", "a.txt", 10, null, "call-a", out, out::flush);
        assertEquals(1, map.size());
        ToolCallDisplay.printStatus("COMPLETED", "read_file", "b.txt", 10, null, "call-b", out, out::flush);
        assertEquals(0, map.size());
    }

    @Test
    @DisplayName("concurrent completed events do not overwrite previous lines with ANSI")
    void concurrentCompletionDoesNotOverwritePreviousLine() {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);

        ToolCallDisplay.printStatus("STARTED", "read_file", "a.txt", 0, null, "call-a", out, out::flush);
        ToolCallDisplay.printStatus("COMPLETED", "read_file", "a.txt", 10, null, "call-a", out, out::flush);

        String rendered = output.toString();
        assertFalse(rendered.contains("\033[A\r"), "concurrent completed events should not overwrite the previous line");
        assertTrue(rendered.contains("⏳"), "started line should be retained");
        assertTrue(rendered.contains("✓"), "completed line should be appended");
    }

    @Test
    @DisplayName("sub-agent completion summary is collapsed to one line")
    void subAgentSummaryCollapsedToSingleLine() {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);

        ToolCallDisplay.printSubAgentStart(0, "query Beijing weather", out, out::flush);
        ToolCallDisplay.printSubAgentEnd(0, "completed", 32400,
                "## Task completion summary\n### Executed actions\n1. web_search", out, out::flush);

        String rendered = output.toString();
        assertTrue(rendered.contains("## Task completion summary ### Executed actions 1."));
        assertFalse(rendered.contains("\n### Executed actions"));
    }

    @Test
    @DisplayName("sub-agent panel clears stale lines when shrinking")
    void subAgentRerenderClearsShrunkenPanelTail() {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);

        ToolCallDisplay.printSubAgentStart(0, "query Beijing weather", out, out::flush);
        ToolCallDisplay.printSubAgentToolEvent(0, "STARTED", "web_search", "{}", 0, null, out, out::flush);
        ToolCallDisplay.printSubAgentToolEvent(0, "COMPLETED", "web_search", "{}", 1100, null, out, out::flush);
        ToolCallDisplay.printSubAgentEnd(0, "completed", 32400, "done", out, out::flush);

        String rendered = output.toString();
        assertTrue(rendered.contains("\033[3A"), "shrink redraw should move back to panel top");
        assertTrue(rendered.contains("\r\033[K\n"), "shrink redraw should clear stale tail lines");
    }

    @Test
    @DisplayName("argument preview shows multiple short fields")
    void argsPreviewShowsMultipleFields() {
        String preview = ToolCallDisplay.argsPreview("web_fetch",
                "{\"url\":\"https://example.com\",\"useJina\":true,\"summarize\":false}");

        assertTrue(preview.contains("url=https://example.com"));
        assertTrue(preview.contains("useJina=true"));
        assertTrue(preview.contains("summarize=false"));
    }

    @Test
    @DisplayName("large text field shows truncated value")
    void argsPreviewTruncatesLargeTextFields() {
        String preview = ToolCallDisplay.argsPreview("write_file",
                "{\"path\":\"a.txt\",\"content\":\"" + "x".repeat(120) + "\"}");

        assertTrue(preview.contains("path=a.txt"));
        assertTrue(preview.contains("content=" + "x".repeat(77) + "..."));
    }
}
