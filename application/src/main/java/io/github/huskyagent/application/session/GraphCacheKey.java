package io.github.huskyagent.application.session;

import lombok.Value;

import java.nio.file.Path;

/**
 * Graph 缓存键 — 保证不同 SceneConfig / workingDirectory / session 不共用 compiledGraph。
 *
 * <p>缓存按 scene/policy/session 共享。同一 scene 下不同渠道（Feishu 多实例、HTTP 多用户）
 * 共享 CompiledGraph，因为渠道差异通过 dynamic prompt（每轮 LLM 调用刷新）注入，
 * 不影响 graph 拓扑（节点连线、工具集、prompt section 注册）。</p>
 *
 * <p>sessionId 保留是因为 ToolCallbackFactory 在构建时把 sessionId bake 进 FunctionToolCallback
 * 的 lambda closure。不同 session 共享 graph 会导致工具回调使用错误的 sessionId。</p>
 */
@Value
public class GraphCacheKey {

    String sceneId;
    String workingDirectory;
    String runtimePolicyFingerprint;
    String promptVariant;
    String principalId;
    String sessionScopeId;

    public static GraphCacheKey of(String sceneId, Path workingDirectory,
                                   String runtimePolicyFingerprint,
                                   String promptVariant, String principalId,
                                   String sessionScopeId) {
        return new GraphCacheKey(
                sceneId,
                workingDirectory.toString(),
                runtimePolicyFingerprint != null ? runtimePolicyFingerprint : "default",
                promptVariant != null ? promptVariant : "default",
                principalId != null ? principalId : "default",
                sessionScopeId != null ? sessionScopeId : "shared"
        );
    }
}