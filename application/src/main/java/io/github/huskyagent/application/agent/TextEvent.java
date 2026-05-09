package io.github.huskyagent.application.agent;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public record TextEvent(String text, boolean intermediate, String token, boolean reasoning,
                        List<AssistantMessage.ToolCall> toolCalls) {

    public static TextEvent ofMessage(String text, boolean intermediate,
                                      List<AssistantMessage.ToolCall> toolCalls) {
        return new TextEvent(text, intermediate, null, false,
                toolCalls != null ? toolCalls : List.of());
    }

    public static TextEvent ofToken(String token) {
        return new TextEvent(null, false, token, false, List.of());
    }

    public static TextEvent ofReasoning(String token) {
        return new TextEvent(null, false, token, true, List.of());
    }

    public boolean isTokenEvent() {
        return token != null;
    }
}
