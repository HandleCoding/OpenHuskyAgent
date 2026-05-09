package io.github.huskyagent.infra.tool.approval;

/**
 * 审批元数据 Key 常量
 *
 * <p>当图 interrupt 时，{@code InterruptionMetadata} 会携带这些 key 对应的值，
 * TUI / REST 层通过这些 key 提取工具名和参数并展示给用户。</p>
 */
public final class ApprovalInfo {

    private ApprovalInfo() {}

    /** 需要审批的工具名 */
    public static final String TOOL_NAME_KEY = "toolName";

    /** 需要审批的工具调用参数（JSON 字符串） */
    public static final String TOOL_ARGS_KEY = "toolArgs";

    /** 审批原因说明（由工具的 approvalChecker 提供） */
    public static final String TOOL_REASON_KEY = "toolReason";
}

