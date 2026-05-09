package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class InboundMessage {
    String messageId;
    String text;
    Principal principal;
    ChannelIdentity channelIdentity;
    String sceneId;
    String requestedSessionId;
    List<InboundContentPart> contentParts;
    ReplyTarget replyTarget;
    Object rawPayload;
    Map<String, Object> metadata;
    boolean ignored;

    public boolean hasContent() {
        if (text != null && !text.isBlank()) {
            return true;
        }
        return contentParts != null && contentParts.stream().anyMatch(part -> part != null
                && (part.getAttachment() != null || (part.getText() != null && !part.getText().isBlank())));
    }

    public static InboundMessage ignored(Object rawPayload) {
        return InboundMessage.builder()
                .rawPayload(rawPayload)
                .ignored(true)
                .build();
    }
}
