package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ReplyTarget {
    String chatId;
    String threadId;
    String messageId;
    Map<String, Object> metadata;
}
