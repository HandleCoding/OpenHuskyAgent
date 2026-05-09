package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class OutboundMessage {
    Kind kind;
    String sessionId;
    ChannelIdentity channelIdentity;
    ReplyTarget replyTarget;
    String text;
    Map<String, Object> metadata;

    public enum Kind {
        TEXT,
        TOKEN,
        REASONING,
        TOOL_STATUS,
        APPROVAL,
        ERROR,
        DONE
    }
}
