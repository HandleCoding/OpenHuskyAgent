package io.github.huskyagent.infra.llm.api;

/**
 * A complete tool call produced by the model.
 */
public record LlmToolCall(
        String id,
        String name,
        String argumentsJson
) {
}
