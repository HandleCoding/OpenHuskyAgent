package io.github.huskyagent.infra.mcp;

/**
 * 本地 MCP 连接配置 — 委托 McpConfigLoader 从本地文件读取，行为与改动前完全一致。
 */
class LocalMcpConnectionProvider implements McpConnectionProvider {

    private final McpConfigLoader configLoader;

    LocalMcpConnectionProvider(McpConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public ServerLoadResult loadEnabledServers() {
        return toServerLoadResult(configLoader.loadEnabledServersResult());
    }

    @Override
    public ServerLoadResult loadAllServers() {
        return toServerLoadResult(configLoader.loadAllServersResult());
    }

    private ServerLoadResult toServerLoadResult(McpConfigLoader.ConfigLoadResult result) {
        if (!result.success()) {
            return ServerLoadResult.failure(result.errorMessage());
        }
        return ServerLoadResult.success(result.servers());
    }
}
