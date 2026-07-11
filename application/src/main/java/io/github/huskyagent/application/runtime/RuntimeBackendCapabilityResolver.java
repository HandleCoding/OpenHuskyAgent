package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeBackendCapabilityResolver {

    private final ExecutionBackendProperties backendProperties;

    public String backendType(RuntimePolicy policy) {
        AgentDefinition.BackendPolicy backendPolicy = policy != null ? policy.getBackendPolicy() : null;
        return backendPolicy != null ? backendPolicy.name().toLowerCase() : "local";
    }

    public boolean filesystemAvailable(RuntimePolicy policy) {
        if (policy == null) {
            return true;
        }
        return filesystemAvailable(policy.getBackendPolicy(), policy.getBackendSpec());
    }

    public boolean filesystemAvailable(AgentDefinition agentDefinition) {
        if (agentDefinition == null) {
            return true;
        }
        return filesystemAvailable(agentDefinition.getBackendPolicy(), agentDefinition.getBackendSpec());
    }

    public boolean filesystemAvailable(AgentDefinition.BackendPolicy backendPolicy, AgentDefinition.BackendSpec spec) {
        if (backendPolicy == null || backendPolicy == AgentDefinition.BackendPolicy.LOCAL) {
            return true;
        }
        if (backendPolicy == AgentDefinition.BackendPolicy.DOCKER) {
            return dockerPersistFilesystem(spec);
        }
        return false;
    }

    public boolean dockerPersistFilesystem(AgentDefinition.BackendSpec spec) {
        if (spec != null && spec.hasDockerPersistFilesystemOverride()) {
            return spec.isDockerPersistFilesystem();
        }
        return backendProperties != null && backendProperties.getDocker().isPersistFilesystem();
    }
}
