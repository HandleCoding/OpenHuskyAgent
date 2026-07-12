package io.github.huskyagent.infra.ai;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * Extracts token usage from Spring AI {@link Usage} when present.
 * Primary model path uses {@link io.github.huskyagent.infra.llm.api.LlmUsage} directly.
 */
@Component
public class LlmUsageDetailsExtractor {

    public LlmUsageDetails extract(Usage usage) {
        if (usage == null) {
            return LlmUsageDetails.empty();
        }

        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        Integer totalTokens = usage.getTotalTokens();
        Object nativeUsage = usage.getNativeUsage();
        String nativeUsageType = nativeUsage != null ? nativeUsage.getClass().getSimpleName() : null;

        return new LlmUsageDetails(promptTokens, completionTokens, totalTokens,
                null, null, nativeUsageType, false);
    }
}
