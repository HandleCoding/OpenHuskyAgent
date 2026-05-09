package io.github.huskyagent.infra.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 连接配置注册。
 *
 * <p>当 mcp.enabled=true 且没有其他 McpConnectionProvider 实现时，
 * 注册基于本地文件的 LocalMcpConnectionProvider。</p>
 */
@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConnectionProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpConnectionProvider.class)
    public McpConnectionProvider localMcpConnectionProvider(McpConfigLoader configLoader) {
        return new LocalMcpConnectionProvider(configLoader);
    }
}
