package io.github.huskyagent.domain.hook;

import java.util.Map;

/**
 * Hook 执行结果 — BeforeHook 的返回值。
 *
 * <p>三种语义：</p>
 * <ul>
 *   <li>{@link #allow()} — 放行，不修改</li>
 *   <li>{@link #block(String)} — 阻止执行，携带原因</li>
 *   <li>{@link #allowWith(Map)} — 放行并携带修改（如注入上下文、自动审批）</li>
 * </ul>
 */
public record HookResult(
        boolean allowed,
        String blockReason,
        Map<String, Object> modifications
) {

    private static final HookResult ALLOW = new HookResult(true, null, Map.of());

    /** 放行 */
    public static HookResult allow() { return ALLOW; }

    /** 阻止执行 */
    public static HookResult block(String reason) {
        return new HookResult(false, reason, Map.of());
    }

    /** 放行并携带修改 */
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
