package io.github.huskyagent.domain.context;

/**
 * 剪枝配置
 */
public record PruneConfig(
        int protectFirstN,
        int tailTokenBudget
) {
    public static PruneConfig of(int protectFirstN, int tailTokenBudget) {
        return new PruneConfig(protectFirstN, tailTokenBudget);
    }
}
