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
    TokenUsage tokenUsage,
    Long retryAfterSeconds
) {

    public enum ErrorCode {
        PARAM_ERROR,
        AUTH_ERROR,
        SESSION_ERROR,
        LLM_ERROR,
        CANCELLED,
        RATE_LIMITED,
        INTERNAL_ERROR
    }

    public static ChatResult success(String content, String sessionId, boolean streamed, TokenUsage tokenUsage) {
        return new ChatResult(content, true, null, null, sessionId, streamed, tokenUsage, null);
    }

    public static ChatResult success(String content, String sessionId, boolean streamed) {
        return new ChatResult(content, true, null, null, sessionId, streamed, null, null);
    }

    public static ChatResult failure(String errorMessage) {
        return new ChatResult(null, false, errorMessage, ErrorCode.INTERNAL_ERROR, null, false, null, null);
    }

    public static ChatResult failure(String errorMessage, ErrorCode errorCode) {
        return new ChatResult(null, false, errorMessage, errorCode, null, false, null, null);
    }

    public static ChatResult cancelled(String sessionId, String message) {
        return new ChatResult(null, false, message != null ? message : "Run cancelled",
                ErrorCode.CANCELLED, sessionId, false, null, null);
    }

    /**
     * @param retryAfterSeconds optional structured hint for HTTP Retry-After (may be null)
     */
    public static ChatResult rateLimited(String message, Long retryAfterSeconds) {
        String text = message != null && !message.isBlank()
                ? message
                : "Rate limit exceeded";
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            text = text + " Retry after " + retryAfterSeconds + "s.";
        }
        return new ChatResult(null, false, text, ErrorCode.RATE_LIMITED, null, false, null, retryAfterSeconds);
    }
}
