package io.github.huskyagent.domain.event;

/**
 * 渠道事件消费者 — 各渠道实现此接口来接收 ChannelEvent。
 */
@FunctionalInterface
public interface ChannelSubscriber {

    /**
     * 处理事件。实现应快速返回，避免阻塞分发线程。
     */
    void onEvent(ChannelEvent event);
}
