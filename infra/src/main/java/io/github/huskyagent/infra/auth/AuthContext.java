package io.github.huskyagent.infra.auth;

/**
 * 当前请求的 userId 上下文
 *
 * 由 ApiKeyAuthFilter 在 HTTP 请求处理前设置，请求结束后清除。
 * TUI 不走 HTTP 请求，filter 不触发，因此 TUI 侧 userId 恒为 "default"。
 */
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