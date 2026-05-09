package io.github.huskyagent.infra.auth;

public final class AuthContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(String userId) {
        CURRENT.set(userId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}