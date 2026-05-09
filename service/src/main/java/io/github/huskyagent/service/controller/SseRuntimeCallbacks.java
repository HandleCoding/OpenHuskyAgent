package io.github.huskyagent.service.controller;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.RuntimeScope;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseRuntimeCallbacks implements RuntimeCallbacks {

    private final SseEmitter emitter;
    private final SseChannelAdapter sseChannelAdapter;
    private String registeredSessionId;

    SseRuntimeCallbacks(SseEmitter emitter, SseChannelAdapter sseChannelAdapter) {
        this.emitter = emitter;
        this.sseChannelAdapter = sseChannelAdapter;
    }

    @Override
    public void started(RuntimeScope scope) {
        registeredSessionId = scope.getSessionId();
        sseChannelAdapter.registerEmitter(registeredSessionId, emitter);
    }

    @Override
    public void text(RuntimeScope scope, TextEvent event) {
        if (event.isTokenEvent()) {
            if (event.reasoning()) {
                SseEventMapper.sendReasoningEvent(emitter, event);
            } else {
                SseEventMapper.sendTokenEvent(emitter, event);
            }
        } else {
            SseEventMapper.sendMessageEvent(emitter, event);
        }
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
        SseEventMapper.sendDoneEvent(emitter, result);
    }

    @Override
    public void failed(RuntimeScope scope, String errorMessage) {
        SseEventMapper.sendErrorEvent(emitter, errorMessage);
    }

    void unregister() {
        if (registeredSessionId != null) {
            sseChannelAdapter.unregisterEmitter(registeredSessionId);
        }
    }
}
