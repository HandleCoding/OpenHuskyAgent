package io.github.huskyagent.infra.llm.api;

import java.util.Locale;

/**
 * Wire protocol for model HTTP APIs. New vendors usually map to an existing protocol;
 * only non-OpenAI shapes need a new enum value + transport.
 */
public enum LlmProtocol {
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES;

    public static LlmProtocol fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI_CHAT_COMPLETIONS;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "openai_chat_completions", "openai", "openai_compatible", "openai-compatible", "chat_completions" ->
                    OPENAI_CHAT_COMPLETIONS;
            case "anthropic_messages", "anthropic", "claude" -> ANTHROPIC_MESSAGES;
            default -> throw new IllegalArgumentException(
                    "Unknown llm protocol '" + raw + "'. Supported: openai_chat_completions, anthropic_messages");
        };
    }
}
