package io.github.huskyagent.application.scene;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSceneResolverTest {

    @Test
    void unknownAgentFailsClosed() {
        ConfigSceneResolver resolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigSceneResolver.SceneProperties());
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("missing"));

        assertEquals("Unknown agent: missing", error.getMessage());
    }

    @Test
    void blankAgentFailsClosed() {
        ConfigSceneResolver resolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigSceneResolver.SceneProperties());
        resolver.setAgents(agents);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(" "));

        assertEquals("Unknown agent: null", error.getMessage());
    }

    @Test
    void explicitAgentResolves() {
        ConfigSceneResolver resolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigSceneResolver.SceneProperties());
        resolver.setAgents(agents);

        assertEquals("assistant", resolver.resolve("assistant").getSceneId());
    }
}
