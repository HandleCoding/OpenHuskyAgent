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

@Slf4j
public class JsonRpcClient {

    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rpc-timeout");
        t.setDaemon(true);
        return t;
    });

    private final String serverUrl;
    private final AtomicLong idCounter = new AtomicLong(0);

    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Consumer<JsonNode>> eventHandlers = new ConcurrentHashMap<>();

    private volatile InternalClient internalClient;
    private volatile boolean connected = false;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private volatile boolean intentionalClose = false;

    public JsonRpcClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }


    public void onEvent(String eventType, Consumer<JsonNode> handler) {
        eventHandlers.put(eventType, handler);
    }


    public CompletableFuture<JsonNode> request(String method, Map<String, Object> params) {
        String id = "tui-" + idCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();

        pending.put(id, new PendingRequest(id, method, future));

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

    public void notify(String method, Map<String, Object> params) {
        ObjectNode frame = JsonRpcProtocol.notification(method, params);
        sendFrame(frame);
    }


    public void connect() throws Exception {
        log.info("Connecting TUI WebSocket: {}", serverUrl);

        internalClient = new InternalClient(URI.create(serverUrl));
        boolean ok = internalClient.connectBlocking(15, TimeUnit.SECONDS);
        if (!ok) {
            throw new RuntimeException("WebSocket connection failed: " + serverUrl);
        }

        try {
            readyFuture.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Timed out waiting for ready event; continuing");
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
            log.debug("Exception while closing WebSocket", e);
        }
        pending.values().forEach(p -> p.future().cancel(true));
        pending.clear();
        timeoutScheduler.shutdownNow();
    }

    private boolean tryReconnectNow() {
        if (intentionalClose) return false;
        log.info("WebSocket is not connected; trying to reconnect...");
        try {
            InternalClient newClient = new InternalClient(URI.create(serverUrl));
            boolean ok = newClient.connectBlocking(15, TimeUnit.SECONDS);
            if (ok) {
                internalClient = newClient;
                connected = true;
                log.info("WebSocket reconnected");
                return true;
            }
        } catch (Exception e) {
            log.warn("WebSocket reconnect failed: {}", e.getMessage());
        }
        return false;
    }


    private class InternalClient extends WebSocketClient {

        InternalClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("TUI WebSocket connected");
            connected = true;
        }

        @Override
        public void onMessage(String message) {
            handleIncomingMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            connected = false;
            log.info("TUI WebSocket disconnected: code={}, reason={}, remote={}", code, reason, remote);
        }

        @Override
        public void onError(Exception exception) {
            log.debug("TUI WebSocket transport error: {}", exception.getMessage());
            connected = false;
        }
    }


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
                log.warn("WebSocket is not connected; send failed: {}", json.substring(0, Math.min(100, json.length())));
            }
        } catch (Exception e) {
            log.error("Failed to send JSON-RPC frame", e);
        }
    }


    private record PendingRequest(
            String id,
            String method,
            CompletableFuture<JsonNode> future
    ) {}
}