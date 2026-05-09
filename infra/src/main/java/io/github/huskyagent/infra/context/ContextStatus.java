package io.github.huskyagent.infra.context;

/**
 * Context 压缩状态
 */
public record ContextStatus(
    int lastPromptTokens,
    int thresholdTokens,
    int contextLength,
    double usagePercent,
    int compressionCount
) {
    public static ContextStatus empty(int contextLength, int thresholdTokens) {
        return new ContextStatus(0, thresholdTokens, contextLength, 0.0, 0);
    }
}