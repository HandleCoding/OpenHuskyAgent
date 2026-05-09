package io.github.huskyagent.infra.auth;

import io.github.huskyagent.infra.channel.Principal;

/**
 * 当前请求的 Principal 上下文 — ThreadLocal 存储。
 *
 * <p>由各入口在执行前设置，执行结束后清除。
 * 替代原有 AuthContext（裸 userId string），提供 typed Principal。</p>
 */
public final class PrincipalContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {}

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    /** 获取 principalId，为 null 时返回 "default" */
    public static String getPrincipalId() {
        Principal p = CURRENT.get();
        return p != null ? p.getId() : "default";
    }

    public static void clear() {
        CURRENT.remove();
    }
}