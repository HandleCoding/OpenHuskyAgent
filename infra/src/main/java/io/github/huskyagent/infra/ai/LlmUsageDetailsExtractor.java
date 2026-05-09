package io.github.huskyagent.infra.ai;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

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

        if (nativeUsage instanceof OpenAiApi.Usage openAiUsage) {
            return fromOpenAi(promptTokens, completionTokens, totalTokens, nativeUsageType, openAiUsage);
        }

        return new LlmUsageDetails(promptTokens, completionTokens, totalTokens,
                null, null, nativeUsageType, false);
    }

    private LlmUsageDetails fromOpenAi(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                       String nativeUsageType, OpenAiApi.Usage usage) {
        boolean hasPromptTokenDetails = usage.promptTokensDetails() != null;
        Integer cachedTokens = hasPromptTokenDetails ? usage.promptTokensDetails().cachedTokens() : null;
        Integer uncachedTokens = promptTokens != null && cachedTokens != null
                ? Math.max(promptTokens - cachedTokens, 0)
                : null;
        return new LlmUsageDetails(promptTokens, completionTokens, totalTokens,
                cachedTokens, uncachedTokens, nativeUsageType, hasPromptTokenDetails);
    }
}
