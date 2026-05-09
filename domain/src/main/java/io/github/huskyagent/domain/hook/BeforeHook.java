package io.github.huskyagent.domain.hook;

/**
 * Before Hook — 在动作执行前触发，可阻塞或修改上下文。
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>TOOL_CALL_BEFORE — 熔断器阻塞</li>
 *   <li>LLM_CALL_BEFORE — 注入上下文到 prompt</li>
 *   <li>APPROVAL_BEFORE — 自动审批/拒绝</li>
 * </ul>
 */
public interface BeforeHook extends AgentHook {

    /**
     * 在动作执行前调用。
     *
     * @return {@link HookResult#allow()} 继续执行，
     *         {@link HookResult#block(String)} 中止执行，
     *         {@link HookResult#allowWith(java.util.Map)} 继续并携带修改
     */
    HookResult before(HookContext context);
}
