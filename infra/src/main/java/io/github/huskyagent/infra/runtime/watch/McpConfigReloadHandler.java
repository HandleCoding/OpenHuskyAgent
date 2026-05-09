package io.github.huskyagent.infra.runtime.watch;

import io.github.huskyagent.infra.mcp.McpConfigLoader;
import io.github.huskyagent.infra.mcp.McpConnectionProvider;
import io.github.huskyagent.infra.mcp.McpServerConnector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConfigReloadHandler implements RuntimeResourceReloadHandler {

    private final McpConfigLoader configLoader;
    private final McpConnectionProvider connectionProvider;
    private final McpServerConnector connector;

    @Override
    public RuntimeResourceDescriptor descriptor() {
        return new RuntimeResourceDescriptor(
                RuntimeResourceType.MCP_CONFIG,
                configLoader.getConfigPath() == null ? Set.of() : Set.of(configLoader.getConfigPath()),
                false
        );
    }

    @Override
    public RuntimeReloadOutcome reload(Set<Path> changedPaths) {
        try {
            McpServerConnector.ReconcileResult result = connector.reconcileWithConfig();
            if (!result.success()) {
                return RuntimeReloadOutcome.failure(RuntimeResourceType.MCP_CONFIG, result.summary());
            }
            connectionProvider.onReconcileComplete();
            return RuntimeReloadOutcome.success(
                    RuntimeResourceType.MCP_CONFIG,
                    result.summary(),
                    true,
                    true
            );
        } catch (Exception e) {
            return RuntimeReloadOutcome.failure(RuntimeResourceType.MCP_CONFIG, e.getMessage());
        }
    }
}
