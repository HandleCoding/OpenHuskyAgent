package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 摘要策略接口
 * 用于生成对话摘要
 */
public interface SummaryStrategy {

    /**
     * 生成摘要
     *
     * @param turns  需要摘要的对话轮次
     * @param config 摘要配置
     * @return 生成的摘要文本
     */
    String generate(List<Message> turns, SummaryConfig config);

    /**
     * 迭代更新摘要
     *
     * @param previousSummary 之前的摘要
     * @param newTurns       新的对话轮次
     * @param config         摘要配置
     * @return 更新后的摘要
     */
    String update(String previousSummary, List<Message> newTurns, SummaryConfig config);

    /**
     * 获取策略名称
     */
    String getName();
}
