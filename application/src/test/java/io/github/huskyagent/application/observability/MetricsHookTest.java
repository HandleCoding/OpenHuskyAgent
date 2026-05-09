package io.github.huskyagent.application.observability;

import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MetricsHookTest {

    private SimpleMeterRegistry meterRegistry;
    private MetricsHook metricsHook;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsHook = new MetricsHook(meterRegistry);
    }

    @Test
    void nameIsMetrics() {
        assertEquals("metrics", metricsHook.name());
    }

    @Test
    void supportedEventsIncludesToolCallAfter() {
        assertTrue(metricsHook.supportedEvents().contains(HookEvent.TOOL_CALL_AFTER));
    }

    @Test
    void recordsToolCall() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.TOOL_NAME, "read_file");
        data.put(HookDataKeys.TOOL_STATUS, "completed");
        data.put(HookDataKeys.TOOL_DURATION_MS, 120L);

        metricsHook.after(new HookContext(HookEvent.TOOL_CALL_AFTER, "s1", data));

        assertEquals(1, meterRegistry.counter("husky.tool.calls", "toolName", "read_file", "status", "completed").count(), 0.001);
        assertEquals(120, meterRegistry.timer("husky.tool.duration", "toolName", "read_file").totalTime(TimeUnit.MILLISECONDS), 1);
    }

    @Test
    void recordsLlmCall() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.LLM_HAS_TOOL_CALLS, true);
        data.put(HookDataKeys.LLM_DURATION_MS, 500L);

        metricsHook.after(new HookContext(HookEvent.LLM_CALL_AFTER, "s1", data));

        assertEquals(1, meterRegistry.counter("husky.llm.calls", "hasToolCalls", "true").count(), 0.001);
        assertEquals(500, meterRegistry.timer("husky.llm.duration").totalTime(TimeUnit.MILLISECONDS), 1);
    }

    @Test
    void recordsSession() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.SESSION_DURATION_MS, 3000L);
        data.put(HookDataKeys.SESSION_INPUT_TOKENS, 100);
        data.put(HookDataKeys.SESSION_OUTPUT_TOKENS, 50);

        metricsHook.after(new HookContext(HookEvent.SESSION_END, "s1", data));

        assertEquals(1, meterRegistry.counter("husky.session.count").count(), 0.001);
        assertEquals(3000, meterRegistry.timer("husky.session.duration").totalTime(TimeUnit.MILLISECONDS), 1);
    }

    @Test
    void recordsCompression() {
        metricsHook.after(new HookContext(HookEvent.CONTEXT_COMPRESS, "s1", Map.of()));

        assertEquals(1, meterRegistry.counter("husky.context.compressions").count(), 0.001);
    }

    @Test
    void recordsApproval() {
        Map<String, Object> data = Map.of(HookDataKeys.APPROVAL_DECISION, "approved");

        metricsHook.after(new HookContext(HookEvent.APPROVAL_AFTER, "s1", data));

        assertEquals(1, meterRegistry.counter("husky.approval.requests", "decision", "approved").count(), 0.001);
    }

    @Test
    void recordsSubAgent() {
        Map<String, Object> data = Map.of(HookDataKeys.SUBAGENT_STATUS, "completed");

        metricsHook.after(new HookContext(HookEvent.SUBAGENT_STOP, "s1", data));

        assertEquals(1, meterRegistry.counter("husky.subagent.calls", "status", "completed").count(), 0.001);
    }

    @Test
    void handlesMissingToolData() {
        metricsHook.after(new HookContext(HookEvent.TOOL_CALL_AFTER, "s1", Map.of()));

        assertEquals(1, meterRegistry.counter("husky.tool.calls", "toolName", "unknown", "status", "unknown").count(), 0.001);
    }
}