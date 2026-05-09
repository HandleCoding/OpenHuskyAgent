package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ChannelCapabilities {
    boolean supportsStreaming;
    boolean supportsEdit;
    boolean supportsTyping;
    boolean supportsApprovalCard;
    boolean supportsImageInput;
    boolean supportsFileInput;
    boolean requiresMentionInGroup;

    public static ChannelCapabilities basic() {
        return ChannelCapabilities.builder().build();
    }
}
