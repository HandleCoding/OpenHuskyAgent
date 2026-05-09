package io.github.huskyagent.application;

import io.github.huskyagent.infra.context.TokenUsage;

/**
 * Result returned by a single chat execution.
 *
 * <p>When {@code streamed} is true, the full response has already been emitted
 * through the streaming callback, so callers should not print {@code content} again.</p>
 */
public record ChatResult(
    String content,
    boolean success,
    String errorMessage,
    ErrorCode errorCode,
    String sessionId,
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
