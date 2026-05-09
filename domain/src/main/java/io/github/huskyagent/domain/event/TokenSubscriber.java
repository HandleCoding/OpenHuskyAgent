package io.github.huskyagent.domain.event;

/**
 * Token 流订阅者 — 接收 LLM 逐 token 流式推送。
 *
 * <p>与 {@link ChannelSubscriber} 不同，TokenSubscriber 处理高频 token 流，
 * 不经过 Hook 系统，直连到各渠道的 emitter。</p>
 */
@FunctionalInterface
public interface TokenSubscriber {

    /**
     * 处理一个 token。
     *
     * @param sessionId 会话 ID
     * @param token     token 内容
     * @param reasoning 是否为推理/thinking token
     */
    void onToken(String sessionId, String token, boolean reasoning);
}