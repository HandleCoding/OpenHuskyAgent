package io.github.huskyagent.application.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
