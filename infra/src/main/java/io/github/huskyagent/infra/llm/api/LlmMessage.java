package io.github.huskyagent.infra.llm.api;

import java.util.List;

/**
 * Neutral chat message for transport request building.
 * Tool results use {@link Role#TOOL} with {@code toolCallId}.
 */
public record LlmMessage(
        Role role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolCallId,
        String name
) {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, List.of(), null, null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls != null ? toolCalls : List.of(), null, null);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId, null);
    }
}
