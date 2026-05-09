package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.session.SessionScope;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LocalWorkspaceProvider implements WorkspaceProvider {

    private final LocalWorkspace localWorkspace;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public Workspace workspace(SessionScope scope) {
        return localWorkspace;
    }
}
