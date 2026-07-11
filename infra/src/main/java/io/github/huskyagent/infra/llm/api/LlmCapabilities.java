package io.github.huskyagent.infra.llm.api;

/**
 * What a transport can do. Callers may fail-closed when a required capability is missing.
 */
public record LlmCapabilities(
        boolean streaming,
        boolean tools,
        boolean parallelTools,
        boolean vision,
        boolean reasoning
) {
    public static LlmCapabilities openaiChatCompletions() {
        return new LlmCapabilities(true, true, true, true, true);
    }

    public static LlmCapabilities anthropicMessages() {
        return new LlmCapabilities(true, true, true, true, true);
    }
}
