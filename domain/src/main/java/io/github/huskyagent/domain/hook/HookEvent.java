package io.github.huskyagent.domain.hook;

/**
 * Hook 事件类型 — Agent 生命周期中可被 Hook 拦截/观察的节点。
 *
 * <p>BeforeHook 事件可在动作执行前拦截（block）或修改上下文；
 * AfterHook 事件在动作执行后通知，不可拦截。</p>
 */
public enum HookEvent {

    // ── 会话生命周期 ──────────────────────────────────────────────────────────
    /** 会话创建后 */
    SESSION_START,
    /** 会话结束前 */
    SESSION_END,
    /** 会话重置时（/new /reset） */
    SESSION_RESET,

    // ── LLM 调用 ─────────────────────────────────────────────────────────────
    /** LLM 调用前 — BeforeHook 可注入上下文（modifications["context"]） */
    LLM_CALL_BEFORE,
    /** LLM 调用后 — 携带响应文本、tool calls、耗时等 */
    LLM_CALL_AFTER,

    // ── 工具调用 ─────────────────────────────────────────────────────────────
    /** 工具执行前 — BeforeHook 可阻塞（如熔断器） */
    TOOL_CALL_BEFORE,
    /** 工具开始执行 — 纯通知，不可阻塞（对应旧 ToolEvent.STARTED） */
    TOOL_CALL_START,
    /** 工具执行后 — 携带结果、耗时、错误信息 */
    TOOL_CALL_AFTER,

    // ── 审批 ─────────────────────────────────────────────────────────────────
    /** 审批决策前 — BeforeHook 可自动审批（modifications["decision"]="approved"） */
    APPROVAL_BEFORE,
    /** 审批决策后 */
    APPROVAL_AFTER,

    // ── 用户交互中断 ─────────────────────────────────────────────────────────
    /** 向用户发起澄清问题前 */
    CLARIFY_BEFORE,
    /** 用户回答澄清问题后 */
    CLARIFY_AFTER,

    // ── 上下文 ───────────────────────────────────────────────────────────────
    /** 上下文压缩后 */
    CONTEXT_COMPRESS,
    /** 发送消息前 */
    MESSAGE_SEND,

    // ── 子 Agent（Phase 5） ──────────────────────────────────────────────────
    /** 子 Agent 启动后 */
    SUBAGENT_START,
    /** 子 Agent 运行中进度（工具调用等） */
    SUBAGENT_PROGRESS,
    /** 子 Agent 结束后 */
    SUBAGENT_STOP,

    // ── 应用生命周期 ─────────────────────────────────────────────────────────
    /** 应用启动后 */
    STARTUP,
    /** 应用关闭前 */
    SHUTDOWN
}
