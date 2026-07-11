package io.github.huskyagent.domain.memory.policy;

import io.github.huskyagent.domain.agent.AgentDefinition;
import lombok.Builder;
import lombok.Value;

import java.util.Set;
import java.util.stream.Collectors;

@Value
@Builder
public class MemoryPolicyConfig {
    boolean enabled;
    String strategyId;
    AgentDefinition.MemoryAccess access;
    AgentDefinition.MemoryScopePolicy scope;
    Set<String> providers;
    AgentDefinition.MemoryPromptMode promptMode;
    boolean allowCrossSessionSearch;

    public static MemoryPolicyConfig from(AgentDefinition.MemoryPolicySpec spec) {
        AgentDefinition.MemoryPolicySpec effective = spec != null ? spec : new AgentDefinition.MemoryPolicySpec();
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
        return enabled && access == AgentDefinition.MemoryAccess.READWRITE;
    }

    public AgentDefinition.LegacyMemoryPolicy legacyPolicy() {
        if (!enabled || access == AgentDefinition.MemoryAccess.DISABLED) {
            return AgentDefinition.LegacyMemoryPolicy.DISABLED;
        }
        if (access == AgentDefinition.MemoryAccess.READONLY) {
            return AgentDefinition.LegacyMemoryPolicy.READONLY;
        }
        return switch (scope) {
            case USER_PROFILE -> AgentDefinition.LegacyMemoryPolicy.USER_PROFILE;
            case PRINCIPAL -> AgentDefinition.LegacyMemoryPolicy.PRINCIPAL;
            case AGENT -> AgentDefinition.LegacyMemoryPolicy.AGENT;
            default -> AgentDefinition.LegacyMemoryPolicy.SESSION;
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
