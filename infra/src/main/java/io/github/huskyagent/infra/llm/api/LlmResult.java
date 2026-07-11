package io.github.huskyagent.infra.llm.api;

import java.util.List;

/**
 * Aggregated non-stream (or fully drained stream) result.
 */
public record LlmResult(
        String text,
        String reasoning,
        List<LlmToolCall> toolCalls,
        LlmUsage usage,
        String finishReason
) {
    public LlmResult {
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        if (usage == null) {
            usage = LlmUsage.empty();
        }
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isEmpty() {
        boolean noText = text == null || text.isBlank();
        boolean noReasoning = reasoning == null || reasoning.isBlank();
        return noText && noReasoning && !hasToolCalls();
    }
}
