package io.github.huskyagent.infra.auth;

import io.github.huskyagent.infra.channel.Principal;

public final class PrincipalContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {}

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static String getPrincipalId() {
        Principal p = CURRENT.get();
        return p != null ? p.getId() : "default";
    }

    public static void clear() {
        CURRENT.remove();
    }
}