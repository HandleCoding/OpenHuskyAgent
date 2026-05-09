package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 剪枝策略接口
 * 用于在 LLM 摘要前对消息进行低成本剪枝
 */
public interface PruneStrategy {

    /**
     * 执行剪枝
     *
     * @param messages 原始消息列表
     * @param config   剪枝配置
     * @return 剪枝后的消息列表
     */
    List<Message> prune(List<Message> messages, PruneConfig config);

    /**
     * 获取策略名称
     */
    String getName();
}
