package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Principal {

    String id;

    String displayName;

    ChannelType channelType;
}