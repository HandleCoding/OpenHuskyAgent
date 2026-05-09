package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.*;
import io.github.huskyagent.infra.mcp.McpServerConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * MCP 熔断器 Hook — 在 TOOL_CALL_BEFORE 中检查 MCP server 的熔断状态。
 *
 * <p>若目标 MCP server 熔断器开启，返回 block() 阻止工具执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpCircuitBreakerHook implements BeforeHook {

    private final McpServerConnector connector;

    @Override
    public String name() { return "mcp-circuit-breaker"; }

    @Override
    public Set<HookEvent> supportedEvents() { return Set.of(HookEvent.TOOL_CALL_BEFORE); }

    @Override
    public int order() { return 10; }

    @Override
    public HookResult before(HookContext context) {
        String toolName = context.getString(HookDataKeys.TOOL_NAME);
        if (toolName == null || !toolName.startsWith("mcp_")) return HookResult.allow();

        String serverName = extractServerName(toolName);
        if (serverName == null) return HookResult.allow();

        if (connector.isCircuitOpen(serverName)) {
            log.info("[hook] MCP server '{}' 熔断开启，阻塞工具 {}", serverName, toolName);
            return HookResult.block(
                    "MCP server '" + serverName + "' is temporarily unavailable (circuit breaker open)");
        }
        return HookResult.allow();
    }

    private String extractServerName(String prefixedToolName) {
        String[] parts = prefixedToolName.split("_");
        if (parts.length < 3) return null;
        return parts[1];
    }
}
