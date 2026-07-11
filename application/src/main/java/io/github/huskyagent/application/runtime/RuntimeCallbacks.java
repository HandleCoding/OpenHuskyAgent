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

    /**
     * Failure without a typed code. Prefer {@link #failed(RuntimeScope, String, ChatResult.ErrorCode)}
     * when the error code is known (e.g. RATE_LIMITED).
     */
    default void failed(RuntimeScope scope, String errorMessage) {
        failed(scope, errorMessage, null);
    }

    /**
     * Failure with optional {@link ChatResult.ErrorCode} so transports can map RATE_LIMITED, AUTH, etc.
     * Default is a no-op; overrides should not call the two-arg overload unless they ignore the code.
     */
    default void failed(RuntimeScope scope, String errorMessage, ChatResult.ErrorCode errorCode) {
    }
}
