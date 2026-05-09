package io.github.huskyagent.infra.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
