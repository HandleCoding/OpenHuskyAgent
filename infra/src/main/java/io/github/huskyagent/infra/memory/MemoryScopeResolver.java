package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import org.springframework.stereotype.Component;

@Component
public class MemoryScopeResolver {

    public MemoryScope resolve(SessionScope sessionScope, String requestedScope) {
        String policy = sessionScope != null && sessionScope.getMemoryPolicy() != null
                ? sessionScope.getMemoryPolicy()
                : "SESSION";
        boolean currentOnly = requestedScope == null || !"all".equalsIgnoreCase(requestedScope);
        MemoryScope.SearchBoundary boundary = boundaryFor(policy, currentOnly);
        return MemoryScope.builder()
                .boundary(boundary)
                .currentSessionId(sessionScope != null ? sessionScope.getSessionId() : null)
                .principalId(sessionScope != null ? sessionScope.getPrincipalId() : null)
                .channelType(sessionScope != null ? sessionScope.getChannelType() : null)
                .agentId(sessionScope != null ? sessionScope.getAgentId() : null)
                .memoryPolicy(policy)
                .build();
    }

    private MemoryScope.SearchBoundary boundaryFor(String policy, boolean currentOnly) {
        if ("DISABLED".equals(policy)) {
            return MemoryScope.SearchBoundary.CURRENT_SESSION;
        }
        if (currentOnly || "SESSION".equals(policy) || "READONLY".equals(policy)) {
            return MemoryScope.SearchBoundary.CURRENT_SESSION;
        }
        if ("PRINCIPAL".equals(policy) || "USER_PROFILE".equals(policy)) {
            return MemoryScope.SearchBoundary.SAME_PRINCIPAL;
        }
        return MemoryScope.SearchBoundary.SAME_PRINCIPAL_AND_AGENT;
    }
}
