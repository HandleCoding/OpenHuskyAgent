package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ClarifyPrompt {
    String requestId;
    String sessionId;
    String question;
    List<String> options;
    String agentText;
    ChannelIdentity channelIdentity;
    ReplyTarget replyTarget;
    Map<String, Object> metadata;
}
