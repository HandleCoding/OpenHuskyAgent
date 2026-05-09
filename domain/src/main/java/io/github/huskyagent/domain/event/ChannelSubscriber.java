package io.github.huskyagent.domain.event;

@FunctionalInterface
public interface ChannelSubscriber {

    void onEvent(ChannelEvent event);
}
