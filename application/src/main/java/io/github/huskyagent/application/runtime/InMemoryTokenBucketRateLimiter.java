package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.agent.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local token bucket rate limiter keyed by length-prefixed agentId + principalId.
 */
@Component
public class InMemoryTokenBucketRateLimiter implements AgentRateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public InMemoryTokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    /** Test hook with controllable clock (nanoseconds). */
    public InMemoryTokenBucketRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime != null ? nanoTime : System::nanoTime;
    }

    @Override
    public RateLimitDecision tryAcquire(String agentId, String principalId, AgentDefinition.RateLimitSpec spec) {
        if (spec == null || !spec.isEnabled()) {
            return RateLimitDecision.allow();
        }
        int rpm = spec.getRequestsPerMinute() != null ? spec.getRequestsPerMinute() : 0;
        if (rpm <= 0) {
            // Misconfigured enabled limit: fail closed
            return RateLimitDecision.deny(60_000L);
        }
        int burst = spec.getBurst() != null && spec.getBurst() > 0 ? spec.getBurst() : rpm;
        String key = rateLimitKey(agentId, principalId);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst));
        synchronized (bucket) {
            bucket.refill(nanoTime.getAsLong(), rpm, burst);
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                return RateLimitDecision.allow();
            }
            double deficit = 1.0d - bucket.tokens;
            double tokensPerSecond = rpm / 60.0d;
            long retryMs = tokensPerSecond > 0
                    ? (long) Math.ceil((deficit / tokensPerSecond) * 1000.0d)
                    : 60_000L;
            return RateLimitDecision.deny(Math.max(1L, retryMs));
        }
    }

    static String rateLimitKey(String agentId, String principalId) {
        String agent = agentId != null && !agentId.isBlank() ? agentId.trim() : "unknown-agent";
        String principal = principalId != null && !principalId.isBlank() ? principalId.trim() : "anonymous";
        // Length-prefix avoids collisions when principal ids contain ':' (e.g. api:user-1).
        return agent.length() + ":" + agent + ":" + principal;
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private boolean initialized;

        Bucket(int burst) {
            this.tokens = burst;
            this.lastRefillNanos = 0L;
            this.initialized = false;
        }

        void refill(long nowNanos, int rpm, int burst) {
            if (!initialized) {
                // Do not treat lastRefillNanos==0 as uninitialized — tests may use a zero clock.
                initialized = true;
                lastRefillNanos = nowNanos;
                tokens = burst;
                return;
            }
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0L) {
                return;
            }
            double tokensPerSecond = rpm / 60.0d;
            double add = tokensPerSecond * (elapsed / 1_000_000_000.0d);
            tokens = Math.min(burst, tokens + add);
            lastRefillNanos = nowNanos;
        }
    }
}
