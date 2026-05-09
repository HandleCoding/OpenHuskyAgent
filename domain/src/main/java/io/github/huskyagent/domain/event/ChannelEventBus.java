package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;

import java.util.Set;

/**
 * 渠道事件总线 — 统一 Hook 事件分发 + Token 流式推送。
 *
 * <p>新渠道只需实现 {@link ChannelSubscriber} 并订阅所需事件，无需注册 Hook 或修改服务端代码。</p>
 *
 * <p>Token 流（高频 per-request）通过 {@link #streamToken} 直连到 {@link TokenSubscriber}，
 * 不经过 Hook 系统，避免每 token 遍历所有 AfterHook 的开销。</p>
 */
public interface ChannelEventBus {

    /**
     * 发布生命周期事件到所有匹配的订阅者。
     */
    void publish(ChannelEvent event);

    /**
     * 订阅生命周期事件。
     *
     * @param channelName 渠道唯一标识（如 "tui", "chatbot", "in-process-tui"）
     * @param eventFilter 关心的事件类型集合，null 或空表示订阅全部事件
     * @param subscriber  事件消费者
     */
    void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber);

    /**
     * 取消生命周期事件订阅。
     */
    void unsubscribe(String channelName);

    /**
     * 推送一个 LLM token 到所有 TokenSubscriber。
     *
     * @param sessionId 会话 ID
     * @param token     token 内容
     * @param reasoning 是否为推理/thinking token
     */
    void streamToken(String sessionId, String token, boolean reasoning);

    /**
     * 订阅 Token 流。
     *
     * @param channelName 渠道唯一标识
     * @param subscriber  Token 消费者
     */
    void subscribeTokens(String channelName, TokenSubscriber subscriber);

    /**
     * 取消 Token 流订阅。
     */
    void unsubscribeTokens(String channelName);
}
