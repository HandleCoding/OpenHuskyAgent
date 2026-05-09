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

@Slf4j
@Component
public class TuiWebSocketHandler extends TextWebSocketHandler {

    private final TuiChannelAdapter channelAdapter;
    private final TuiSessionServiceFactory tuiSessionServiceFactory;

    private final ConcurrentHashMap<String, ConnectionContext> connections = new ConcurrentHashMap<>();

    public TuiWebSocketHandler(TuiChannelAdapter channelAdapter, TuiSessionServiceFactory tuiSessionServiceFactory) {
        this.channelAdapter = channelAdapter;
        this.tuiSessionServiceFactory = tuiSessionServiceFactory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String connectionId = session.getId();
        log.info("WS connected: connectionId={}", connectionId);

        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(message -> {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.error("WebSocket send failed: connectionId={}", connectionId, e);
            }
        });

        TuiSessionService sessionService = tuiSessionServiceFactory.create(connectionId);
        JsonRpcMethods methods = new JsonRpcMethods(sessionService, dispatcher,
                (sessionId, emitter) -> registerEmitter(connectionId, sessionId, emitter));
        methods.registerAll();

        ConnectionContext ctx = new ConnectionContext(dispatcher, methods);
        connections.put(connectionId, ctx);

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
            log.warn("Invalid JSON-RPC frame: {}", payload.substring(0, Math.min(100, payload.length())));
            return;
        }

        ctx.dispatcher().dispatch(frame);

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
        log.info("WS disconnected: connectionId={}, status={}", connectionId, status);
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
        log.error("WS transport error: connectionId={}", session.getId(), exception);
    }

    private record ConnectionContext(JsonRpcDispatcher dispatcher, JsonRpcMethods methods) {}
}