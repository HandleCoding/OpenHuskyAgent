package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.adapter.ToolRuntimeEnvironment;
import io.github.huskyagent.infra.workspace.Workspace;

import java.util.List;
import java.util.Set;

final class FileToolRuntime {

    private FileToolRuntime() {
    }

    static Workspace workspace(ToolExecutionContext context, Workspace fallback) {
        if (context == null || !context.hasRuntimeEnvironment()) {
            throw new IllegalStateException("No runtime environment is available for file tools");
        }
        return context.workspace();
    }

    static ToolExecutionContext localContext(Workspace workspace) {
        return new ToolExecutionContext(
                "file-tool-local",
                null,
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new ToolRuntimeEnvironment("local", true, () -> workspace, null));
    }
}
