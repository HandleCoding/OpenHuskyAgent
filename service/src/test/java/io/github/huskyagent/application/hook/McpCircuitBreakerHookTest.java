package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.*;
import io.github.huskyagent.infra.mcp.McpServerConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpCircuitBreakerHookTest {

    private McpServerConnector connector;
    private McpCircuitBreakerHook hook;

    @BeforeEach
    void setUp() {
        connector = mock(McpServerConnector.class);
        hook = new McpCircuitBreakerHook(connector);
    }

    @Test
    void nameAndEvents() {
        assertEquals("mcp-circuit-breaker", hook.name());
        assertEquals(Set.of(HookEvent.TOOL_CALL_BEFORE), hook.supportedEvents());
        assertEquals(10, hook.order());
    }

    @Test
    void nonMcpTool_allows() {
        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "read_file"));

        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
        verifyNoInteractions(connector);
    }

    @Test
    void mcpTool_circuitOpen_blocks() {
        when(connector.isCircuitOpen("brave")).thenReturn(true);

        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "mcp_brave_web_search"));

        HookResult result = hook.before(ctx);
        assertFalse(result.allowed());
        assertTrue(result.blockReason().contains("circuit breaker open"));
    }

    @Test
    void mcpTool_circuitClosed_allows() {
        when(connector.isCircuitOpen("brave")).thenReturn(false);

        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "mcp_brave_web_search"));

        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
    }

    @Test
    void nullToolName_allows() {
        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
    }

    @Test
    void malformedMcpName_allows() {
        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "mcp_x"));
        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
    }
}
