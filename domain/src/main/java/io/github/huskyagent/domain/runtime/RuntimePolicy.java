package io.github.huskyagent.domain.runtime;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.scene.SceneConfig;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Value
@Builder
public class RuntimePolicy {
    String sceneId;
    CapabilityView capabilityView;
    ContextPolicy contextPolicy;
    MemoryPolicyConfig memoryPolicy;
    SceneConfig.ApprovalPolicy approvalPolicy;
    SceneConfig.BackendPolicy backendPolicy;
    SceneConfig.WorkingDirectoryPolicy workingDirectoryPolicy;
    SceneConfig.AuditSpec auditSpec;
    SceneConfig.RateLimitSpec rateLimitSpec;
    Set<String> knowledgeSources;
    String systemPrompt;
    List<String> promptFiles;
    SceneConfig.PromptFilePolicy promptFilePolicy;
    SceneConfig.BackendSpec backendSpec;
    SceneConfig.StoragePolicy storagePolicy;
    SceneConfig.StorageSpec storageSpec;
    String fixedWorkingDirectory;

    public String fingerprint() {
        String base = String.join("|",
                sceneId != null ? sceneId : "",
                capabilityView != null ? capabilityView.fingerprint() : "",
                contextPolicy != null ? contextPolicy.fingerprint() : "",
                memoryPolicy != null ? memoryPolicy.fingerprint() : "",
                knowledgeSources != null ? String.join(",", knowledgeSources.stream().sorted().toList()) : "",
                systemPrompt != null ? systemPrompt : "",
                namesFingerprint(promptFiles),
                String.valueOf(promptFilePolicy),
                backendSpecFingerprint(backendSpec),
                fixedWorkingDirectory != null ? fixedWorkingDirectory : "",
                String.valueOf(approvalPolicy), String.valueOf(backendPolicy), String.valueOf(workingDirectoryPolicy));
        if (!isRemoteStorage()) {
            return base;
        }
        return base + "|" + storagePolicy + "|" + storageSpecFingerprint(storageSpec);
    }

    public boolean isRemoteStorage() {
        return storagePolicy == SceneConfig.StoragePolicy.REMOTE;
    }

    public String effectiveWorkspaceType() {
        if (!isRemoteStorage() || storageSpec == null) {
            return "local";
        }
        return typeOrLocal(storageSpec.getWorkspaceType());
    }

    public String effectiveCheckpointType() {
        if (!isRemoteStorage() || storageSpec == null) {
            return "local";
        }
        return typeOrLocal(storageSpec.getCheckpointType());
    }

    private String namesFingerprint(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        return names.stream().sorted().collect(Collectors.joining(","));
    }

    private String backendSpecFingerprint(SceneConfig.BackendSpec spec) {
        if (spec == null) {
            return "";
        }
        return String.join(",",
                value(spec.getDockerImage()),
                value(spec.getDockerMemory()),
                value(spec.getDockerCpus()),
                Boolean.toString(spec.isDockerPersistFilesystem()),
                value(spec.getDockerWorkdir()),
                value(spec.getSshHost()),
                Integer.toString(spec.getSshPort()),
                value(spec.getSshUser()),
                value(spec.getSshIdentityFile()));
    }

    private String storageSpecFingerprint(SceneConfig.StorageSpec spec) {
        if (spec == null) {
            return "";
        }
        return String.join(",",
                effectiveWorkspaceType(),
                value(spec.getWorkspaceEndpoint()),
                value(spec.getWorkspaceBucket()),
                value(spec.getWorkspaceRegion()),
                value(spec.getWorkspacePrefix()),
                effectiveCheckpointType(),
                value(spec.getCheckpointUrl()),
                value(spec.getCheckpointTable()));
    }

    private String typeOrLocal(String value) {
        String normalized = value(value).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "local" : normalized;
    }

    private String value(String value) {
        return value != null ? value.trim() : "";
    }
}
