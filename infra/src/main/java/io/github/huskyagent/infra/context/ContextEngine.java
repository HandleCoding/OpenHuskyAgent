package io.github.huskyagent.infra.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Context 压缩引擎接口
 *
 * 参考 Hermes-Agent 的 ContextEngine 设计，支持多种压缩策略的扩展
 */
public interface ContextEngine {

    /**
     * 获取引擎名称
     */
    String getName();

    /**
     * 更新 token 使用情况（从 API 响应中获取）
     */
    void updateFromResponse(TokenUsage usage);

    /**
     * 判断是否需要压缩
     */
    boolean shouldCompress(int promptTokens);

    /**
     * 执行压缩，返回压缩后的消息列表
     */
    List<Message> compress(List<Message> messages, int currentTokens);

    /**
     * 会话开始时调用
     */
    void onSessionStart(String sessionId);

    /**
     * 会话结束时调用
     */
    void onSessionEnd(String sessionId);

    /**
     * 模型切换时更新参数
     */
    void updateModel(String model, int contextLength);

    /**
     * 获取当前状态
     */
    ContextStatus getStatus();
}