package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ApprovalDecision {
    boolean approved;
    boolean always;
    String reason;

    public static ApprovalDecision deny(String reason) {
        return ApprovalDecision.builder()
                .approved(false)
                .always(false)
                .reason(reason)
                .build();
    }
}
