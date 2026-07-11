package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.agent.AgentDefinition;

/**
 * Per-agent inbound rate limiting. Implementations may be process-local or distributed later.
 */
public interface AgentRateLimiter {

    /**
     * Attempt to consume one inbound turn for the given agent + principal.
     * When {@code spec} is null or disabled, always allows without side effects.
     */
    RateLimitDecision tryAcquire(String agentId, String principalId, AgentDefinition.RateLimitSpec spec);

    static AgentRateLimiter allowAll() {
        return (agentId, principalId, spec) -> RateLimitDecision.allow();
    }
}
