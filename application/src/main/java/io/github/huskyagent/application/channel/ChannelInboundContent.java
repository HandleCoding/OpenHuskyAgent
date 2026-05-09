package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.InboundContentPart;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ChannelInboundContent {
    String text;
    List<InboundContentPart> parts;
    List<ChannelMediaReference> mediaRefs;
    boolean ignored;
    Map<String, Object> metadata;

    public static ChannelInboundContent ignored() {
        return ChannelInboundContent.builder().ignored(true).build();
    }
}
