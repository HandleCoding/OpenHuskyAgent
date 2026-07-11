package io.github.huskyagent.domain.runtime;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.llm.ModelSelection;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Value
@Builder
public class RuntimePolicy {
    String agentId;
    CapabilityView capabilityView;
    ContextPolicy contextPolicy;
    MemoryPolicyConfig memoryPolicy;
    AgentDefinition.ApprovalPolicy approvalPolicy;
    AgentDefinition.BackendPolicy backendPolicy;
    AgentDefinition.WorkingDirectoryPolicy workingDirectoryPolicy;
    AgentDefinition.AuditSpec auditSpec;
    AgentDefinition.RateLimitSpec rateLimitSpec;
    Set<String> knowledgeSources;
    String systemPrompt;
    List<String> promptFiles;
    AgentDefinition.PromptFilePolicy promptFilePolicy;
    AgentDefinition.BackendSpec backendSpec;
    AgentDefinition.StoragePolicy storagePolicy;
    AgentDefinition.StorageSpec storageSpec;
    String fixedWorkingDirectory;
    /**
     * Effective LLM selection for this run (provider + model + sampling). Always resolved at policy assemble time.
     */
    ModelSelection modelSelection;

    public String fingerprint() {
        String base = String.join("|",
                agentId != null ? agentId : "",
                capabilityView != null ? capabilityView.fingerprint() : "",
                contextPolicy != null ? contextPolicy.fingerprint() : "",
                memoryPolicy != null ? memoryPolicy.fingerprint() : "",
                knowledgeSources != null ? String.join(",", knowledgeSources.stream().sorted().toList()) : "",
                systemPrompt != null ? systemPrompt : "",
                namesFingerprint(promptFiles),
                String.valueOf(promptFilePolicy),
                backendSpecFingerprint(backendSpec),
                fixedWorkingDirectory != null ? fixedWorkingDirectory : "",
                modelSelection != null ? modelSelection.fingerprint() : "",
                String.valueOf(approvalPolicy), String.valueOf(backendPolicy), String.valueOf(workingDirectoryPolicy));
        if (!isRemoteStorage()) {
            return base;
        }
        return base + "|" + storagePolicy + "|" + storageSpecFingerprint(storageSpec);
    }

    public boolean isRemoteStorage() {
        return storagePolicy == AgentDefinition.StoragePolicy.REMOTE;
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

    private String backendSpecFingerprint(AgentDefinition.BackendSpec spec) {
        if (spec == null) {
            return "";
        }
        return String.join(",",
                value(spec.getDockerImage()),
                value(spec.getDockerMemory()),
                value(spec.getDockerCpus()),
                value(spec.getDockerPersistFilesystem()),
                value(spec.getDockerWorkdir()),
                value(spec.getSshHost()),
                Integer.toString(spec.getSshPort()),
                value(spec.getSshUser()),
                value(spec.getSshIdentityFile()));
    }

    private String storageSpecFingerprint(AgentDefinition.StorageSpec spec) {
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

    private String value(Boolean value) {
        return value != null ? value.toString() : "";
    }
}
