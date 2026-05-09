package io.github.huskyagent.domain.context;

public record SummaryConfig(
    int maxTokens,
    String focusTopic
) {
    public static SummaryConfig of(int maxTokens) {
        return new SummaryConfig(maxTokens, null);
    }

    public static SummaryConfig of(int maxTokens, String focusTopic) {
        return new SummaryConfig(maxTokens, focusTopic);
    }
}
