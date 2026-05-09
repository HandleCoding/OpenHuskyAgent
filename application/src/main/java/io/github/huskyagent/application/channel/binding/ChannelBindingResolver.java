package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelIdentity;

import java.util.Optional;

public interface ChannelBindingResolver {
    Optional<ChannelInstanceBinding> resolve(ChannelIdentity identity);

    Optional<ChannelInstanceBinding> resolveConfigured(ChannelIdentity identity);

    Optional<String> defaultScene();

    boolean allowsExplicitSceneOverride(ChannelIdentity identity);
}
