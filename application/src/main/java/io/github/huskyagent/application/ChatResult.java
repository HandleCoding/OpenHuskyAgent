package io.github.huskyagent.application;

import io.github.huskyagent.infra.context.TokenUsage;

/**
 * 对话结果
 *
 * <p>{@code streamed} 为 true 时表示内容已通过 textHandler 实时推送给调用方，
 * TUI 等展示层无需再次打印 {@code content}。</p>
 */
public record ChatResult(
    String content,
    boolean success,
    String errorMessage,
    ErrorCode errorCode,
    String sessionId,
    /** 内容是否已在流式回调中全量输出（true → 调用方不应再打印 content） */
    boolean streamed,
    TokenUsage tokenUsage
) {

    public enum ErrorCode {
        PARAM_ERROR,
        AUTH_ERROR,
        SESSION_ERROR,
        LLM_ERROR,
        INTERNAL_ERROR
    }

    public static ChatResult success(String content, String sessionId, boolean streamed, TokenUsage tokenUsage) {
        return new ChatResult(content, true, null, null, sessionId, streamed, tokenUsage);
    }

    public static ChatResult success(String content, String sessionId, boolean streamed) {
        return new ChatResult(content, true, null, null, sessionId, streamed, null);
    }

    public static ChatResult failure(String errorMessage) {
        return new ChatResult(null, false, errorMessage, ErrorCode.INTERNAL_ERROR, null, false, null);
    }

    public static ChatResult failure(String errorMessage, ErrorCode errorCode) {
        return new ChatResult(null, false, errorMessage, errorCode, null, false, null);
    }
}
