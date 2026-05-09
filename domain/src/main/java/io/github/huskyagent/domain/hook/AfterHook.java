package io.github.huskyagent.domain.hook;

/**
 * After Hook — 在动作执行后触发，纯通知，不可阻塞。
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>TOOL_CALL_START / TOOL_CALL_AFTER — TUI 渲染、指标记录</li>
 *   <li>LLM_CALL_AFTER — TUI 消息推送、指标记录</li>
 *   <li>SESSION_START / SESSION_END — 审计日志</li>
 * </ul>
 */
public interface AfterHook extends AgentHook {

    /**
     * 在动作执行后调用。
     * {@link HookContext#data()} 包含结果、状态等信息。
     * 异常会被 HookRegistry 捕获并记录日志，不会传播。
     */
    void after(HookContext context);
}
