package io.github.huskyagent.application.session;

import lombok.Value;

import java.nio.file.Path;

@Value
public class GraphCacheKey {

    /** Keeps per-session callback closures from leaking across otherwise shared compiled graphs. */
    String agentId;
    String workingDirectory;
    String runtimePolicyFingerprint;
    String promptVariant;
    String principalId;
    String sessionScopeId;

    public static GraphCacheKey of(String agentId, Path workingDirectory,
                                   String runtimePolicyFingerprint,
                                   String promptVariant, String principalId,
                                   String sessionScopeId) {
        return new GraphCacheKey(
                agentId,
                workingDirectory.toString(),
                runtimePolicyFingerprint != null ? runtimePolicyFingerprint : "default",
                promptVariant != null ? promptVariant : "default",
                principalId != null ? principalId : "default",
                sessionScopeId != null ? sessionScopeId : "shared"
        );
    }
}