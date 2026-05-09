package io.github.huskyagent.infra.execute;

import java.util.List;
import java.util.Map;

/**
 * Per-session execution sandbox abstraction.
 *
 * <p>One instance per session, created by {@link ExecutionBackendFactory}.
 * LOCAL: thin ProcessBuilder wrapper. DOCKER: one container per session.</p>
 */
public interface ExecutionBackend {

    ExecResult execute(String command, String cwd, int timeoutSeconds);

    String startBackground(String command, String cwd);

    BackgroundStatus pollBackground(String taskId);

    BackgroundLog logBackground(String taskId, int offset, int limit);

    ExecResult waitBackground(String taskId, int timeoutSeconds);

    void killBackground(String taskId);

    List<Map<String, Object>> listBackground();

    /** Release sandbox resources. Idempotent. */
    void release();

    boolean isAlive();

    // ── Value types ────────────────────────────────────────────────────────

    record ExecResult(String stdout, int exitCode, boolean success) {}

    record BackgroundStatus(String taskId, String command, boolean running,
                            String outputPreview, int totalChars, Integer exitCode) {}

    record BackgroundLog(String taskId, boolean running, int offset, int returned,
                         int totalLines, boolean hasMore, String content) {}
}
