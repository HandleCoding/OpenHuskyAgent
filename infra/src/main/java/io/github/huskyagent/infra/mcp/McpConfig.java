package io.github.huskyagent.infra.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConfig {
}
