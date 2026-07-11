package io.github.huskyagent.infra.llm.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Protocol-neutral tool definition for the model request.
 */
public record LlmToolDefinition(
        String name,
        String description,
        JsonNode parametersSchema
) {
}
