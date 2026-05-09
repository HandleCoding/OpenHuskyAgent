package io.github.huskyagent.infra.runtime.watch;

import io.github.huskyagent.infra.mcp.McpConfigLoader;
import io.github.huskyagent.infra.mcp.McpConnectionProvider;
import io.github.huskyagent.infra.mcp.McpServerConnector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpConfigReloadHandlerTest {

    @Test
    void testReloadSucceedsAndRequestsInvalidation() {
        McpConfigLoader configLoader = mock(McpConfigLoader.class);
        McpConnectionProvider connectionProvider = mock(McpConnectionProvider.class);
        McpServerConnector connector = mock(McpServerConnector.class);
        when(configLoader.getConfigPath()).thenReturn(Path.of("/tmp/mcp-servers.json"));
        when(connector.reconcileWithConfig()).thenReturn(McpServerConnector.ReconcileResult.success(2, 1));

        McpConfigReloadHandler handler = new McpConfigReloadHandler(configLoader, connectionProvider, connector);
        RuntimeReloadOutcome outcome = handler.reload(Set.of(Path.of("/tmp/mcp-servers.json")));

        assertTrue(outcome.success());
        assertTrue(outcome.clearPromptCache());
        assertTrue(outcome.clearGraphCache());
        assertEquals(RuntimeResourceType.MCP_CONFIG, outcome.type());
    }

    @Test
    void testReloadFailureSkipsInvalidation() {
        McpConfigLoader configLoader = mock(McpConfigLoader.class);
        McpConnectionProvider connectionProvider = mock(McpConnectionProvider.class);
        McpServerConnector connector = mock(McpServerConnector.class);
        when(configLoader.getConfigPath()).thenReturn(Path.of("/tmp/mcp-servers.json"));
        when(connector.reconcileWithConfig()).thenReturn(McpServerConnector.ReconcileResult.failure("parse failed"));

        McpConfigReloadHandler handler = new McpConfigReloadHandler(configLoader, connectionProvider, connector);
        RuntimeReloadOutcome outcome = handler.reload(Set.of(Path.of("/tmp/mcp-servers.json")));

        assertFalse(outcome.success());
        assertFalse(outcome.clearPromptCache());
        assertFalse(outcome.clearGraphCache());
        assertEquals("parse failed", outcome.summary());
    }
}
