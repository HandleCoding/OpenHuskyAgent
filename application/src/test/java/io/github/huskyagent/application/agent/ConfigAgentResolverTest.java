package io.github.huskyagent.application.agent;

import io.github.huskyagent.infra.llm.ModelSelection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAgentResolverTest {

    @Test
    void unknownToolsetFailsClosedOnResolve() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setToolsets(List.of("CORE", "NOT_A_REAL_TOOLSET"));
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("assistant"));
        assertTrue(error.getMessage().contains("Unknown toolset"));
        assertTrue(error.getMessage().contains("NOT_A_REAL_TOOLSET"));
    }

    @Test
    void unknownAgentFailsClosed() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigAgentResolver.AgentProperties());
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("missing"));

        assertEquals("Unknown agent: missing", error.getMessage());
    }

    @Test
    void blankAgentFailsClosed() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigAgentResolver.AgentProperties());
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(" "));

        assertEquals("Unknown agent: null", error.getMessage());
    }

    @Test
    void explicitAgentResolves() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigAgentResolver.AgentProperties());
        resolver.setAgents(agents);

        assertEquals("assistant", resolver.resolve("assistant").getAgentId());
    }

    @Test
    void dockerPersistFilesystemOnlyCreatesBackendSpec() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setDockerPersistFilesystem(true);
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        var scene = resolver.resolve("assistant");

        assertEquals(Boolean.TRUE, scene.getBackendSpec().getDockerPersistFilesystem());
    }

    @Test
    void dockerSpecPreservesMissingPersistOverrideAsNull() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setDockerImage("node:22");
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        var scene = resolver.resolve("assistant");

        assertEquals("node:22", scene.getBackendSpec().getDockerImage());
        assertNull(scene.getBackendSpec().getDockerPersistFilesystem());
    }

    @Test
    void modelStringBindsToModelSelectionName() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setModel("gpt-5.4");
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        ModelSelection model = resolver.resolve("assistant").getModelSelection();
        assertNotNull(model);
        assertEquals("gpt-5.4", model.getModelName());
        assertNull(model.getProviderId());
    }

    @Test
    void modelObjectBindsProviderNameAndSampling() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setModel(Map.of(
                "provider", "deepseek",
                "name", "deepseek-chat",
                "temperature", 0.2,
                "max-tokens", 4096));
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("chatbot", props);
        resolver.setAgents(agents);

        ModelSelection model = resolver.resolve("chatbot").getModelSelection();
        assertNotNull(model);
        assertEquals("deepseek", model.getProviderId());
        assertEquals("deepseek-chat", model.getModelName());
        assertEquals(0.2, model.getTemperature());
        assertEquals(4096, model.getMaxTokens());
    }

    @Test
    void toModelSelectionAcceptsModelKeyAlias() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ModelSelection model = resolver.toModelSelection(Map.of("provider", "main", "model", "qwen"));
        assertEquals("main", model.getProviderId());
        assertEquals("qwen", model.getModelName());
    }

    @Test
    void rateLimitEnabledRequiresPositiveRpm() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setRateLimitEnabled(true);
        // missing rpm
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("chatbot", props);
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("chatbot"));
        assertTrue(error.getMessage().contains("rate-limit-requests-per-minute"));
    }

    @Test
    void delegationPropertiesBindOntoAgentDefinition() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        ConfigAgentResolver.DelegationProperties delegation = new ConfigAgentResolver.DelegationProperties();
        delegation.setEnabled(true);
        delegation.setMaxIterations(12);
        delegation.setMaxConcurrentChildren(2);
        delegation.setBlockedToolsets(List.of("BROWSER"));
        delegation.setDefaultToolsets(List.of("CORE", "SEARCH"));
        delegation.setModel("cheap-model");
        props.setDelegation(delegation);
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        var agent = resolver.resolve("assistant");
        assertNotNull(agent.getDelegationSpec());
        assertEquals(Boolean.TRUE, agent.getDelegationSpec().getEnabled());
        assertEquals(12, agent.getDelegationSpec().getMaxIterations());
        assertEquals(2, agent.getDelegationSpec().getMaxConcurrentChildren());
        assertEquals(List.of("BROWSER"), agent.getDelegationSpec().getBlockedToolsets());
        assertEquals(List.of("CORE", "SEARCH"), agent.getDelegationSpec().getDefaultToolsets());
        assertEquals("cheap-model", agent.getDelegationSpec().getModel());
    }

    @Test
    void rateLimitEnabledWithRpmResolves() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setRateLimitEnabled(true);
        props.setRateLimitRequestsPerMinute(30);
        props.setRateLimitBurst(10);
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("chatbot", props);
        resolver.setAgents(agents);

        var agent = resolver.resolve("chatbot");
        assertTrue(agent.getRateLimitSpec().isEnabled());
        assertEquals(30, agent.getRateLimitSpec().getRequestsPerMinute());
        assertEquals(10, agent.getRateLimitSpec().getBurst());
    }

    @Test
    void emptyToolsetsMeansNoneNotAll() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setToolsets(List.of());
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        assertTrue(resolver.resolve("assistant").getAllowedToolsets().isEmpty());
    }

    @Test
    void starToolsetsMeansAll() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setToolsets(List.of("*"));
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        assertEquals(Set.of(io.github.huskyagent.infra.tool.Toolset.values()),
                resolver.resolve("assistant").getAllowedToolsets());
    }

    @Test
    void starKnowledgeSourceIsNormalized() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setKnowledgeSources(Set.of("all"));
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        assertEquals(Set.of("*"), resolver.resolve("assistant").getKnowledgeSources());
    }
}
