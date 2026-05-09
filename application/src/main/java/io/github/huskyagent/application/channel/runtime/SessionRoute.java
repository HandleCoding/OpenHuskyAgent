package io.github.huskyagent.application.channel.runtime;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ReplyTarget;

public record SessionRoute(
        String sessionId,
        ChannelType channelType,
        ChannelIdentity channelIdentity,
        ReplyTarget replyTarget,
        String correlationId
) {
}