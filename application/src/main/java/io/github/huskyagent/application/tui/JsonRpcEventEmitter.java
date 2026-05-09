package io.github.huskyagent.application.tui;

import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class JsonRpcEventEmitter {

    private final JsonRpcDispatcher dispatcher;

    public JsonRpcEventEmitter(JsonRpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void emitMessageDelta(String token, boolean reasoning) {
        dispatcher.sendEvent("message.delta", Map.of(
                "token", token != null ? token : "",
                "reasoning", reasoning
        ));
    }

    public void emitMessageIntermediate(String text, boolean intermediate) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("intermediate", intermediate);
        if (text != null) payload.put("text", text);
        dispatcher.sendEvent("message.intermediate", payload);
    }

    public void emitMessageComplete(String text, String status, long durationMs, boolean streamed) {
        dispatcher.sendEvent("message.complete", Map.of(
                "text", text != null ? text : "",
                "status", status != null ? status : "ok",
                "durationMs", durationMs,
                "streamed", streamed
        ));
    }

    public void emitToolStarted(String toolName, String argsPreview) {
        emitToolStarted(ToolStatusPayload.started(toolName, argsPreview, null, null));
    }

    public void emitToolStarted(String toolName, String argsPreview, String toolCallId) {
        emitToolStarted(ToolStatusPayload.started(toolName, argsPreview, null, toolCallId));
    }

    public void emitToolStarted(String toolName, String argsPreview, String toolArgs, String toolCallId) {
        emitToolStarted(ToolStatusPayload.started(toolName, argsPreview, toolArgs, toolCallId));
    }

    public void emitToolStarted(ToolStatusPayload status) {
        dispatcher.sendEvent("tool.started", status.toEventPayload(false, false));
    }

    public void emitToolCompleted(String toolName, String argsPreview, long durationMs) {
        emitToolCompleted(ToolStatusPayload.completed(toolName, argsPreview, null, durationMs, null));
    }

    public void emitToolCompleted(String toolName, String argsPreview, long durationMs, String toolCallId) {
        emitToolCompleted(ToolStatusPayload.completed(toolName, argsPreview, null, durationMs, toolCallId));
    }

    public void emitToolCompleted(String toolName, String argsPreview, String toolArgs, long durationMs, String toolCallId) {
        emitToolCompleted(ToolStatusPayload.completed(toolName, argsPreview, toolArgs, durationMs, toolCallId));
    }

    public void emitToolCompleted(ToolStatusPayload status) {
        dispatcher.sendEvent("tool.completed", status.toEventPayload(true, false));
    }

    public void emitToolFailed(String toolName, String argsPreview, long durationMs, String error) {
        emitToolFailed(ToolStatusPayload.failed(toolName, argsPreview, null, durationMs, error, null));
    }

    public void emitToolFailed(String toolName, String argsPreview, long durationMs, String error, String toolCallId) {
        emitToolFailed(ToolStatusPayload.failed(toolName, argsPreview, null, durationMs, error, toolCallId));
    }

    public void emitToolFailed(String toolName, String argsPreview, String toolArgs, long durationMs, String error, String toolCallId) {
        emitToolFailed(ToolStatusPayload.failed(toolName, argsPreview, toolArgs, durationMs, error, toolCallId));
    }

    public void emitToolFailed(ToolStatusPayload status) {
        dispatcher.sendEvent("tool.failed", status.toEventPayload(true, true));
    }

    public void emitApprovalRequest(String requestId, String toolName, String toolArgs,
                                     String reason, String agentText) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("toolName", toolName);
        payload.put("toolArgs", toolArgs != null ? toolArgs : "{}");
        if (reason != null) payload.put("reason", reason);
        if (agentText != null) payload.put("agentText", agentText);
        dispatcher.sendEvent("approval.request", payload);
    }

    public void emitClarifyRequest(String requestId, String question, java.util.List<String> options,
                                   String agentText) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("question", question != null ? question : "");
        payload.put("options", options != null ? options : java.util.List.of());
        if (agentText != null) payload.put("agentText", agentText);
        dispatcher.sendEvent("clarify.request", payload);
    }

    public void emitSubAgentStarted(int taskIndex, String goal) {
        dispatcher.sendEvent("subagent.started", Map.of(
                "taskIndex", taskIndex,
                "goal", goal != null ? goal : ""
        ));
    }

    public void emitSubAgentCompleted(int taskIndex, String status, long durationMs, String summary) {
        dispatcher.sendEvent("subagent.completed", Map.of(
                "taskIndex", taskIndex,
                "status", status,
                "durationMs", durationMs,
                "summary", summary != null ? summary : ""
        ));
    }

    public void emitSubAgentToolStarted(int taskIndex, String toolName, String argsPreview) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskIndex", taskIndex);
        payload.put("toolName", toolName != null ? toolName : "unknown");
        payload.put("argsPreview", argsPreview != null ? argsPreview : "");
        dispatcher.sendEvent("subagent.tool.started", payload);
    }

    public void emitSubAgentToolCompleted(int taskIndex, String toolName, long durationMs, boolean success, String error) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskIndex", taskIndex);
        payload.put("toolName", toolName != null ? toolName : "unknown");
        payload.put("durationMs", durationMs);
        payload.put("success", success);
        if (error != null && !error.isBlank()) payload.put("error", error);
        dispatcher.sendEvent("subagent.tool.completed", payload);
    }

    public void emitTodoUpdated(Object items) {
        dispatcher.sendEvent("todo.updated", Map.of("items", items));
    }

    public void emitError(String message) {
        dispatcher.sendEvent("error", Map.of("message", message));
    }

    public void emitStatusUpdate(String kind, String text) {
        dispatcher.sendEvent("status.update", Map.of("kind", kind, "text", text));
    }

    public record ToolStatusPayload(String toolName,
                                    String argsPreview,
                                    String toolArgs,
                                    long durationMs,
                                    String error,
                                    String toolCallId) {
        static ToolStatusPayload started(String toolName, String argsPreview, String toolArgs, String toolCallId) {
            return new ToolStatusPayload(toolName, argsPreview, toolArgs, 0, null, toolCallId);
        }

        static ToolStatusPayload completed(String toolName, String argsPreview, String toolArgs, long durationMs, String toolCallId) {
            return new ToolStatusPayload(toolName, argsPreview, toolArgs, durationMs, null, toolCallId);
        }

        static ToolStatusPayload failed(String toolName, String argsPreview, String toolArgs, long durationMs, String error, String toolCallId) {
            return new ToolStatusPayload(toolName, argsPreview, toolArgs, durationMs, error, toolCallId);
        }

        Map<String, Object> toEventPayload(boolean includeDuration, boolean includeError) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("toolName", toolName);
            payload.put("argsPreview", argsPreview != null ? argsPreview : "");
            if (toolArgs != null) payload.put("toolArgs", toolArgs);
            if (includeDuration) payload.put("durationMs", durationMs);
            if (includeError) payload.put("error", error != null ? error : "");
            if (toolCallId != null && !toolCallId.isBlank()) payload.put("toolCallId", toolCallId);
            return payload;
        }
    }
}
