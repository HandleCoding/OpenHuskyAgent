package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.agent.AgentDefinition;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTokenBucketRateLimiterTest {

    @Test
    void disabledAlwaysAllows() {
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter();
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        spec.setEnabled(false);
        spec.setRequestsPerMinute(1);

        assertTrue(limiter.tryAcquire("a", "u", spec).allowed());
        assertTrue(limiter.tryAcquire("a", "u", spec).allowed());
        assertTrue(limiter.tryAcquire("a", "u", null).allowed());
    }

    @Test
    void burstThenDenyUntilRefill() {
        AtomicLong now = new AtomicLong(0L);
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(now::get);
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        spec.setEnabled(true);
        spec.setRequestsPerMinute(60); // 1 token/sec
        spec.setBurst(2);

        assertTrue(limiter.tryAcquire("chatbot", "user-1", spec).allowed());
        assertTrue(limiter.tryAcquire("chatbot", "user-1", spec).allowed());
        RateLimitDecision denied = limiter.tryAcquire("chatbot", "user-1", spec);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);

        // Advance 1 second → one token refilled
        now.addAndGet(1_000_000_000L);
        assertTrue(limiter.tryAcquire("chatbot", "user-1", spec).allowed());
    }

    @Test
    void principalsAreIsolated() {
        AtomicLong now = new AtomicLong(0L);
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(now::get);
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        spec.setEnabled(true);
        spec.setRequestsPerMinute(60);
        spec.setBurst(1);

        assertTrue(limiter.tryAcquire("chatbot", "user-a", spec).allowed());
        assertFalse(limiter.tryAcquire("chatbot", "user-a", spec).allowed());
        assertTrue(limiter.tryAcquire("chatbot", "user-b", spec).allowed());
    }

    @Test
    void agentsAreIsolated() {
        AtomicLong now = new AtomicLong(0L);
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(now::get);
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        spec.setEnabled(true);
        spec.setRequestsPerMinute(60);
        spec.setBurst(1);

        assertTrue(limiter.tryAcquire("assistant", "user-1", spec).allowed());
        assertFalse(limiter.tryAcquire("assistant", "user-1", spec).allowed());
        assertTrue(limiter.tryAcquire("chatbot", "user-1", spec).allowed());
    }

    @Test
    void rateLimitKeyUsesAnonymousFallback() {
        assertEquals("5:agent:anonymous", InMemoryTokenBucketRateLimiter.rateLimitKey("agent", null));
        assertEquals("5:agent:anonymous", InMemoryTokenBucketRateLimiter.rateLimitKey("agent", "  "));
    }

    @Test
    void rateLimitKeyAvoidsColonCollision() {
        String a = InMemoryTokenBucketRateLimiter.rateLimitKey("a", "b:c");
        String b = InMemoryTokenBucketRateLimiter.rateLimitKey("a:b", "c");
        assertNotEquals(a, b);
    }

    @Test
    void enabledWithInvalidRpmFailsClosed() {
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter();
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        spec.setEnabled(true);
        spec.setRequestsPerMinute(0);
        assertFalse(limiter.tryAcquire("a", "u", spec).allowed());
    }
}
