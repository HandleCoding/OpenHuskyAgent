package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ClarifyDecision {
    String answer;

    public static ClarifyDecision answer(String answer) {
        return ClarifyDecision.builder()
                .answer(answer != null ? answer : "")
                .build();
    }
}
