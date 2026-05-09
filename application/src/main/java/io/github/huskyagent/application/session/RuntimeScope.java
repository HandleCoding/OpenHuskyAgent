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

/**
 * 运行时作用域 — 单次执行边界。
 *
 * <p>由 Principal + ChannelIdentity + RuntimePolicy + sessionId 组合推导。
 * 运行过程不得再读取全局可变状态；所有行为从 RuntimeScope 推导。</p>
 */
@Value
@Builder
public class RuntimeScope {

    /** 持久化会话 ID */
    String sessionId;

    /** 已认证主体 */
    Principal principal;

    /** 渠道身份 */
    ChannelIdentity channelIdentity;

    /** 由 SceneConfig 和全局 catalog/defaults 推导出的运行时策略（唯一权威源） */
    RuntimePolicy runtimePolicy;

    /** 本次执行的工作目录（由 RuntimePolicy.workingDirectoryPolicy 推导） */
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
