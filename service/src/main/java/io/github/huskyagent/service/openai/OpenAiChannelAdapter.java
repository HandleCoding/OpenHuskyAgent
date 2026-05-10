package io.github.huskyagent.service.openai;

import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.TokenSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
class OpenAiChannelAdapter implements TokenSubscriber {

    private final ConcurrentHashMap<String, OpenAiStreamingRuntimeCallbacks> activeCallbacks = new ConcurrentHashMap<>();

    OpenAiChannelAdapter(ChannelEventBus eventBus) {
        eventBus.subscribeTokens("openai-compatible", this);
    }

    void register(String sessionId, OpenAiStreamingRuntimeCallbacks callbacks) {
        if (sessionId != null && callbacks != null) {
            activeCallbacks.put(sessionId, callbacks);
        }
    }

    void unregister(String sessionId) {
        if (sessionId != null) {
            activeCallbacks.remove(sessionId);
        }
    }

    boolean hasCallback(String sessionId) {
        return sessionId != null && activeCallbacks.containsKey(sessionId);
    }

    @Override
    public void onToken(String sessionId, String token, boolean reasoning) {
        OpenAiStreamingRuntimeCallbacks callbacks = activeCallbacks.get(sessionId);
        if (callbacks == null) {
            return;
        }
        try {
            callbacks.emitToken(token, reasoning);
        } catch (Exception e) {
            log.error("[OpenAiChannelAdapter] SSE token push failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
