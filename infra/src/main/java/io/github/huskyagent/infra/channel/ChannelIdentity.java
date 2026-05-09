package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ChannelIdentity {

    ChannelType channelType;

    ConversationType conversationType;

    String platformAccountId;

    String chatId;

    String threadId;

    String senderId;

    String connectionId;
}