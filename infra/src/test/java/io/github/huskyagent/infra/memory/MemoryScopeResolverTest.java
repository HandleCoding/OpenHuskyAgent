package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryScopeResolverTest {

    private final MemoryScopeResolver resolver = new MemoryScopeResolver();

    @Test
    void nullScopeDefaultsToCurrentSessionPolicyWithoutRuntimeIdentity() {
        MemoryScope scope = resolver.resolve(null, "all");

        assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION, scope.getBoundary());
        assertEquals("SESSION", scope.getMemoryPolicy());
        assertNull(scope.getCurrentSessionId());
        assertNull(scope.getPrincipalId());
        assertNull(scope.getAgentId());
    }

    @Test
    void currentRequestAlwaysUsesCurrentSession() {
        for (String policy : new String[]{"SESSION", "READONLY", "PRINCIPAL", "USER_PROFILE", "AGENT"}) {
            MemoryScope scope = resolver.resolve(scope(policy), "current");

            assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION, scope.getBoundary(), policy);
            assertEquals("session-1", scope.getCurrentSessionId());
            assertEquals("principal-1", scope.getPrincipalId());
            assertEquals("scene-1", scope.getAgentId());
            assertEquals(policy, scope.getMemoryPolicy());
        }
    }

    @Test
    void allRequestUsesPolicyBoundary() {
        assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION,
                resolver.resolve(scope("SESSION"), "all").getBoundary());
        assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION,
                resolver.resolve(scope("READONLY"), "all").getBoundary());
        assertEquals(MemoryScope.SearchBoundary.SAME_PRINCIPAL,
                resolver.resolve(scope("PRINCIPAL"), "all").getBoundary());
        assertEquals(MemoryScope.SearchBoundary.SAME_PRINCIPAL,
                resolver.resolve(scope("USER_PROFILE"), "all").getBoundary());
        assertEquals(MemoryScope.SearchBoundary.SAME_PRINCIPAL_AND_AGENT,
                resolver.resolve(scope("AGENT"), "all").getBoundary());
    }

    @Test
    void disabledPolicyNeverBroadens() {
        MemoryScope scope = resolver.resolve(scope("DISABLED"), "all");

        assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION, scope.getBoundary());
        assertEquals("DISABLED", scope.getMemoryPolicy());
    }

    private SessionScope scope(String policy) {
        return SessionScope.builder()
                .sessionId("session-1")
                .principalId("principal-1")
                .channelType("http")
                .agentId("scene-1")
                .memoryPolicy(policy)
                .memoryStrategyId("default")
                .build();
    }
}
