package io.github.huskyagent.application.agent;

import io.github.huskyagent.infra.llm.ModelSelection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigAgentResolverTest {

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
}
