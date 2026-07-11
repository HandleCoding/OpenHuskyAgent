package io.github.huskyagent.infra.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConnectorTest {

    @Test
    void connectAllSkipsConnectionsWhenServerConfigLoadFails() {
        McpConnectionProvider provider = new McpConnectionProvider() {
            @Override
            public ServerLoadResult loadEnabledServers() {
                return ServerLoadResult.failure("parse failed");
            }

            @Override
            public ServerLoadResult loadAllServers() {
                return ServerLoadResult.success(Map.of());
            }
        };
        McpServerConnector connector = new McpServerConnector(provider);

        connector.connectAll();

        assertTrue(connector.getConnectedServerNames().isEmpty());
        assertTrue(connector.getAllStatuses().isEmpty());
        assertEquals(McpServerConnector.ServerStatus.NOT_CONFIGURED, connector.getStatus("missing"));
        connector.shutdownAll();
    }

    @Test
    void stdioToolLookupUsesExactDiscoveredToolIndex() {
        McpServerConnector connector = new McpServerConnector(new EmptyMcpConnectionProvider());
        McpServerConfig stdioConfig = new McpServerConfig(
                "node", List.of("server.js"), Map.of(), null, null, Map.of(), true, 30);
        McpServerConfig httpConfig = new McpServerConfig(
                null, List.of(), Map.of(), "https://example.com/mcp", "streamable-http", Map.of(), true, 30);

        connector.indexToolNames("docs", stdioConfig, List.of("lookup"));
        connector.indexToolNames("docs-api", httpConfig, List.of("read"));

        String docsApiTool = McpToolNames.prefixName("docs-api", "read");
        assertEquals("docs-api", connector.serverNameForTool(docsApiTool).orElseThrow());
        assertFalse(connector.isStdioTool(docsApiTool));
        assertTrue(connector.isStdioTool(McpToolNames.prefixName("docs", "lookup")));
    }

    private static class EmptyMcpConnectionProvider implements McpConnectionProvider {
        @Override
        public ServerLoadResult loadEnabledServers() {
            return ServerLoadResult.success(Map.of());
        }

        @Override
        public ServerLoadResult loadAllServers() {
            return ServerLoadResult.success(Map.of());
        }
    }
}
