package io.github.huskyagent.domain.scene;

public interface SceneResolver {
    SceneConfig resolve(String sceneId);

    SceneConfig resolveDefault();
}
