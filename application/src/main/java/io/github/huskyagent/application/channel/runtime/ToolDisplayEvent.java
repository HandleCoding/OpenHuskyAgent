package io.github.huskyagent.application.channel.runtime;

import java.time.Instant;

public record ToolDisplayEvent(
        String sessionId,
        String toolCallId,
        String toolName,
        String argsPreview,
        String toolArgs,
        ToolDisplayStatus status,
        long durationMs,
        String error,
        Instant timestamp
) implements RuntimeEvent {
}
