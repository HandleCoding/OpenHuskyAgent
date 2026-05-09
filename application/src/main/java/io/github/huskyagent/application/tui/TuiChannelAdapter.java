package io.github.huskyagent.application.tui;

import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.tool.todo.TodoStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class TuiChannelAdapter implements ChannelSubscriber, TokenSubscriber {

    private final ConcurrentHashMap<String, EmitterBinding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> connectionBindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, EmitterBinding>> sessionBindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EmitterBinding> activeSessionBindings = new ConcurrentHashMap<>();
    private final AtomicLong bindingSequence = new AtomicLong();
    private final TodoStore todoStore;
    private final ScheduledExecutorService todoDebounce = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingTodoRenders = new ConcurrentHashMap<>();

    private static final Set<HookEvent> SUPPORTED_EVENTS = Set.of(
            HookEvent.LLM_CALL_AFTER,
            HookEvent.TOOL_CALL_START,
            HookEvent.TOOL_CALL_AFTER,
            HookEvent.SUBAGENT_START,
            HookEvent.SUBAGENT_PROGRESS,
            HookEvent.SUBAGENT_STOP
    );

    public TuiChannelAdapter(ChannelEventBus eventBus, TodoStore todoStore) {
        this.todoStore = todoStore;
        eventBus.subscribe("tui", SUPPORTED_EVENTS, this);
        eventBus.subscribeTokens("tui", this);
    }


    public void registerEmitter(String connectionId, String sessionId, JsonRpcEventEmitter emitter) {
        if (connectionId == null || sessionId == null || emitter == null) {
            return;
        }
        String key = bindingKey(connectionId, sessionId);
        String previousKey = connectionBindings.put(connectionId, key);
        if (previousKey != null && !previousKey.equals(key)) {
            removeBinding(previousKey);
        }

        EmitterBinding binding = new EmitterBinding(connectionId, sessionId, key, emitter, bindingSequence.incrementAndGet());
        bindings.put(key, binding);
        sessionBindings.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>()).put(key, binding);
        activeSessionBindings.put(sessionId, binding);
        log.info("[TuiChannelAdapter] registered emitter: key={}, total={}", key, bindings.size());
    }

    public void unregisterEmitter(String connectionId, String sessionId) {
        if (connectionId == null || sessionId == null) {
            return;
        }
        String key = bindingKey(connectionId, sessionId);
        connectionBindings.remove(connectionId, key);
        removeBinding(key);
        log.info("[TuiChannelAdapter] unregistered emitter: key={}, total={}", key, bindings.size());
    }

    private JsonRpcEventEmitter findEmitterBySessionId(String sessionId) {
        EmitterBinding binding = activeSessionBindings.get(sessionId);
        return binding != null ? binding.emitter() : null;
    }

    private void removeBinding(String key) {
        EmitterBinding removed = bindings.remove(key);
        if (removed == null) {
            return;
        }
        ConcurrentHashMap<String, EmitterBinding> perSession = sessionBindings.get(removed.sessionId());
        if (perSession != null) {
            perSession.remove(key);
            if (perSession.isEmpty()) {
                sessionBindings.remove(removed.sessionId(), perSession);
                activeSessionBindings.remove(removed.sessionId(), removed);
                ScheduledFuture<?> pending = pendingTodoRenders.remove(removed.sessionId());
                if (pending != null) {
                    pending.cancel(false);
                }
            } else {
                activeSessionBindings.compute(removed.sessionId(), (sessionId, current) ->
                        current != null && !current.key().equals(key)
                                ? current
                                : latestBinding(perSession));
            }
        }
    }

    private EmitterBinding latestBinding(ConcurrentHashMap<String, EmitterBinding> perSession) {
        return perSession.values().stream()
                .max(Comparator.comparingLong(EmitterBinding::sequence))
                .orElse(null);
    }

    private static String bindingKey(String connectionId, String sessionId) {
        return connectionId + ":" + sessionId;
    }

    private record EmitterBinding(String connectionId,
                                  String sessionId,
                                  String key,
                                  JsonRpcEventEmitter emitter,
                                  long sequence) {
    }

    // ── ChannelSubscriber ─────────────────────────────────────────────────────

    @Override
    public void onEvent(ChannelEvent event) {
        String sessionId = event.sessionId();
        JsonRpcEventEmitter emitter = sessionId != null ? findEmitterBySessionId(sessionId) : null;
        if (emitter == null) {
            log.debug("[TuiChannelAdapter] no emitter for sessionId={}, skipping", sessionId);
            return;
        }

        Map<String, Object> data = event.data();
        switch (event.type()) {
            case LLM_CALL_AFTER -> handleLlmAfter(data, emitter);
            case TOOL_CALL_START -> handleToolStart(data, emitter);
            case TOOL_CALL_AFTER -> handleToolAfter(sessionId, data, emitter);
            case SUBAGENT_START -> handleSubAgentStart(data, emitter);
            case SUBAGENT_PROGRESS -> handleSubAgentProgress(data, emitter);
            case SUBAGENT_STOP -> handleSubAgentStop(data, emitter);
            default -> {}
        }
    }


    private void handleLlmAfter(Map<String, Object> data, JsonRpcEventEmitter emitter) {
        Object responseObj = data.get(HookDataKeys.LLM_RESPONSE);
        if (!(responseObj instanceof AssistantMessage am)) return;
        boolean hasToolCalls = am.hasToolCalls();
        emitter.emitMessageIntermediate(am.getText(), hasToolCalls);
    }

    private void handleToolStart(Map<String, Object> data, JsonRpcEventEmitter emitter) {
        String toolName = (String) data.get(HookDataKeys.TOOL_NAME);
        String argsPreview = (String) data.get(HookDataKeys.TOOL_ARGS_PREVIEW);
        String toolArgs = (String) data.get(HookDataKeys.TOOL_ARGS);
        String toolCallId = (String) data.get(HookDataKeys.TOOL_CALL_ID);
        emitter.emitToolStarted(toolName, argsPreview, toolArgs, toolCallId);
    }

    private void handleToolAfter(String sessionId, Map<String, Object> data, JsonRpcEventEmitter emitter) {
        String toolName = (String) data.get(HookDataKeys.TOOL_NAME);
        String argsPreview = (String) data.get(HookDataKeys.TOOL_ARGS_PREVIEW);
        String toolArgs = (String) data.get(HookDataKeys.TOOL_ARGS);
        String status = (String) data.get(HookDataKeys.TOOL_STATUS);
        String toolCallId = (String) data.get(HookDataKeys.TOOL_CALL_ID);
        long durationMs = data.containsKey(HookDataKeys.TOOL_DURATION_MS)
                ? ((Number) data.get(HookDataKeys.TOOL_DURATION_MS)).longValue() : 0;
        String error = (String) data.get(HookDataKeys.TOOL_ERROR);

        if ("failed".equals(status)) {
            emitter.emitToolFailed(toolName, argsPreview, toolArgs, durationMs, error, toolCallId);
        } else {
            emitter.emitToolCompleted(toolName, argsPreview, toolArgs, durationMs, toolCallId);
        }

        if ("todo".equals(toolName) && !"failed".equals(status)) {
            scheduleTodoUpdate(sessionId, emitter);
        }
    }

    private void handleSubAgentStart(Map<String, Object> data, JsonRpcEventEmitter emitter) {
        String goal = (String) data.get(HookDataKeys.SUBAGENT_GOAL);
        emitter.emitStatusUpdate("subagent", goal != null ? goal : "Sub-agent running");
    }

    private void handleSubAgentProgress(Map<String, Object> data, JsonRpcEventEmitter emitter) {
        String progressType = data.get("progressType") != null ? String.valueOf(data.get("progressType")) : null;
        if (progressType == null) return;

        int taskIndex = data.containsKey(HookDataKeys.SUBAGENT_DEPTH)
                ? ((Number) data.get(HookDataKeys.SUBAGENT_DEPTH)).intValue() : 0;

        switch (progressType) {
            case "started" -> {
                String goal = data.get(HookDataKeys.SUBAGENT_GOAL) != null
                        ? String.valueOf(data.get(HookDataKeys.SUBAGENT_GOAL)) : "";
                emitter.emitSubAgentStarted(taskIndex, goal);
            }
            case "completed" -> {
                long durationMs = data.containsKey(HookDataKeys.SUBAGENT_DURATION_MS)
                        ? ((Number) data.get(HookDataKeys.SUBAGENT_DURATION_MS)).longValue() : 0;
                String summary = data.get(HookDataKeys.SUBAGENT_SUMMARY) != null
                        ? String.valueOf(data.get(HookDataKeys.SUBAGENT_SUMMARY)) : "";
                emitter.emitSubAgentCompleted(taskIndex, "completed", durationMs, summary);
            }
            case "failed" -> {
                String error = data.get(HookDataKeys.SUBAGENT_ERROR) != null
                        ? String.valueOf(data.get(HookDataKeys.SUBAGENT_ERROR)) : "";
                emitter.emitSubAgentCompleted(taskIndex, "failed", 0, error);
            }
            case "timeout" -> emitter.emitSubAgentCompleted(taskIndex, "timeout", 0, "Execution timed out");
            case "tool_started" -> {
                String toolName = data.get(HookDataKeys.TOOL_NAME) != null
                        ? String.valueOf(data.get(HookDataKeys.TOOL_NAME)) : "unknown";
                String argsPreview = data.get(HookDataKeys.TOOL_ARGS_PREVIEW) != null
                        ? String.valueOf(data.get(HookDataKeys.TOOL_ARGS_PREVIEW)) : "";
                emitter.emitSubAgentToolStarted(taskIndex, toolName, argsPreview);
            }
            case "tool_completed" -> {
                String toolName = data.get(HookDataKeys.TOOL_NAME) != null
                        ? String.valueOf(data.get(HookDataKeys.TOOL_NAME)) : "unknown";
                long durationMs = data.containsKey(HookDataKeys.TOOL_DURATION_MS)
                        ? ((Number) data.get(HookDataKeys.TOOL_DURATION_MS)).longValue() : 0;
                String toolStatus = data.get(HookDataKeys.TOOL_STATUS) != null
                        ? String.valueOf(data.get(HookDataKeys.TOOL_STATUS)) : "completed";
                boolean success = !"failed".equals(toolStatus);
                String error = !success && data.get(HookDataKeys.TOOL_ERROR) != null
                        ? String.valueOf(data.get(HookDataKeys.TOOL_ERROR)) : null;
                emitter.emitSubAgentToolCompleted(taskIndex, toolName, durationMs, success, error);
            }
            default -> {
            }
        }
    }

    private void handleSubAgentStop(Map<String, Object> data, JsonRpcEventEmitter emitter) {
        String status = data.get(HookDataKeys.SUBAGENT_STATUS) != null
                ? String.valueOf(data.get(HookDataKeys.SUBAGENT_STATUS)) : "completed";
        long durationMs = data.containsKey(HookDataKeys.SUBAGENT_DURATION_MS)
                ? ((Number) data.get(HookDataKeys.SUBAGENT_DURATION_MS)).longValue() : 0;
        emitter.emitStatusUpdate("subagent", "Sub-agent completed: status=" + status + ", duration=" + durationMs + "ms");
    }

    private void scheduleTodoUpdate(String sessionId, JsonRpcEventEmitter emitter) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        ScheduledFuture<?> previous = pendingTodoRenders.remove(sessionId);
        if (previous != null) {
            previous.cancel(false);
        }

        ScheduledFuture<?> future = todoDebounce.schedule(() -> {
            try {
                emitter.emitTodoUpdated(todoStore.list(sessionId));
            } catch (Exception e) {
                log.debug("[TuiChannelAdapter] todo.updated send failed: sessionId={}", sessionId, e);
            } finally {
                pendingTodoRenders.remove(sessionId);
            }
        }, 200, TimeUnit.MILLISECONDS);
        pendingTodoRenders.put(sessionId, future);
    }

    // ── TokenSubscriber ────────────────────────────────────────────────────────

    @Override
    public void onToken(String sessionId, String token, boolean reasoning) {
        JsonRpcEventEmitter emitter = sessionId != null ? findEmitterBySessionId(sessionId) : null;
        if (emitter != null) {
            emitter.emitMessageDelta(token, reasoning);
        }
    }

    @PreDestroy
    public void shutdown() {
        todoDebounce.shutdownNow();
    }
}
