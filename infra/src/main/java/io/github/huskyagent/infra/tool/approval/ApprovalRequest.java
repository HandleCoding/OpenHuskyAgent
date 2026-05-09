package io.github.huskyagent.infra.tool.approval;

import java.util.Map;

/**
 * 审批请求
 */
public record ApprovalRequest(

    /**
     * 请求ID
     */
    String requestId,

    /**
     * 工具名称
     */
    String toolName,

    /**
     * 工具参数
     */
    Map<String, Object> arguments,

    /**
     * 危险原因说明
     */
    String reason,

    /**
     * 会话ID
     */
    String sessionId

) {

    public static ApprovalRequest of(String requestId, String toolName,
                                     Map<String, Object> arguments, String reason, String sessionId) {
        return new ApprovalRequest(requestId, toolName, arguments, reason, sessionId);
    }
}