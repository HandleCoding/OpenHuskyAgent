package io.github.huskyagent.tui;

/**
 * Token/message 流事件 DTO（client-side simplified version）。
 *
 * <p>去掉 toolCalls 字段（Spring AI 依赖），client 不需要——工具调用走独立 JSON-RPC 事件。</p>
 */
public record TextEvent(String text, boolean intermediate, String token, boolean reasoning) {

    public static TextEvent ofMessage(String text, boolean intermediate) {
        return new TextEvent(text, intermediate, null, false);
    }

    public static TextEvent ofToken(String token) {
        return new TextEvent(null, false, token, false);
    }

    public static TextEvent ofReasoning(String token) {
        return new TextEvent(null, false, token, true);
    }

    public boolean isTokenEvent() {
        return token != null;
    }
}