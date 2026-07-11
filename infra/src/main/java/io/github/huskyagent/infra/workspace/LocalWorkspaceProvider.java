package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.session.SessionScope;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@RequiredArgsConstructor
public class LocalWorkspaceProvider implements WorkspaceProvider {

    private final LocalWorkspace localWorkspace;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public Workspace workspace(SessionScope scope) {
        if (scope != null && scope.getWorkingDirectory() != null && !scope.getWorkingDirectory().isBlank()) {
            Path runtimeRoot = scope.getRuntimeWorkingDirectory() != null && !scope.getRuntimeWorkingDirectory().isBlank()
                    ? Path.of(scope.getRuntimeWorkingDirectory())
                    : null;
            return new ScopedWorkspace(localWorkspace, Path.of(scope.getWorkingDirectory()), runtimeRoot);
        }
        return localWorkspace;
    }
}
