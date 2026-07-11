package io.github.huskyagent.application.subagent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegateTaskToolTest {

    @Test
    void exposesModelControlledTimeoutParameter() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null, null);

        ToolDefinition definition = tool.getTools().get(0);

        assertTrue(definition.parametersSchema().get("properties").has("timeout_seconds"));
        assertTrue(definition.description().contains("timeout_seconds"));
    }

    @Test
    void resolvesToolTimeoutFromTimeoutSecondsArgumentCappedByGlobalCeiling() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null, null);
        ToolDefinition definition = tool.getTools().get(0);

        Duration timeout = definition.resolveTimeout(Map.of("timeout_seconds", 900), Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(600), timeout);
    }

    @Test
    void resolvesToolTimeoutBelowGlobalCeilingUnchanged() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null, null);
        ToolDefinition definition = tool.getTools().get(0);

        Duration timeout = definition.resolveTimeout(Map.of("timeout_seconds", 120), Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(120), timeout);
    }

    @Test
    void resolvesDefaultToolTimeoutFromSubAgentConfig() {
        SubAgentConfig config = new SubAgentConfig();
        config.setChildTimeoutSeconds(600);
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, config, null, null, null, null);
        ToolDefinition definition = tool.getTools().get(0);

        Duration timeout = definition.resolveTimeout(Map.of(), Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(600), timeout);
    }

    @Test
    void mergesAgentDelegationOverridesIntoEffectivePolicy() {
        SubAgentConfig global = new SubAgentConfig();
        global.setMaxIterations(50);
        global.setBlockedToolsets(List.of("DELEGATE", "MEMORY"));

        AgentDefinition definition = new AgentDefinition();
        AgentDefinition.DelegationSpec delegation = new AgentDefinition.DelegationSpec();
        delegation.setMaxIterations(15);
        delegation.setBlockedToolsets(List.of("BROWSER"));
        definition.setDelegationSpec(delegation);

        AgentResolver resolver = new AgentResolver() {
            @Override
            public AgentDefinition resolve(String agentId) {
                assertEquals("assistant", agentId);
                return definition;
            }

            @Override
            public AgentDefinition resolveDefault() {
                return definition;
            }
        };
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, global, resolver, null, null, null, null);
        ToolExecutionContext ctx = parentContext("assistant");

        EffectiveDelegationPolicy policy = tool.resolveEffectivePolicy(ctx);

        assertEquals(15, policy.maxIterations());
        assertEquals(Set.of("DELEGATE", "MEMORY", "BROWSER"), policy.blockedToolsets());
    }

    @Test
    void agentDisabledDelegationSurfacesInEffectivePolicy() {
        SubAgentConfig global = new SubAgentConfig();
        global.setEnabled(true);
        AgentDefinition definition = new AgentDefinition();
        AgentDefinition.DelegationSpec delegation = new AgentDefinition.DelegationSpec();
        delegation.setEnabled(false);
        definition.setDelegationSpec(delegation);

        AgentResolver resolver = new AgentResolver() {
            @Override
            public AgentDefinition resolve(String agentId) {
                return definition;
            }

            @Override
            public AgentDefinition resolveDefault() {
                return definition;
            }
        };
        DelegateTaskTool tool = new DelegateTaskTool(null, null, null, global, resolver, null, null, null, null);

        assertFalse(tool.resolveEffectivePolicy(parentContext("assistant")).enabled());
    }

    private static ToolExecutionContext parentContext(String agentId) {
        return new ToolExecutionContext(
                "parent-session",
                SessionScope.builder().sessionId("parent-session").agentId(agentId).build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
