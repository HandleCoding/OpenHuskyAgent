package io.github.huskyagent.application.scene;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSceneResolverTest {

    @Test
    void unknownSceneFailsClosed() {
        ConfigSceneResolver resolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> configs = new LinkedHashMap<>();
        configs.put("assistant", new ConfigSceneResolver.SceneProperties());
        resolver.setConfigs(configs);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("missing"));

        assertEquals("Unknown scene: missing", error.getMessage());
    }

    @Test
    void blankSceneUsesDefaultScene() {
        ConfigSceneResolver resolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> configs = new LinkedHashMap<>();
        configs.put("assistant", new ConfigSceneResolver.SceneProperties());
        resolver.setConfigs(configs);

        assertEquals("assistant", resolver.resolve(" ").getSceneId());
    }
}
