package io.github.huskyagent.application.subagent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.tool.Toolset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveDelegationPolicyTest {

    @Test
    void mergesGlobalDefaultsWhenAgentSpecMissing() {
        SubAgentConfig global = new SubAgentConfig();
        global.setEnabled(true);
        global.setMaxIterations(50);
        global.setMaxConcurrentChildren(3);
        global.setMaxSpawnDepth(1);
        global.setChildTimeoutSeconds(600);
        global.setBlockedToolsets(List.of("DELEGATE", "MEMORY"));

        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, null);

        assertTrue(policy.enabled());
        assertEquals(50, policy.maxIterations());
        assertEquals(3, policy.maxConcurrentChildren());
        assertEquals(1, policy.maxSpawnDepth());
        assertEquals(600L, policy.childTimeoutSeconds());
        assertEquals(Set.of("DELEGATE", "MEMORY"), policy.blockedToolsets());
        assertTrue(policy.defaultToolsets().isEmpty());
        assertNull(policy.model());
    }

    @Test
    void agentCannotReEnableWhenGlobalDisabled() {
        SubAgentConfig global = new SubAgentConfig();
        global.setEnabled(false);
        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setEnabled(true);

        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, agent);

        assertFalse(policy.enabled());
    }

    @Test
    void agentCanDisableWhenGlobalEnabled() {
        SubAgentConfig global = new SubAgentConfig();
        global.setEnabled(true);
        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setEnabled(false);

        assertFalse(EffectiveDelegationPolicy.merge(global, agent).enabled());
    }

    @Test
    void numericCeilingsUseStricterMinimum() {
        SubAgentConfig global = new SubAgentConfig();
        global.setMaxIterations(50);
        global.setMaxConcurrentChildren(5);
        global.setMaxSpawnDepth(2);
        global.setChildTimeoutSeconds(600);

        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setMaxIterations(20);
        agent.setMaxConcurrentChildren(10);
        agent.setMaxSpawnDepth(1);
        agent.setChildTimeoutSeconds(900L);

        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, agent);

        assertEquals(20, policy.maxIterations());
        assertEquals(5, policy.maxConcurrentChildren());
        assertEquals(1, policy.maxSpawnDepth());
        assertEquals(600L, policy.childTimeoutSeconds());
    }

    @Test
    void blockedToolsetsAreUnioned() {
        SubAgentConfig global = new SubAgentConfig();
        global.setBlockedToolsets(List.of("DELEGATE", "MEMORY"));
        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setBlockedToolsets(List.of("BROWSER", "delegate"));

        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, agent);

        assertEquals(Set.of("DELEGATE", "MEMORY", "BROWSER"), policy.blockedToolsets());
    }

    @Test
    void agentDefaultToolsetsOverrideGlobal() {
        SubAgentConfig global = new SubAgentConfig();
        global.setDefaultToolsets(List.of("CORE", "SEARCH"));
        global.setBlockedToolsets(List.of("DELEGATE"));
        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setDefaultToolsets(List.of("WEB"));

        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, agent);

        assertEquals(List.of("WEB"), policy.defaultToolsets());
        assertEquals(Set.of(Toolset.WEB), policy.resolveAllowedToolsets(List.of()));
    }

    @Test
    void toolParamsAreCappedByEffectiveCeilings() {
        SubAgentConfig global = new SubAgentConfig();
        global.setMaxIterations(30);
        global.setChildTimeoutSeconds(120);
        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, null);

        assertEquals(30, policy.resolveMaxSteps(null));
        assertEquals(10, policy.resolveMaxSteps(10));
        assertEquals(30, policy.resolveMaxSteps(100));
        assertEquals(120L, policy.resolveTimeoutSeconds(null));
        assertEquals(60L, policy.resolveTimeoutSeconds(60L));
        assertEquals(120L, policy.resolveTimeoutSeconds(999L));
    }

    @Test
    void requestedToolsetsExcludeBlockedEvenWhenRequested() {
        SubAgentConfig global = new SubAgentConfig();
        global.setBlockedToolsets(List.of("DELEGATE", "MEMORY"));
        EffectiveDelegationPolicy policy = EffectiveDelegationPolicy.merge(global, null);

        Set<Toolset> allowed = policy.resolveAllowedToolsets(List.of("CORE", "DELEGATE", "SEARCH"));

        assertEquals(Set.of(Toolset.CORE, Toolset.SEARCH), allowed);
    }

    @Test
    void modelPrefersAgentThenGlobal() {
        SubAgentConfig global = new SubAgentConfig();
        global.setModel("global-model");
        AgentDefinition.DelegationSpec agent = new AgentDefinition.DelegationSpec();
        agent.setModel("agent-model");

        assertEquals("agent-model", EffectiveDelegationPolicy.merge(global, agent).model());
        assertEquals("global-model", EffectiveDelegationPolicy.merge(global, new AgentDefinition.DelegationSpec()).model());
        assertNotNull(EffectiveDelegationPolicy.merge(global, agent).modelOverride());
        assertEquals("agent-model", EffectiveDelegationPolicy.merge(global, agent).modelOverride().getModelName());
    }
}
