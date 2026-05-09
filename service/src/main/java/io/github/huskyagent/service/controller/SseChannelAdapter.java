package io.github.huskyagent.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 渠道适配器 — 统一消费 Hook 事件，推送到 Chatbot SSE 客户端。
 *
 * <p>替代 SseHookRegistry + CompositeHookRegistry + ToolEventCallbacks。</p>
 */
@Slf4j
@Component
public class SseChannelAdapter implements ChannelSubscriber, TokenSubscriber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    private static final Set<HookEvent> SUPPORTED_EVENTS = Set.of(
            HookEvent.TOOL_CALL_START,
            HookEvent.TOOL_CALL_AFTER
    );

    public SseChannelAdapter(ChannelEventBus eventBus) {
        eventBus.subscribe("chatbot", SUPPORTED_EVENTS, this);
        eventBus.subscribeTokens("chatbot", this);
    }

    // ── Emitter 生命周期 ─────────────────────────────────────────────────────

    public void registerEmitter(String sessionId, SseEmitter emitter) {
        activeEmitters.put(sessionId, emitter);
    }

    public void unregisterEmitter(String sessionId) {
        activeEmitters.remove(sessionId);
    }

    // ── ChannelSubscriber ─────────────────────────────────────────────────────

    @Override
    public void onEvent(ChannelEvent event) {
        String sessionId = event.sessionId();
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter == null) return;

        Map<String, Object> data = event.data();
        String toolName = String.valueOf(data.getOrDefault(HookDataKeys.TOOL_NAME, "unknown"));
        String argsPreview = String.valueOf(data.getOrDefault(HookDataKeys.TOOL_ARGS_PREVIEW, ""));
        String toolArgs = data.get(HookDataKeys.TOOL_ARGS) != null
                ? String.valueOf(data.get(HookDataKeys.TOOL_ARGS)) : null;
        long durationMs = data.containsKey(HookDataKeys.TOOL_DURATION_MS)
                ? ((Number) data.get(HookDataKeys.TOOL_DURATION_MS)).longValue() : 0;
        String error = data.get(HookDataKeys.TOOL_ERROR) != null
                ? String.valueOf(data.get(HookDataKeys.TOOL_ERROR)) : null;

        try {
            if (event.type() == HookEvent.TOOL_CALL_START) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolName", toolName);
                payload.put("argsPreview", argsPreview);
                if (toolArgs != null) payload.put("toolArgs", toolArgs);
                send(emitter, "tool_started", payload);
            } else if (event.type() == HookEvent.TOOL_CALL_AFTER) {
                if (error != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("toolName", toolName);
                    payload.put("argsPreview", argsPreview);
                    if (toolArgs != null) payload.put("toolArgs", toolArgs);
                    payload.put("durationMs", durationMs);
                    payload.put("error", error);
                    send(emitter, "tool_failed", payload);
                } else {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("toolName", toolName);
                    payload.put("argsPreview", argsPreview);
                    if (toolArgs != null) payload.put("toolArgs", toolArgs);
                    payload.put("durationMs", durationMs);
                    send(emitter, "tool_completed", payload);
                }
            }
        } catch (Exception e) {
            log.error("[SseChannelAdapter] SSE push failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(MAPPER.writeValueAsString(data)));
        } catch (Exception e) {
            throw new RuntimeException("SSE send failed: " + e.getMessage(), e);
        }
    }

    // ── TokenSubscriber ────────────────────────────────────────────────────────

    @Override
    public void onToken(String sessionId, String token, boolean reasoning) {
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter == null) return;
        try {
            String eventName = reasoning ? "reasoning" : "token";
            send(emitter, eventName, Map.of("text", token));
        } catch (Exception e) {
            log.error("[SseChannelAdapter] SSE token push failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
