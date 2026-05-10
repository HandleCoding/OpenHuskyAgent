package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.session.RuntimeScope;

public interface AgentRuntimeExecutor {
    ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks);

    default ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks,
                               RuntimeExecutionRequest.PersistenceMode persistenceMode) {
        RuntimeExecutionRequest.PersistenceMode mode = persistenceMode != null
                ? persistenceMode
                : RuntimeExecutionRequest.PersistenceMode.STATEFUL;
        if (mode == RuntimeExecutionRequest.PersistenceMode.STATELESS) {
            throw new UnsupportedOperationException("Stateless runtime execution is not supported by " + getClass().getName());
        }
        return execute(scope, input, callbacks);
    }
}
