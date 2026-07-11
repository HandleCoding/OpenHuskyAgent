package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeBackendCapabilityResolver {

    private final ExecutionBackendProperties backendProperties;

    public String backendType(RuntimePolicy policy) {
        SceneConfig.BackendPolicy backendPolicy = policy != null ? policy.getBackendPolicy() : null;
        return backendPolicy != null ? backendPolicy.name().toLowerCase() : "local";
    }

    public boolean filesystemAvailable(RuntimePolicy policy) {
        if (policy == null) {
            return true;
        }
        return filesystemAvailable(policy.getBackendPolicy(), policy.getBackendSpec());
    }

    public boolean filesystemAvailable(SceneConfig sceneConfig) {
        if (sceneConfig == null) {
            return true;
        }
        return filesystemAvailable(sceneConfig.getBackendPolicy(), sceneConfig.getBackendSpec());
    }

    public boolean filesystemAvailable(SceneConfig.BackendPolicy backendPolicy, SceneConfig.BackendSpec spec) {
        if (backendPolicy == null || backendPolicy == SceneConfig.BackendPolicy.LOCAL) {
            return true;
        }
        if (backendPolicy == SceneConfig.BackendPolicy.DOCKER) {
            return dockerPersistFilesystem(spec);
        }
        return false;
    }

    public boolean dockerPersistFilesystem(SceneConfig.BackendSpec spec) {
        if (spec != null && spec.hasDockerPersistFilesystemOverride()) {
            return spec.isDockerPersistFilesystem();
        }
        return backendProperties != null && backendProperties.getDocker().isPersistFilesystem();
    }
}
