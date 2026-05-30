package io.github.huskyagent.application.runtime;

public record RunHandle(String sessionId, String runId, long generation) {
    public static final String METADATA_KEY = "runHandle";

    public io.github.huskyagent.domain.runtime.RunHandle toDomain() {
        return new io.github.huskyagent.domain.runtime.RunHandle(sessionId, runId, generation);
    }
}
