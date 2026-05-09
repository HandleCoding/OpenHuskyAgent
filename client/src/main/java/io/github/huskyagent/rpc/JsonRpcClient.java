package io.github.huskyagent.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * WebSocket JSON-RPC 客户端。
 *
 * <p>用于 TUI 客户端连接到 Husky Agent 服务的 /api/tui WebSocket 端点。</p>
 * <p>内部使用 Java-WebSocket，不需要 Spring 容器。</p>
 */
@Slf4j
public class JsonRpcClient {

    /** 请求超时检测用的共享 scheduler（不再每请求创建） */
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rpc-timeout");
        t.setDaemon(true);
        return t;
    });

    private final String serverUrl;
    private final AtomicLong idCounter = new AtomicLong(0);

    /** 待响应的请求：id → PendingRequest */
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    /** 事件处理器：eventType → handler */
    private final ConcurrentHashMap<String, Consumer<JsonNode>> eventHandlers = new ConcurrentHashMap<>();

    private volatile InternalClient internalClient;
    private volatile boolean connected = false;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private volatile boolean intentionalClose = false;

    public JsonRpcClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    // ── 事件注册 ────────────────────────────────────────────────────────────

    public void onEvent(String eventType, Consumer<JsonNode> handler) {
        eventHandlers.put(eventType, handler);
    }

    // ── 请求方法 ────────────────────────────────────────────────────────────

    /** 发送 JSON-RPC 请求并等待响应 */
    public CompletableFuture<JsonNode> request(String method, Map<String, Object> params) {
        String id = "tui-" + idCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();

        pending.put(id, new PendingRequest(id, method, future));

        // 共享 scheduler 统一管理超时，不再每请求创建新 ScheduledExecutorService
        timeoutScheduler.schedule(() -> {
            PendingRequest removed = pending.remove(id);
            if (removed != null && !removed.future().isDone()) {
                removed.future().completeExceptionally(new TimeoutException("Request timeout: " + method));
            }
        }, 2, TimeUnit.MINUTES);

        ObjectNode frame = JsonRpcProtocol.request(id, method, params);
        sendFrame(frame);

        return future;
    }

    /** 发送 JSON-RPC 通知（无响应） */
    public void notify(String method, Map<String, Object> params) {
        ObjectNode frame = JsonRpcProtocol.notification(method, params);
        sendFrame(frame);
    }

    // ── 连接管理 ────────────────────────────────────────────────────────────

    /** 连接 WebSocket 服务器，阻塞直到收到 ready 事件或超时 */
    public void connect() throws Exception {
        log.info("连接 TUI WebSocket: {}", serverUrl);

        internalClient = new InternalClient(URI.create(serverUrl));
        boolean ok = internalClient.connectBlocking(15, TimeUnit.SECONDS);
        if (!ok) {
            throw new RuntimeException("WebSocket 连接失败: " + serverUrl);
        }

        // 等待 ready 事件
        try {
            readyFuture.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("等待 ready 事件超时，继续执行");
        }
    }

    public boolean isConnected() {
        return connected && internalClient != null && internalClient.isOpen();
    }

    public void disconnect() {
        intentionalClose = true;
        connected = false;
        try {
            if (internalClient != null && internalClient.isOpen()) {
                internalClient.close(1000, "normal");
            }
        } catch (Exception e) {
            log.debug("关闭 WebSocket 异常", e);
        }
        pending.values().forEach(p -> p.future().cancel(true));
        pending.clear();
        timeoutScheduler.shutdownNow();
    }

    /** 同步重连一次，最多等待 15s。供 sendFrame 在检测到断开时调用。 */
    private boolean tryReconnectNow() {
        if (intentionalClose) return false;
        log.info("WebSocket 未连接，尝试重连...");
        try {
            InternalClient newClient = new InternalClient(URI.create(serverUrl));
            boolean ok = newClient.connectBlocking(15, TimeUnit.SECONDS);
            if (ok) {
                internalClient = newClient;
                connected = true;
                log.info("WebSocket 重连成功");
                return true;
            }
        } catch (Exception e) {
            log.warn("WebSocket 重连失败: {}", e.getMessage());
        }
        return false;
    }

    // ── 内部 WebSocket client ────────────────────────────────────────────────

    private class InternalClient extends WebSocketClient {

        InternalClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("TUI WebSocket 连接成功");
            connected = true;
        }

        @Override
        public void onMessage(String message) {
            handleIncomingMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            connected = false;
            log.info("TUI WebSocket 断开: code={}, reason={}, remote={}", code, reason, remote);
        }

        @Override
        public void onError(Exception exception) {
            log.debug("TUI WebSocket 传输错误: {}", exception.getMessage());
            connected = false;
        }
    }

    // ── 消息处理 ────────────────────────────────────────────────────────────

    private void handleIncomingMessage(String raw) {
        JsonNode frame = JsonRpcProtocol.deserialize(raw);
        if (frame == null) return;

        if (JsonRpcProtocol.isResponse(frame)) {
            String id = JsonRpcProtocol.getId(frame);
            if (id != null) {
                PendingRequest req = pending.remove(id);
                if (req != null) {
                    if (frame.has("error")) {
                        req.future().completeExceptionally(
                                new RuntimeException(frame.get("error").get("message").asText("Unknown error")));
                    } else {
                        req.future().complete(frame.get("result"));
                    }
                }
            }
        } else if (frame.has("method")) {
            String method = frame.get("method").asText();
            JsonNode params = frame.get("params");

            if ("event".equals(method) && params != null && params.has("type")) {
                String eventType = params.get("type").asText();

                // ready 事件特殊处理
                if ("ready".equals(eventType)) {
                    readyFuture.complete(null);
                }

                Consumer<JsonNode> handler = eventHandlers.get(eventType);
                if (handler != null) {
                    handler.accept(params.get("payload"));
                }
            }
        }
    }

    private void sendFrame(ObjectNode frame) {
        String json = JsonRpcProtocol.serialize(frame);
        try {
            if (!isConnected()) {
                tryReconnectNow();
            }
            if (internalClient != null && internalClient.isOpen()) {
                internalClient.send(json);
            } else {
                log.warn("WebSocket 未连接，发送失败: {}", json.substring(0, Math.min(100, json.length())));
            }
        } catch (Exception e) {
            log.error("发送 JSON-RPC 帧失败", e);
        }
    }

    // ── 内嵌类型 ────────────────────────────────────────────────────────────

    private record PendingRequest(
            String id,
            String method,
            CompletableFuture<JsonNode> future
    ) {}
}