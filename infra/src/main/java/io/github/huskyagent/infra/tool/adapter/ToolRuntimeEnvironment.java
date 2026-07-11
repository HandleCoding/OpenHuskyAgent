package io.github.huskyagent.infra.tool.adapter;

import io.github.huskyagent.infra.execute.ExecutionBackend;
import io.github.huskyagent.infra.workspace.Workspace;

import java.util.Locale;
import java.util.function.Supplier;

public class ToolRuntimeEnvironment {

    private final String backendType;
    private final boolean filesystemAvailable;
    private final Supplier<Workspace> workspaceSupplier;
    private final Supplier<ExecutionBackend> executionBackendSupplier;

    public ToolRuntimeEnvironment(String backendType,
                                  boolean filesystemAvailable,
                                  Supplier<Workspace> workspaceSupplier,
                                  Supplier<ExecutionBackend> executionBackendSupplier) {
        this.backendType = normalizeBackendType(backendType);
        this.filesystemAvailable = filesystemAvailable;
        this.workspaceSupplier = workspaceSupplier;
        this.executionBackendSupplier = executionBackendSupplier;
    }

    public String backendType() {
        return backendType;
    }

    public boolean isLocalBackend() {
        return "local".equals(backendType);
    }

    public boolean hasFilesystem() {
        return filesystemAvailable;
    }

    public Workspace workspace() {
        if (!filesystemAvailable) {
            throw new IllegalStateException("File tools are not available for backend '" + backendType + "' in this runtime");
        }
        if (workspaceSupplier == null) {
            throw new IllegalStateException("No workspace is available for backend '" + backendType + "'");
        }
        return workspaceSupplier.get();
    }

    public ExecutionBackend executionBackend() {
        if (executionBackendSupplier == null) {
            throw new IllegalStateException("No execution backend is available for backend '" + backendType + "'");
        }
        return executionBackendSupplier.get();
    }

    private static String normalizeBackendType(String backendType) {
        String normalized = backendType != null ? backendType.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isEmpty() ? "local" : normalized;
    }
}
