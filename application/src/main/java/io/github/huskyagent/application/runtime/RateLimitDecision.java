package io.github.huskyagent.application.runtime;

/**
 * Result of a rate-limit check for one inbound user turn.
 */
public record RateLimitDecision(boolean allowed, long retryAfterMs) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0L);
    }

    public static RateLimitDecision deny(long retryAfterMs) {
        return new RateLimitDecision(false, Math.max(0L, retryAfterMs));
    }

    public long retryAfterSeconds() {
        if (retryAfterMs <= 0L) {
            return 0L;
        }
        return Math.max(1L, (retryAfterMs + 999L) / 1000L);
    }
}
