package io.github.huskyagent.application.subagent;

import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegateTaskToolTest {

    @Test
    void exposesModelControlledTimeoutParameter() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null);

        ToolDefinition definition = tool.getTools().get(0);

        assertTrue(definition.parametersSchema().get("properties").has("timeout_seconds"));
        assertTrue(definition.description().contains("timeout_seconds"));
    }

    @Test
    void resolvesToolTimeoutFromTimeoutSecondsArgument() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null);
        ToolDefinition definition = tool.getTools().get(0);

        Duration timeout = definition.resolveTimeout(Map.of("timeout_seconds", 900), Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(900), timeout);
    }

    @Test
    void resolvesDefaultToolTimeoutFromSubAgentConfig() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null);
        ToolDefinition definition = tool.getTools().get(0);

        Duration timeout = definition.resolveTimeout(Map.of(), Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(600), timeout);
    }
}
