package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelType;

import java.util.Map;

public record ChannelInstanceBinding(
        String bindingId,
        ChannelType channelType,
        String platformAccountId,
        String sceneId,
        boolean enabled,
        String displayName,
        Map<String, String> metadata
) {
}
