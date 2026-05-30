package io.github.huskyagent.application.runtime;

public record StopResult(boolean hadActiveRun,
                         String sessionId,
                         String runId,
                         long generation,
                         String reason) {
    public static StopResult none(String sessionId, String reason) {
        return new StopResult(false, sessionId, null, 0L, reason);
    }

    public static StopResult stopped(RunHandle handle, String reason) {
        return new StopResult(true, handle.sessionId(), handle.runId(), handle.generation(), reason);
    }
}
