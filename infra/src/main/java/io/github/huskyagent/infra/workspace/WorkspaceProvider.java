package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.session.SessionScope;

public interface WorkspaceProvider {
    String type();

    Workspace workspace(SessionScope scope);
}
