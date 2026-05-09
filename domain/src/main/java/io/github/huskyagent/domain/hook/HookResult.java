package io.github.huskyagent.domain.hook;

import java.util.Map;

public record HookResult(
        boolean allowed,
        String blockReason,
        Map<String, Object> modifications
) {

    private static final HookResult ALLOW = new HookResult(true, null, Map.of());

    public static HookResult allow() { return ALLOW; }

    public static HookResult block(String reason) {
        return new HookResult(false, reason, Map.of());
    }

    public static HookResult allowWith(Map<String, Object> mods) {
        return new HookResult(true, null, mods);
    }

    public boolean hasModifications() {
        return modifications != null && !modifications.isEmpty();
    }

    public <T> T getModification(String key, Class<T> type) {
        if (modifications == null) return null;
        Object value = modifications.get(key);
        return value != null && type.isInstance(value) ? type.cast(value) : null;
    }
}
