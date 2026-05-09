package io.github.huskyagent.infra.ai;

public record LlmUsageDetails(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer cachedPromptTokens,
        Integer uncachedPromptTokens,
        String nativeUsageType,
        boolean hasPromptTokenDetails
) {
    public static LlmUsageDetails empty() {
        return new LlmUsageDetails(null, null, null, null, null, null, false);
    }
}
