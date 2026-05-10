package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.RuntimeScope;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

class OpenAiStreamingRuntimeCallbacks implements RuntimeCallbacks {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final OpenAiResponseMapper responseMapper;
    private final String completionId;
    private final long created;
    private final String model;
    private final AtomicBoolean roleSent = new AtomicBoolean(false);
    private final AtomicBoolean contentSent = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private OpenAiChannelAdapter channelAdapter;
    private String registeredSessionId;

    OpenAiStreamingRuntimeCallbacks(SseEmitter emitter, ObjectMapper objectMapper,
                                    OpenAiResponseMapper responseMapper, String completionId,
                                    long created, String model) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.completionId = completionId;
        this.created = created;
        this.model = model;
    }

    @Override
    public void started(RuntimeScope scope) {
        if (scope != null && channelAdapter != null) {
            registeredSessionId = scope.getSessionId();
            channelAdapter.register(registeredSessionId, this);
        }
        sendRoleIfNeeded();
    }

    @Override
    public void text(RuntimeScope scope, TextEvent event) {
        if (event == null || event.reasoning() || !event.isTokenEvent()) {
            return;
        }
        emitContent(event.token());
    }

    @Override
    public void approval(RuntimeScope scope, ApprovalContext approval) {
        approval.approve(false, false);
    }

    @Override
    public void clarify(RuntimeScope scope, ClarifyContext clarify) {
        clarify.respond("");
    }

    @Override
    public void completed(RuntimeScope scope, ChatResult result) {
        if (!contentSent.get() && result != null) {
            emitContent(result.content());
        }
        finish();
    }

    @Override
    public void failed(RuntimeScope scope, String errorMessage) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            sendData(new OpenAiWireResponses.ErrorResponse(
                    new OpenAiWireResponses.ErrorBody(errorMessage, "server_error", null, "runtime_error")));
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        sendRoleIfNeeded();
        sendData(responseMapper.stopChunk(completionId, created, model));
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    void emitContent(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        contentSent.set(true);
        sendRoleIfNeeded();
        send(responseMapper.contentChunk(completionId, created, model, content));
    }

    void emitToken(String token, boolean reasoning) {
        if (!reasoning) {
            emitContent(token);
        }
    }

    void registerWith(OpenAiChannelAdapter channelAdapter) {
        this.channelAdapter = channelAdapter;
    }

    String registeredSessionId() {
        return registeredSessionId;
    }

    private void sendRoleIfNeeded() {
        if (roleSent.compareAndSet(false, true)) {
            sendData(responseMapper.roleChunk(completionId, created, model));
        }
    }

    private void send(Object value) {
        if (finished.get()) {
            return;
        }
        sendData(value);
    }

    private void sendData(Object value) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(value)));
        } catch (IOException e) {
            finished.set(true);
            emitter.completeWithError(e);
            throw new IllegalStateException("Failed to send OpenAI-compatible SSE event", e);
        }
    }
}
