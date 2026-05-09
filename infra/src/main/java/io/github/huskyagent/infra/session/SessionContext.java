package io.github.huskyagent.infra.session;

import com.alibaba.ttl.TransmittableThreadLocal;

public final class SessionContext {

    private static final TransmittableThreadLocal<SessionScope> CURRENT = new TransmittableThreadLocal<>();

    private SessionContext() {}

    public static void set(String sessionId) {
        CURRENT.set(SessionScope.builder().sessionId(sessionId).build());
    }

    public static void setScope(SessionScope scope) {
        CURRENT.set(scope);
    }

    public static String get() {
        SessionScope scope = CURRENT.get();
        return scope != null ? scope.getSessionId() : null;
    }

    public static SessionScope getScope() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
