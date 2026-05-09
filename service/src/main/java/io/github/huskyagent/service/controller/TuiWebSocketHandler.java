package io.github.huskyagent.service.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import io.github.huskyagent.application.rpc.JsonRpcProtocol;
import io.github.huskyagent.application.tui.JsonRpcEventEmitter;
import io.github.huskyagent.application.tui.JsonRpcMethods;
import io.github.huskyagent.application.tui.TuiChannelAdapter;
import io.github.huskyagent.application.tui.TuiSessionService;
import io.github.huskyagent.application.tui.TuiSessionServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS 连接处理器 — 通用传输层，TUI 只是一种 channel adapter。
 *
 * <p>每个 WebSocket 连接创建一个 {@link JsonRpcDispatcher} 和 {@link JsonRpcMethods}，
 * 实现 per-connection 隔离的 JSON-RPC 通信。</p>
 */
@Slf4j
@Component
public class TuiWebSocketHandler extends TextWebSocketHandler {

    private final TuiChannelAdapter channelAdapter;
    private final TuiSessionServiceFactory tuiSessionServiceFactory;

    /** 活跃连接：wsSessionId → ConnectionContext */
    private final ConcurrentHashMap<String, ConnectionContext> connections = new ConcurrentHashMap<>();

    public TuiWebSocketHandler(TuiChannelAdapter channelAdapter, TuiSessionServiceFactory tuiSessionServiceFactory) {
        this.channelAdapter = channelAdapter;
        this.tuiSessionServiceFactory = tuiSessionServiceFactory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String connectionId = session.getId();
        log.info("WS 连接: connectionId={}", connectionId);

        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(message -> {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.error("WebSocket 发送失败: connectionId={}", connectionId, e);
            }
        });

        TuiSessionService sessionService = tuiSessionServiceFactory.create(connectionId);
        JsonRpcMethods methods = new JsonRpcMethods(sessionService, dispatcher,
                (sessionId, emitter) -> registerEmitter(connectionId, sessionId, emitter));
        methods.registerAll();

        ConnectionContext ctx = new ConnectionContext(dispatcher, methods);
        connections.put(connectionId, ctx);

        // 发送 ready 通知（包含 connectionId，供客户端标识）
        dispatcher.sendEvent("ready", Map.of(
                "version", "0.1.0",
                "mode", "tui",
                "connectionId", connectionId
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String connectionId = session.getId();
        ConnectionContext ctx = connections.get(connectionId);
        if (ctx == null) return;

        String payload = message.getPayload();
        JsonNode frame = JsonRpcProtocol.deserialize(payload);
        if (frame == null) {
            log.warn("无效 JSON-RPC 帧: {}", payload.substring(0, Math.min(100, payload.length())));
            return;
        }

        ctx.dispatcher().dispatch(frame);

        // session.create 后，将 emitter 注册到 TuiChannelAdapter
        String agentSessionId = ctx.methods().getSessionService().getCurrentSessionId();
        if (agentSessionId != null) {
            registerEmitter(connectionId, agentSessionId, ctx.methods().getEmitter());
        }
    }

    private void registerEmitter(String connectionId, String sessionId, JsonRpcEventEmitter emitter) {
        if (emitter != null) {
            channelAdapter.registerEmitter(connectionId, sessionId, emitter);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String connectionId = session.getId();
        log.info("WS 断开: connectionId={}, status={}", connectionId, status);
        ConnectionContext ctx = connections.remove(connectionId);
        if (ctx != null) {
            String agentSessionId = ctx.methods().getSessionService().getCurrentSessionId();
            if (agentSessionId != null) {
                channelAdapter.unregisterEmitter(connectionId, agentSessionId);
            }
            ctx.methods().getSessionService().close();
            ctx.dispatcher().shutdown();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WS 传输错误: connectionId={}", session.getId(), exception);
    }

    private record ConnectionContext(JsonRpcDispatcher dispatcher, JsonRpcMethods methods) {}
}