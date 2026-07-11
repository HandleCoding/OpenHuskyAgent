package io.github.huskyagent.infra.session;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class SessionScope {
    String sessionId;
    String principalId;
    String channelType;
    String agentId;
    String workingDirectory;
    String memoryPolicy;
    String memoryStrategyId;
    String memoryPromptMode;
    Set<String> memoryProviderIds;
    boolean memoryWriteAllowed;
    boolean allowCrossSessionMemorySearch;
    Set<String> visibleSkillNames;
    Set<String> knowledgeSourceIds;
    String backendType;
    Boolean filesystemAvailable;
    String runtimeWorkingDirectory;
    String workspaceType;
    String checkpointType;
}
