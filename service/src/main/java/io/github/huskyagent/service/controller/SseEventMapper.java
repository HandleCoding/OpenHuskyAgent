package io.github.huskyagent.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.agent.ToolEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

public class SseEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void sendTokenEvent(SseEmitter emitter, TextEvent event) {
        send(emitter, "token", Map.of("text", event.token()));
    }

    public static void sendReasoningEvent(SseEmitter emitter, TextEvent event) {
        send(emitter, "reasoning", Map.of("text", event.token()));
    }

    public static void sendMessageEvent(SseEmitter emitter, TextEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", event.text());
        data.put("intermediate", event.intermediate());
        if (event.toolCalls() != null && !event.toolCalls().isEmpty()) {
            data.put("toolCalls", event.toolCalls().stream()
                    .map(tc -> Map.of("name", tc.name(), "arguments", tc.arguments()))
                    .toList());
        }
        send(emitter, "message", data);
    }

    public static void sendToolStartedEvent(SseEmitter emitter, ToolEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", event.toolName());
        data.put("argsPreview", event.argsPreview());
        if (event.toolArgs() != null) data.put("toolArgs", event.toolArgs());
        send(emitter, "tool_started", data);
    }

    public static void sendToolCompletedEvent(SseEmitter emitter, ToolEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", event.toolName());
        data.put("argsPreview", event.argsPreview());
        if (event.toolArgs() != null) data.put("toolArgs", event.toolArgs());
        data.put("durationMs", event.durationMs());
        send(emitter, "tool_completed", data);
    }

    public static void sendToolFailedEvent(SseEmitter emitter, ToolEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", event.toolName());
        data.put("argsPreview", event.argsPreview());
        if (event.toolArgs() != null) data.put("toolArgs", event.toolArgs());
        data.put("durationMs", event.durationMs());
        data.put("error", event.error());
        send(emitter, "tool_failed", data);
    }

    public static void sendDoneEvent(SseEmitter emitter, ChatResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", result.sessionId());
        data.put("success", result.success());
        if (result.content() != null) data.put("content", result.content());
        if (result.errorMessage() != null) data.put("errorMessage", result.errorMessage());
        if (result.tokenUsage() != null) {
            data.put("promptTokens", result.tokenUsage().promptTokens());
            data.put("completionTokens", result.tokenUsage().completionTokens());
            data.put("totalTokens", result.tokenUsage().totalTokens());
        }
        send(emitter, "done", data);
    }

    public static void sendErrorEvent(SseEmitter emitter, String message) {
        sendErrorEvent(emitter, message, ChatResult.ErrorCode.INTERNAL_ERROR);
    }

    public static void sendErrorEvent(SseEmitter emitter, String message, ChatResult.ErrorCode code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code != null ? code.name() : ChatResult.ErrorCode.INTERNAL_ERROR.name());
        data.put("message", message);
        send(emitter, "error", data);
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(MAPPER.writeValueAsString(data)));
        } catch (Exception e) {
            throw new RuntimeException("SSE send failed: " + e.getMessage(), e);
        }
    }
}