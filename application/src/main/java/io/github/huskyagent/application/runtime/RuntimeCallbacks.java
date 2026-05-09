package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.session.RuntimeScope;

public interface RuntimeCallbacks {

    RuntimeCallbacks NOOP = new RuntimeCallbacks() {
    };

    default void started(RuntimeScope scope) {
    }

    default void text(RuntimeScope scope, TextEvent event) {
    }

    default void approval(RuntimeScope scope, ApprovalContext approval) {
        approval.approve(false, false);
    }

    default void clarify(RuntimeScope scope, ClarifyContext clarify) {
        clarify.respond("");
    }

    default void completed(RuntimeScope scope, ChatResult result) {
    }

    default void failed(RuntimeScope scope, String errorMessage) {
    }
}
