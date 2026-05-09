package io.github.huskyagent.infra.execute;

import java.util.List;
import java.util.Map;

/**
 * SSH execution backend — Phase 3 stub.
 */
public class SshBackend implements ExecutionBackend {

    public SshBackend(BackendConfig config) {}

    @Override
    public ExecResult execute(String command, String cwd, int timeoutSeconds) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public String startBackground(String command, String cwd) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public BackgroundStatus pollBackground(String taskId) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public BackgroundLog logBackground(String taskId, int offset, int limit) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public ExecResult waitBackground(String taskId, int timeoutSeconds) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public void killBackground(String taskId) {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public List<Map<String, Object>> listBackground() {
        throw new UnsupportedOperationException("SSH backend not yet implemented");
    }

    @Override
    public void release() {}

    @Override
    public boolean isAlive() {
        return false;
    }
}
