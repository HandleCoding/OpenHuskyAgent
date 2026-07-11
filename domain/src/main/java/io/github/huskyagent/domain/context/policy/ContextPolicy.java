package io.github.huskyagent.domain.context.policy;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.context.ContextConfig;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ContextPolicy {
    boolean enabled;
    String mode;
    String strategyId;
    String pruneStrategyId;
    String summaryStrategyId;
    double thresholdPercent;
    int contextLength;
    int protectFirstN;
    int tailTokenBudget;
    int maxSummaryTokens;
    String summaryFocus;

    public static ContextPolicy from(AgentDefinition.ContextPolicySpec scenePolicy, ContextConfig defaults) {
        return from(scenePolicy, defaults, null);
    }

    public static ContextPolicy from(AgentDefinition.ContextPolicySpec scenePolicy, ContextConfig defaults, String modelName) {
        AgentDefinition.ContextPolicySpec spec = scenePolicy != null ? scenePolicy : new AgentDefinition.ContextPolicySpec();
        return ContextPolicy.builder()
                .enabled(spec.isEnabled())
                .mode(spec.getMode() != null ? spec.getMode() : "prune-then-summary")
                .strategyId(spec.getStrategy() != null ? spec.getStrategy() : modeToStrategy(spec.getMode()))
                .pruneStrategyId(spec.getPruneStrategy() != null ? spec.getPruneStrategy() : "default")
                .summaryStrategyId(spec.getSummaryStrategy() != null ? spec.getSummaryStrategy() : "default")
                .thresholdPercent(spec.getThresholdPercent() != null ? spec.getThresholdPercent() : defaults.getThresholdPercent())
                .contextLength(spec.getContextLength() != null ? spec.getContextLength() : defaults.resolveContextLength(modelName))
                .protectFirstN(spec.getProtectFirstN() != null ? spec.getProtectFirstN() : defaults.getProtectFirstN())
                .tailTokenBudget(spec.getTailTokenBudget() != null ? spec.getTailTokenBudget() : defaults.getTailTokenBudget())
                .maxSummaryTokens(spec.getMaxSummaryTokens() != null ? spec.getMaxSummaryTokens() : defaults.getMaxSummaryTokens())
                .summaryFocus(spec.getSummaryFocus())
                .build();
    }

    public String fingerprint() {
        return String.join("|",
                Boolean.toString(enabled), mode, strategyId, pruneStrategyId, summaryStrategyId,
                Double.toString(thresholdPercent), Integer.toString(contextLength),
                Integer.toString(protectFirstN), Integer.toString(tailTokenBudget),
                Integer.toString(maxSummaryTokens), summaryFocus != null ? summaryFocus : "");
    }

    private static String modeToStrategy(String mode) {
        if (mode == null) {
            return "default";
        }
        return switch (mode) {
            case "none", "disabled" -> "none";
            default -> "default";
        };
    }
}
