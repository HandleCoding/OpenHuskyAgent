package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.infra.channel.OutboundMessage;

public record RuntimeExecutionResult(
        ChatResult chatResult,
        RuntimeScope scope,
        String sessionId,
        boolean commandHandled,
        OutboundMessage commandReply
) {
    public static RuntimeExecutionResult commandHandled(OutboundMessage reply) {
        ChatResult result = ChatResult.success(
                reply != null ? reply.getText() : null,
                reply != null ? reply.getSessionId() : null,
                false);
        return new RuntimeExecutionResult(result, null, result.sessionId(), true, reply);
    }

    public static RuntimeExecutionResult rejected(ChatResult chatResult) {
        return new RuntimeExecutionResult(chatResult, null, chatResult != null ? chatResult.sessionId() : null, false, null);
    }

    public static RuntimeExecutionResult executed(ChatResult chatResult, RuntimeScope scope) {
        return new RuntimeExecutionResult(chatResult, scope, scope != null ? scope.getSessionId() : null, false, null);
    }
}
