package io.github.huskyagent.infra.mcp;

import java.util.Map;

public interface McpConnectionProvider {

    ServerLoadResult loadEnabledServers();

    ServerLoadResult loadAllServers();

    default Map<String, McpServerConfig> serversForAgent(String agentId) {
        ServerLoadResult result = loadEnabledServers();
        if (!result.success()) {
            throw new IllegalStateException("Failed to load MCP server config: " + result.errorMessage());
        }
        return result.servers();
    }

    default void onReconcileComplete() {}

    record ServerLoadResult(boolean success, Map<String, McpServerConfig> servers, String errorMessage) {
        public static ServerLoadResult success(Map<String, McpServerConfig> servers) {
            return new ServerLoadResult(true, Map.copyOf(servers), null);
        }

        public static ServerLoadResult failure(String errorMessage) {
            return new ServerLoadResult(false, Map.of(), errorMessage);
        }
    }
}
