package io.github.huskyagent.application.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import io.github.huskyagent.application.rpc.JsonRpcProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
public class JsonRpcMethods {

    private final TuiSessionService sessionService;
    private final JsonRpcEventEmitter emitter;
    private final JsonRpcDispatcher dispatcher;
    private final java.util.function.Consumer<String> sessionReadyHandler;

    public JsonRpcMethods(TuiSessionService sessionService, JsonRpcDispatcher dispatcher) {
        this(sessionService, dispatcher, null);
    }

    public JsonRpcMethods(TuiSessionService sessionService, JsonRpcDispatcher dispatcher,
                          java.util.function.BiConsumer<String, JsonRpcEventEmitter> sessionReadyHandler) {
        this.sessionService = sessionService;
        this.dispatcher = dispatcher;
        this.emitter = new JsonRpcEventEmitter(dispatcher);
        this.sessionReadyHandler = sessionReadyHandler != null
                ? sessionId -> sessionReadyHandler.accept(sessionId, emitter)
                : null;
    }

    public void registerAll() {
        dispatcher.register("session.create", this::sessionCreate);
        dispatcher.register("session.list", this::sessionList);
        dispatcher.register("session.switch", this::sessionSwitch);
        dispatcher.register("session.user-messages", this::sessionUserMessages);
        dispatcher.register("session.rewind", this::sessionRewind);
        dispatcher.register("session.info", this::sessionInfo);
        dispatcher.registerLong("prompt.submit", this::promptSubmit);
        dispatcher.register("approval.respond", this::approvalRespond);
        dispatcher.register("clarify.respond", this::clarifyRespond);
        dispatcher.register("session.interrupt", this::sessionInterrupt);
        dispatcher.register("session.cd", this::sessionCd);
        dispatcher.register("session.pwd", this::sessionPwd);
        dispatcher.register("session.status", this::sessionStatus);
    }

    public TuiSessionService getSessionService() {
        return sessionService;
    }

    public JsonRpcEventEmitter getEmitter() {
        return emitter;
    }

    public ChatResult submitPrompt(String text) {
        return sessionService.submitPrompt(text, emitter);
    }

    public ChatResult submitPrompt(String text, java.util.function.Consumer<String> sessionReadyHandler) {
        return sessionService.submitPrompt(text, emitter, sessionReadyHandler);
    }


    private ObjectNode sessionCreate(String id, JsonNode params) {
        String sessionId = sessionService.createSession();
        return JsonRpcProtocol.response(id, Map.of("sessionId", sessionId));
    }

    private ObjectNode sessionList(String id, JsonNode params) {
        List<?> sessions = sessionService.listSessions();
        return JsonRpcProtocol.response(id, Map.of("sessions", sessions));
    }

    private ObjectNode sessionSwitch(String id, JsonNode params) {
        String sessionId = params.has("sessionId") ? params.get("sessionId").asText() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing sessionId");
        }
        try {
            String switched = sessionService.switchSession(sessionId);
            return JsonRpcProtocol.response(id, Map.of("sessionId", switched));
        } catch (IllegalArgumentException e) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.SESSION_NOT_FOUND, e.getMessage());
        }
    }

    private ObjectNode sessionInfo(String id, JsonNode params) {
        String sid = sessionService.getCurrentSessionId();
        if (sid == null) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.SESSION_NOT_FOUND, "No active session");
        }
        int msgCount = sessionService.countMessages(sid);
        return JsonRpcProtocol.response(id, Map.of(
                "sessionId", sid,
                "messageCount", msgCount,
                "workingDirectory", sessionService.getWorkingDirectory().toString()
        ));
    }

    private ObjectNode promptSubmit(String id, JsonNode params) {
        String text = params.has("text") ? params.get("text").asText() : "";
        String sessionId = params.has("sessionId") ? params.get("sessionId").asText() : null;

        if (text.isBlank()) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing text");
        }

        if (sessionId != null && !sessionId.equals(sessionService.getCurrentSessionId())) {
            try {
                sessionService.switchSession(sessionId);
            } catch (IllegalArgumentException e) {
                return JsonRpcProtocol.error(id, JsonRpcProtocol.SESSION_NOT_FOUND, e.getMessage());
            }
        }

        ChatResult result = submitPrompt(text, sessionReadyHandler);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("success", result.success());
        response.put("sessionId", result.sessionId());
        response.put("streamed", result.streamed());
        if (!result.success()) {
            response.put("error", result.errorMessage());
        }
        return JsonRpcProtocol.response(id, response);
    }

    private ObjectNode approvalRespond(String id, JsonNode params) {
        String requestId = params.has("requestId") ? params.get("requestId").asText() : null;
        String choice = params.has("choice") ? params.get("choice").asText() : "deny";
        boolean all = params.has("all") && params.get("all").asBoolean();

        if (requestId == null) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing requestId");
        }

        boolean resolved = sessionService.respondApproval(requestId, choice, all);
        return JsonRpcProtocol.response(id, Map.of("resolved", resolved));
    }

    private ObjectNode clarifyRespond(String id, JsonNode params) {
        String requestId = params.has("requestId") ? params.get("requestId").asText() : null;
        String answer = params.has("answer") ? params.get("answer").asText() : "";

        if (requestId == null) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing requestId");
        }

        boolean resolved = sessionService.respondClarify(requestId, answer);
        return JsonRpcProtocol.response(id, Map.of("resolved", resolved));
    }

    private ObjectNode sessionInterrupt(String id, JsonNode params) {
        TuiSessionService.InterruptResult result = sessionService.interruptCurrentRun(emitter, "user_stop");
        return JsonRpcProtocol.response(id, Map.of(
                "ok", true,
                "interrupted", result.interrupted(),
                "sessionId", result.sessionId() != null ? result.sessionId() : ""
        ));
    }

    private ObjectNode sessionCd(String id, JsonNode params) {
        String dir = params.has("path") ? params.get("path").asText() : "";
        if (dir.isBlank()) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing path");
        }
        try {
            Path newPath = sessionService.changeDirectory(dir);
            return JsonRpcProtocol.response(id, Map.of("path", newPath.toString()));
        } catch (IllegalArgumentException e) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, e.getMessage());
        }
    }

    private ObjectNode sessionPwd(String id, JsonNode params) {
        return JsonRpcProtocol.response(id, Map.of(
                "path", sessionService.getWorkingDirectory().toString()
        ));
    }

    private ObjectNode sessionUserMessages(String id, JsonNode params) {
        List<?> messages = sessionService.listUserMessages();
        return JsonRpcProtocol.response(id, Map.of("messages", messages));
    }

    private ObjectNode sessionRewind(String id, JsonNode params) {
        if (!params.has("messageId")) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, "Missing messageId");
        }
        long messageId = params.get("messageId").asLong();
        try {
            sessionService.rewindTo(messageId);
            return JsonRpcProtocol.response(id, Map.of("ok", true, "rewindTo", messageId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return JsonRpcProtocol.error(id, JsonRpcProtocol.INVALID_PARAMS, e.getMessage());
        }
    }

    private ObjectNode sessionStatus(String id, JsonNode params) {
        var status = sessionService.getContextStatus(sessionService.getCurrentSessionId());
        if (status == null) {
            return JsonRpcProtocol.response(id, Map.of("status", "unknown"));
        }
        return JsonRpcProtocol.response(id, Map.of(
                "contextLength", status.contextLength(),
                "thresholdTokens", status.thresholdTokens(),
                "usagePercent", status.usagePercent(),
                "status", status.usagePercent() > 75.0 ? "needs-compression" : "ok"
        ));
    }
}
