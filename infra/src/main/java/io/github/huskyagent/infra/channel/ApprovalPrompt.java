package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ApprovalPrompt {
    String requestId;
    String sessionId;
    String toolName;
    String toolArgs;
    String reason;
    String agentText;
    ChannelIdentity channelIdentity;
    ReplyTarget replyTarget;
    Map<String, Object> metadata;
}
