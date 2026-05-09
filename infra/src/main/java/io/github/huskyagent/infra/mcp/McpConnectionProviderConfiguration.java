package io.github.huskyagent.infra.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConnectionProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpConnectionProvider.class)
    public McpConnectionProvider localMcpConnectionProvider(McpConfigLoader configLoader) {
        return new LocalMcpConnectionProvider(configLoader);
    }
}
