package io.github.huskyagent.infra.tool.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ApprovalService {

    private final Map<String, Set<String>> sessionAllowedTools = new ConcurrentHashMap<>();

    public void addSessionAllowedTool(String sessionId, String toolName) {
        sessionAllowedTools
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
        log.info("Tool {} added to allowlist for session {}", toolName, sessionId);
    }

    public boolean isSessionAllowed(String sessionId, String toolName) {
        Set<String> allowed = sessionAllowedTools.get(sessionId);
        return allowed != null && allowed.contains(toolName);
    }

    public Set<String> getSessionAllowedTools(String sessionId) {
        Set<String> allowed = sessionAllowedTools.get(sessionId);
        return allowed != null ? Set.copyOf(allowed) : Set.of();
    }

    public void clearSession(String sessionId) {
        sessionAllowedTools.remove(sessionId);
        log.debug("Cleared tool allowlist for session {}", sessionId);
    }
}