package io.github.huskyagent.domain.hook;

import java.util.List;

/**
 * Hook 注册表接口 — 管理所有 Hook 的注册、发现和触发。
 */
public interface HookRegistry {

    /** 注册一个 Hook */
    void register(AgentHook hook);

    /** 按 name 取消注册 */
    void unregister(String hookName);

    /**
     * 触发 Before 事件。
     * 按 order 有序执行所有 BeforeHook，首个 block 即返回；
     * 收集所有 modifications 合并到最终结果。
     */
    HookResult fireBefore(HookEvent event, String sessionId, java.util.Map<String, Object> data);

    /**
     * 触发 After 事件。
     * 按 order 有序执行所有 AfterHook，异常仅记录日志不传播。
     */
    void fireAfter(HookEvent event, String sessionId, java.util.Map<String, Object> data);

    /** 获取所有已注册 Hook */
    List<AgentHook> getHooks();

    /** 获取指定事件的 Hook 列表 */
    List<AgentHook> getHooks(HookEvent event);
}
