package io.github.huskyagent.application.session;

import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.session.SessionScope;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class RuntimeScope {

    String sessionId;

    Principal principal;

    ChannelIdentity channelIdentity;

    RuntimePolicy runtimePolicy;

    Path workingDirectory;

    public RuntimeScope withWorkingDirectory(Path workingDirectory) {
        requireCompleteForExecution();
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory is required");
        }
        return RuntimeScope.builder()
                .sessionId(sessionId)
                .principal(principal)
                .channelIdentity(channelIdentity)
                .runtimePolicy(runtimePolicy)
                .workingDirectory(workingDirectory)
                .build();
    }

    public void requireCompleteForExecution() {
        List<String> missing = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) missing.add("sessionId");
        if (principal == null) missing.add("principal");
        if (channelIdentity == null) missing.add("channelIdentity");
        if (runtimePolicy == null) missing.add("runtimePolicy");
        if (workingDirectory == null) missing.add("workingDirectory");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Incomplete RuntimeScope, missing: " + String.join(", ", missing));
        }
    }

    public SessionScope toSessionScope() {
        requireCompleteForExecution();
        return SessionScope.builder()
                .sessionId(sessionId)
                .principalId(principal.getId())
                .channelType(channelIdentity.getChannelType().getName())
                .sceneId(runtimePolicy.getSceneId())
                .workingDirectory(workingDirectory.toString())
                .memoryPolicy(runtimePolicy.getMemoryPolicy().legacyPolicy().name())
                .memoryStrategyId(runtimePolicy.getMemoryPolicy().getStrategyId())
                .memoryPromptMode(runtimePolicy.getMemoryPolicy().getPromptMode().name())
                .memoryProviderIds(runtimePolicy.getMemoryPolicy().getProviders())
                .memoryWriteAllowed(runtimePolicy.getMemoryPolicy().writeAllowed())
                .allowCrossSessionMemorySearch(runtimePolicy.getMemoryPolicy().isAllowCrossSessionSearch())
                .visibleSkillNames(runtimePolicy.getCapabilityView().getVisibleSkillNames())
                .knowledgeSourceIds(runtimePolicy.getKnowledgeSources())
                .workspaceType(runtimePolicy.effectiveWorkspaceType())
                .checkpointType(runtimePolicy.effectiveCheckpointType())
                .build();
    }
}
