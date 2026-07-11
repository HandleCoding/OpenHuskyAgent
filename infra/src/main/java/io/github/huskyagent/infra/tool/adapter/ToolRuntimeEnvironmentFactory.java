package io.github.huskyagent.infra.tool.adapter;

import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.workspace.WorkspaceProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ToolRuntimeEnvironmentFactory {

    private final ExecutionBackendFactory executionBackendFactory;
    private final Map<String, WorkspaceProvider> workspaceProviders;

    public ToolRuntimeEnvironmentFactory(ExecutionBackendFactory executionBackendFactory,
                                         List<WorkspaceProvider> workspaceProviders) {
        this.executionBackendFactory = executionBackendFactory;
        this.workspaceProviders = buildProviderMap(workspaceProviders);
    }

    public ToolRuntimeEnvironment create(SessionScope scope) {
        String backendType = normalize(scope != null ? scope.getBackendType() : null, "local");
        boolean filesystemAvailable = filesystemAvailable(scope, backendType);
        String workspaceType = normalize(scope != null ? scope.getWorkspaceType() : null, "local");
        String sessionId = scope != null ? scope.getSessionId() : null;

        return new ToolRuntimeEnvironment(
                backendType,
                filesystemAvailable,
                () -> workspaceFor(workspaceType, scope),
                () -> {
                    if (sessionId == null || sessionId.isBlank()) {
                        throw new IllegalStateException("Execution backend requires a session id");
                    }
                    return executionBackendFactory.getForSession(sessionId, backendType);
                });
    }

    public ToolRuntimeEnvironment inherit(ToolExecutionContext parentContext, SessionScope childScope) {
        if (parentContext != null && parentContext.sessionId() != null
                && childScope != null && childScope.getSessionId() != null) {
            executionBackendFactory.inheritSession(parentContext.sessionId(), childScope.getSessionId());
        }
        return create(childScope);
    }

    private boolean filesystemAvailable(SessionScope scope, String backendType) {
        if (scope != null && scope.getFilesystemAvailable() != null) {
            return scope.getFilesystemAvailable();
        }
        return "local".equals(backendType);
    }

    private io.github.huskyagent.infra.workspace.Workspace workspaceFor(String workspaceType, SessionScope scope) {
        WorkspaceProvider provider = workspaceProviders.get(workspaceType);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported workspace type: " + workspaceType);
        }
        return provider.workspace(scope);
    }

    private Map<String, WorkspaceProvider> buildProviderMap(List<WorkspaceProvider> providers) {
        Map<String, WorkspaceProvider> result = new HashMap<>();
        for (WorkspaceProvider provider : providers) {
            String type = normalize(provider.type(), null);
            if (type == null) {
                throw new IllegalStateException("Workspace provider type is required");
            }
            if (result.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("Duplicate workspace provider type: " + type);
            }
        }
        return Map.copyOf(result);
    }

    private String normalize(String value, String defaultValue) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isEmpty()) {
            return defaultValue;
        }
        return normalized;
    }
}
