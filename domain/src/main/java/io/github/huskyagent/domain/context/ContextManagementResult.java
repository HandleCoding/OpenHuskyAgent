package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public record ContextManagementResult(
        List<Message> messages,
        boolean changed,
        String strategyId,
        String reason,
        Map<String, Object> diagnostics
) {
    public static ContextManagementResult unchanged(List<Message> messages, String strategyId, String reason) {
        return new ContextManagementResult(messages, false, strategyId, reason, Map.of());
    }

    public static ContextManagementResult changed(List<Message> messages, String strategyId, String reason, Map<String, Object> diagnostics) {
        return new ContextManagementResult(messages, true, strategyId, reason, diagnostics != null ? diagnostics : Map.of());
    }
}
