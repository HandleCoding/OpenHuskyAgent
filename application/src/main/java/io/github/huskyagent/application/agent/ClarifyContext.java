package io.github.huskyagent.application.agent;

import java.util.List;

public record ClarifyContext(
        String sessionId,
        String question,
        List<String> options,
        String agentText,
        ClarifyResponder responder
) {
    public void respond(String answer) {
        responder.respond(answer);
    }
}
