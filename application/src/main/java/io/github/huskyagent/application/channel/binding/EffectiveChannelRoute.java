package io.github.huskyagent.application.channel.binding;

public record EffectiveChannelRoute(
        String sceneId,
        String bindingId,
        Source source
) {
    public enum Source {
        EXPLICIT,
        BINDING,
        CHANNEL_LEGACY_DEFAULT,
        GLOBAL_DEFAULT,
        SCENE_DEFAULT
    }
}
