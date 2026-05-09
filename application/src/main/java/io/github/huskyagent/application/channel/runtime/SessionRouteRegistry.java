package io.github.huskyagent.application.channel.runtime;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRouteRegistry {

    private final ConcurrentHashMap<String, SessionRoute> routes = new ConcurrentHashMap<>();

    public void register(SessionRoute route) {
        if (route == null || route.sessionId() == null || route.sessionId().isBlank()) {
            return;
        }
        routes.put(route.sessionId(), route);
    }

    public Optional<SessionRoute> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(routes.get(sessionId));
    }

    public void unregister(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        routes.remove(sessionId);
    }

    public void unregister(SessionRoute route) {
        if (route == null || route.sessionId() == null || route.sessionId().isBlank()) {
            return;
        }
        routes.remove(route.sessionId(), route);
    }
}