package io.github.huskyagent.application.channel.binding;

public record EffectiveChannelRoute(
        String agentId,
        String bindingId,
        Source source
) {
    public enum Source {
        EXPLICIT,
        BINDING,
        CHANNEL_LEGACY_DEFAULT,
        GLOBAL_DEFAULT,
        AGENT_DEFAULT
    }
}
