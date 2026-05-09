package io.github.huskyagent.application.tui;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.RuntimeScope;

class TuiRuntimeCallbacks implements RuntimeCallbacks {

    private final TuiSessionService sessionService;
    private final JsonRpcEventEmitter emitter;

    TuiRuntimeCallbacks(TuiSessionService sessionService, JsonRpcEventEmitter emitter) {
        this.sessionService = sessionService;
        this.emitter = emitter;
    }

    @Override
    public void text(RuntimeScope scope, TextEvent event) {
        if (event.isTokenEvent()) {
            emitter.emitMessageDelta(event.token(), event.reasoning());
        }
    }

    @Override
    public void approval(RuntimeScope scope, ApprovalContext approval) {
        sessionService.handleApprovalRequest(approval, emitter);
    }

    @Override
    public void clarify(RuntimeScope scope, ClarifyContext clarify) {
        sessionService.handleClarifyRequest(clarify, emitter);
    }

    @Override
    public void completed(RuntimeScope scope, ChatResult result) {
    }

    @Override
    public void failed(RuntimeScope scope, String errorMessage) {
    }
}
