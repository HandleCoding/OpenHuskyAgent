package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;

import java.util.Set;

public interface ChannelEventBus {

    void publish(ChannelEvent event);

    void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber);

    void unsubscribe(String channelName);

    /** Streams token-by-token model output to channel adapters that support live rendering. */
    void streamToken(String sessionId, String token, boolean reasoning);

    void subscribeTokens(String channelName, TokenSubscriber subscriber);

    void unsubscribeTokens(String channelName);
}
