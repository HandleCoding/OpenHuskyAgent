package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelType;

import java.util.Optional;

public interface ChannelInstanceReferenceResolver {
    Optional<ChannelInstanceReference> resolve(ChannelType channelType, String instanceId);
}
