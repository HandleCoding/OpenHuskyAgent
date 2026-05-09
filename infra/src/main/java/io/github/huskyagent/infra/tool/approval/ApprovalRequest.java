package io.github.huskyagent.infra.tool.approval;

import java.util.Map;

public record ApprovalRequest(

    String requestId,

    String toolName,

    Map<String, Object> arguments,

    String reason,

    String sessionId

) {

    public static ApprovalRequest of(String requestId, String toolName,
                                     Map<String, Object> arguments, String reason, String sessionId) {
        return new ApprovalRequest(requestId, toolName, arguments, reason, sessionId);
    }
}