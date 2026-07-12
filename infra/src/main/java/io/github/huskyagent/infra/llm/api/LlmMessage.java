package io.github.huskyagent.infra.llm.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Neutral chat message for transport request building.
 * Tool results use {@link Role#TOOL} with {@code toolCallId}.
 * Multimodal user content uses {@link #parts()}; when empty, {@link #content()} is plain text.
 */
public record LlmMessage(
        Role role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolCallId,
        String name,
        List<LlmContentPart> parts
) {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public LlmMessage {
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        if (parts == null) {
            parts = List.of();
        } else {
            parts = List.copyOf(parts);
        }
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content, List.of(), null, null, List.of());
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, List.of(), null, null, List.of());
    }

    /**
     * User message with multimodal parts (text + images). {@code text} is also stored in
     * {@link #content()} for logging/fallback; wire encoding uses {@link #parts()}.
     */
    public static LlmMessage userMultimodal(String text, List<LlmContentPart> parts) {
        List<LlmContentPart> effective = new ArrayList<>();
        if (parts != null) {
            for (LlmContentPart part : parts) {
                if (part != null) {
                    effective.add(part);
                }
            }
        }
        if (effective.isEmpty()) {
            return user(text);
        }
        // Ensure at least one text part when caller only passed images + free text string
        boolean hasTextPart = effective.stream().anyMatch(p -> p.kind() == LlmContentPart.Kind.TEXT);
        if (!hasTextPart && text != null && !text.isBlank()) {
            effective.add(0, LlmContentPart.text(text));
        }
        return new LlmMessage(Role.USER, text, List.of(), null, null, List.copyOf(effective));
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls != null ? toolCalls : List.of(), null, null, List.of());
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId, null, List.of());
    }
}
