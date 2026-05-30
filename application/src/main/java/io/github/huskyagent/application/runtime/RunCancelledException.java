package io.github.huskyagent.application.runtime;

public class RunCancelledException extends RuntimeException {
    private final String sessionId;

    public RunCancelledException(String sessionId) {
        super("Run cancelled");
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
