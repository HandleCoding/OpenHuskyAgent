package io.github.huskyagent.infra.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 配置类 — 仅在 mcp.enabled=true 时激活
 *
 * <p>实际的 bean 条件已通过 @ConditionalOnProperty 注解在各组件类上声明。
 * 此配置类作为 MCP 模块的统一入口标记。</p>
 */
@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConfig {
}
