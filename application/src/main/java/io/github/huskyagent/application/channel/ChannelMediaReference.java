package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundContentPart;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ChannelMediaReference {
    ChannelType channelType;
    String messageId;
    String resourceKey;
    InboundContentPart.Kind kind;
    String declaredMimeType;
    String filename;
    Map<String, Object> metadata;
}
