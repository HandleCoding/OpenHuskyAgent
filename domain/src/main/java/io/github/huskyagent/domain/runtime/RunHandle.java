package io.github.huskyagent.domain.runtime;

public record RunHandle(String sessionId, String runId, long generation) {
    public static final String METADATA_KEY = "runHandle";

    public boolean isPresent() {
        return sessionId != null && !sessionId.isBlank()
                && runId != null && !runId.isBlank();
    }
}
