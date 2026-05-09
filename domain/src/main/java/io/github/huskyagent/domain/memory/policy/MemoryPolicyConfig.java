package io.github.huskyagent.domain.memory.policy;

import io.github.huskyagent.domain.scene.SceneConfig;
import lombok.Builder;
import lombok.Value;

import java.util.Set;
import java.util.stream.Collectors;

@Value
@Builder
public class MemoryPolicyConfig {
    boolean enabled;
    String strategyId;
    SceneConfig.MemoryAccess access;
    SceneConfig.MemoryScopePolicy scope;
    Set<String> providers;
    SceneConfig.MemoryPromptMode promptMode;
    boolean allowCrossSessionSearch;

    public static MemoryPolicyConfig from(SceneConfig.MemoryPolicySpec spec) {
        SceneConfig.MemoryPolicySpec effective = spec != null ? spec : new SceneConfig.MemoryPolicySpec();
        return MemoryPolicyConfig.builder()
                .enabled(effective.isEnabled())
                .strategyId(effective.getStrategy() != null ? effective.getStrategy() : "default")
                .access(effective.getAccess())
                .scope(effective.getScope())
                .providers(effective.getProviders() != null ? Set.copyOf(effective.getProviders()) : Set.of())
                .promptMode(effective.getPromptMode())
                .allowCrossSessionSearch(effective.isAllowCrossSessionSearch())
                .build();
    }

    public boolean writeAllowed() {
        return enabled && access == SceneConfig.MemoryAccess.READWRITE;
    }

    public SceneConfig.LegacyMemoryPolicy legacyPolicy() {
        if (!enabled || access == SceneConfig.MemoryAccess.DISABLED) {
            return SceneConfig.LegacyMemoryPolicy.DISABLED;
        }
        if (access == SceneConfig.MemoryAccess.READONLY) {
            return SceneConfig.LegacyMemoryPolicy.READONLY;
        }
        return switch (scope) {
            case USER_PROFILE -> SceneConfig.LegacyMemoryPolicy.USER_PROFILE;
            case PRINCIPAL -> SceneConfig.LegacyMemoryPolicy.PRINCIPAL;
            case SCENE -> SceneConfig.LegacyMemoryPolicy.SCENE;
            default -> SceneConfig.LegacyMemoryPolicy.SESSION;
        };
    }

    public String fingerprint() {
        String providerHash = providers == null || providers.isEmpty()
                ? "all"
                : providers.stream().sorted().collect(Collectors.joining(","));
        return String.join("|",
                Boolean.toString(enabled), strategyId, String.valueOf(access), String.valueOf(scope),
                providerHash, String.valueOf(promptMode), Boolean.toString(allowCrossSessionSearch));
    }
}
