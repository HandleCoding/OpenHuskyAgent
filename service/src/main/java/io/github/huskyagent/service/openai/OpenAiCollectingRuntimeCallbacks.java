package io.github.huskyagent.service.openai;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.RuntimeScope;

class OpenAiCollectingRuntimeCallbacks implements RuntimeCallbacks {

    private final StringBuilder tokens = new StringBuilder();

    private String message;

    @Override
    public void text(RuntimeScope scope, TextEvent event) {
        if (event == null || event.reasoning()) {
            return;
        }
        if (event.isTokenEvent()) {
            tokens.append(event.token());
            return;
        }
        if (!event.intermediate()) {
            message = event.text();
        }
    }

    String content(ChatResult result) {
        if (!tokens.isEmpty()) {
            return tokens.toString();
        }
        if (message != null) {
            return message;
        }
        return result != null ? result.content() : "";
    }
}
