package io.github.huskyagent.application.agent;

public record ApprovalContext(
        String sessionId,
        String toolName,
        String toolArgs,
        String agentText,
        String reason,
        ApprovalResponder responder
) {
    public void approve(boolean approved, boolean always) {
        responder.respond(approved, always);
    }
}
