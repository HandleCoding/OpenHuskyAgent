package io.github.huskyagent.domain.event;

@FunctionalInterface
public interface TokenSubscriber {

    void onToken(String sessionId, String token, boolean reasoning);
}