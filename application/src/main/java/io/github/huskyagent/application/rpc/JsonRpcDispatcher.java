package io.github.huskyagent.application.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * JSON-RPC 方法注册 + 请求分发。
 *
 * <p>服务端使用：注册方法处理器，收到请求后分发到对应 handler。</p>
 * <p>支持 LONG handler（在线程池中异步执行，适合 prompt.submit 等耗时操作）。</p>
 */
@Slf4j
public class JsonRpcDispatcher {

    private final Map<String, HandlerEntry> handlers = new ConcurrentHashMap<>();
    private final ExecutorService longExecutor;
    private final AtomicLong idCounter = new AtomicLong(0);
    private final AtomicLong longThreadCounter = new AtomicLong(0);

    /** 发送消息的回调（WebSocket session write / stdout write 等） */
    private final Sender sender;

    @FunctionalInterface
    public interface Sender {
        void send(String message);
    }

    /**
     * 方法处理器。
     * 接收 (id, params)，返回 ObjectNode 响应或 null（通知/异步自行回复）。
     */
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

    // ── 注册方法 ─────────────────────────────────────────────────────────────

    /** 注册普通方法（同步执行） */
    public void register(String method, MethodHandler handler) {
        handlers.put(method, new HandlerEntry(handler, false));
    }

    /** 注册长时间运行方法（线程池异步执行） */
    public void registerLong(String method, MethodHandler handler) {
        handlers.put(method, new HandlerEntry(handler, true));
    }

    // ── 分发请求 ─────────────────────────────────────────────────────────────

    /** 处理一个 JSON-RPC 帧 */
    public void dispatch(JsonNode frame) {
        String id = JsonRpcProtocol.getId(frame);

        // 响应帧（客户端收到服务端响应），跳过
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
                    log.error("LONG handler 异常: method={}", method, e);
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
                log.error("handler 异常: method={}", method, e);
                sendError(id, JsonRpcProtocol.INTERNAL_ERROR, e.getMessage());
            }
        }
    }

    // ── 发送消息 ─────────────────────────────────────────────────────────────

    /** 发送通知（无 id，不需要响应） */
    public void sendNotification(String method, Object params) {
        ObjectNode notif = JsonRpcProtocol.notification(method, params);
        send(notif);
    }

    /** 发送事件通知（统一用 event method + type 字段） */
    public void sendEvent(String eventType, Object payload) {
        sendNotification("event", Map.of("type", eventType, "payload", payload));
    }

    /** 生成唯一请求 id */
    public String nextId() {
        return "req-" + idCounter.incrementAndGet();
    }

    // ── 内部方法 ─────────────────────────────────────────────────────────────

    private void send(ObjectNode node) {
        sender.send(JsonRpcProtocol.serialize(node));
    }

    private void sendError(String id, int code, String message) {
        if (id == null) return; // 通知无法回复错误
        send(JsonRpcProtocol.error(id, code, message));
    }

    /** 关闭线程池 */
    public void shutdown() {
        longExecutor.shutdownNow();
    }
}
