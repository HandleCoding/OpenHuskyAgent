package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.InboundMessage;

import java.util.Optional;

public interface ChannelCommandParser {
    Optional<ChannelCommand> parse(InboundMessage inbound);
}
