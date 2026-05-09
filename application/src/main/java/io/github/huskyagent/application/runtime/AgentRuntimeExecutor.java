package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.session.RuntimeScope;

public interface AgentRuntimeExecutor {
    ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks);
}
