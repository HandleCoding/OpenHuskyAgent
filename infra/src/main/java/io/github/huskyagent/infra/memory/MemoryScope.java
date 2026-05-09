package io.github.huskyagent.infra.memory;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MemoryScope {

    SearchBoundary boundary;
    String currentSessionId;
    String principalId;
    String channelType;
    String sceneId;
    String memoryPolicy;

    public enum SearchBoundary {
        CURRENT_SESSION,
        SAME_PRINCIPAL,
        SAME_PRINCIPAL_AND_SCENE
    }
}
