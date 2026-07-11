package io.github.huskyagent.infra.llm.api;

/**
 * Normalized token usage across providers.
 */
public record LlmUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer cachedPromptTokens,
        Integer reasoningTokens
) {
    public static LlmUsage empty() {
        return new LlmUsage(null, null, null, null, null);
    }

    public static LlmUsage of(Integer prompt, Integer completion, Integer total) {
        return new LlmUsage(prompt, completion, total, null, null);
    }
}
