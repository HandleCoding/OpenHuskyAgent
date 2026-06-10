package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelType;

public record ChannelInstanceReference(
        ChannelType channelType,
        String instanceId,
        boolean enabled,
        String platformAccountId
) {
}
