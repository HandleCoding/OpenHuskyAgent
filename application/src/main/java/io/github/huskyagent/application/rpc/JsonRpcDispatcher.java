package io.github.huskyagent.application.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Dispatches JSON-RPC requests to registered handlers and runs long-running
 * handlers on a dedicated worker pool.
 */
@Slf4j
public class JsonRpcDispatcher {

    private final Map<String, HandlerEntry> handlers = new ConcurrentHashMap<>();
    private final ExecutorService longExecutor;
    private final AtomicLong idCounter = new AtomicLong(0);
    private final AtomicLong longThreadCounter = new AtomicLong(0);

    /** Sends serialized JSON-RPC frames back to the active transport. */
    private final Sender sender;

    @FunctionalInterface
    public interface Sender {
        void send(String message);
    }

    @FunctionalInterface
    public interface MethodHandler {
        ObjectNode handle(String id, JsonNode params);
    }

    private record HandlerEntry(MethodHandler handler, boolean longRunning) {}

    public JsonRpcDispatcher(Sender sender) {
        this.sender = sender;
        int cpuCores = Runtime.getRuntime().availableProcessors();
        this.longExecutor = new ThreadPoolExecutor(
                cpuCores, cpuCores * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "jsonrpc-long-handler-" + longThreadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }


    public void register(String method, MethodHandler handler) {
        handlers.put(method, new HandlerEntry(handler, false));
    }

    public void registerLong(String method, MethodHandler handler) {
        handlers.put(method, new HandlerEntry(handler, true));
    }


    public void dispatch(JsonNode frame) {
        String id = JsonRpcProtocol.getId(frame);

        if (JsonRpcProtocol.isResponse(frame)) return;

        String method = JsonRpcProtocol.getMethod(frame);
        if (method == null) {
            sendError(id, JsonRpcProtocol.INVALID_REQUEST, "Missing method");
            return;
        }

        HandlerEntry entry = handlers.get(method);
        if (entry == null) {
            sendError(id, JsonRpcProtocol.METHOD_NOT_FOUND, "Method not found: " + method);
            return;
        }

        JsonNode params = JsonRpcProtocol.getParams(frame);

        if (entry.longRunning) {
            longExecutor.submit(() -> {
                try {
                    ObjectNode result = entry.handler.handle(id, params);
                    if (result != null) {
                        send(result);
                    }
                } catch (Exception e) {
                    log.error("LONG handler failed: method={}", method, e);
                    sendError(id, JsonRpcProtocol.INTERNAL_ERROR, e.getMessage());
                }
            });
        } else {
            try {
                ObjectNode result = entry.handler.handle(id, params);
                if (result != null) {
                    send(result);
                }
            } catch (Exception e) {
                log.error("handler failed: method={}", method, e);
                sendError(id, JsonRpcProtocol.INTERNAL_ERROR, e.getMessage());
            }
        }
    }


    public void sendNotification(String method, Object params) {
        ObjectNode notif = JsonRpcProtocol.notification(method, params);
        send(notif);
    }

    public void sendEvent(String eventType, Object payload) {
        sendNotification("event", Map.of("type", eventType, "payload", payload));
    }

    public String nextId() {
        return "req-" + idCounter.incrementAndGet();
    }


    private void send(ObjectNode node) {
        sender.send(JsonRpcProtocol.serialize(node));
    }

    private void sendError(String id, int code, String message) {
        if (id == null) return;
        send(JsonRpcProtocol.error(id, code, message));
    }

    public void shutdown() {
        longExecutor.shutdownNow();
    }
}
