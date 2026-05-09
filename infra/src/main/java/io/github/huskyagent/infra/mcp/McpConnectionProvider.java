package io.github.huskyagent.infra.mcp;

import java.util.Map;

/**
 * MCP 服务器连接配置抽象。
 *
 * <p>决定哪些 MCP servers 可用及其连接配置。
 * 默认 impl 从本地 mcp-servers.json 读取；远端 impl 可从 API 或配置中心获取。</p>
 */
public interface McpConnectionProvider {

    ServerLoadResult loadEnabledServers();

    ServerLoadResult loadAllServers();

    /**
     * 获取指定 scene 应连接的 MCP servers。
     * 默认返回所有 enabled servers（与改动前行为一致）。
     * 远端 impl 可按 scene 返回不同子集。
     */
    default Map<String, McpServerConfig> serversForScene(String sceneId) {
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
