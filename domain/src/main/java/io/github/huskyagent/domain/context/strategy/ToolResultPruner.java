package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.domain.context.PruneConfig;
import io.github.huskyagent.domain.context.PruneStrategy;
import io.github.huskyagent.infra.context.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具结果剪枝策略
 * 参考 Hermes-Agent 的 _prune_old_tool_results 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultPruner implements PruneStrategy {

    private final TokenCounter tokenCounter;
    private final ToolLimitsConfig limitsConfig;

    @Override
    public String getName() {
        return "toolResultPruner";
    }

    @Override
    public List<Message> prune(List<Message> messages, PruneConfig config) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<Message> result = new ArrayList<>();
        Set<String> seenToolResults = new HashSet<>();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            // 保护前 N 条消息
            if (i < config.protectFirstN()) {
                result.add(msg);
                continue;
            }

            // 处理 ToolResponseMessage
            if (msg instanceof ToolResponseMessage toolMsg) {
                Message pruned = pruneToolResponse(toolMsg, seenToolResults);
                if (pruned != null) {
                    result.add(pruned);
                }
                continue;
            }

            // 处理 AssistantMessage（可能包含 tool_call）
            if (msg instanceof AssistantMessage assistantMsg) {
                Message pruned = pruneAssistantMessage(assistantMsg);
                result.add(pruned);
                continue;
            }

            // 其他消息原样保留
            result.add(msg);
        }

        log.debug("Tool result pruning completed: {} -> {} messages", messages.size(), result.size());
        return result;
    }

    /**
     * 剪枝工具响应消息
     * Spring AI 的 ToolResponseMessage 构造需要 ToolResponse 列表
     * 这里采用简化策略：去重和内容截断通过保留原消息实现
     */
    private Message pruneToolResponse(ToolResponseMessage msg, Set<String> seenToolResults) {
        String content = msg.getText();

        // 去重：相同工具结果只保留一次
        if (content != null && !content.isEmpty()) {
            String key = content.hashCode() + "_" + content.length();
            if (seenToolResults.contains(key)) {
                log.debug("Dropping duplicate tool result");
                return null;
            }
            seenToolResults.add(key);
        }

        // 对于过长的工具输出，保留原消息但添加摘要注释
        // 由于 ToolResponseMessage 构造复杂，这里不做截断
        // 后续的 LLM 摘要阶段会处理过长的内容
        if (content != null && content.length() > limitsConfig.getPruneMaxToolResultLength()) {
            log.debug("Tool result is too long ({}) chars, will be handled by summary", content.length());
        }

        return msg;
    }

    /**
     * 剪枝助手消息（截断过长文本）
     */
    private Message pruneAssistantMessage(AssistantMessage msg) {
        String content = msg.getText();
        if (content != null && content.length() > limitsConfig.getPruneMaxArgumentsLength()) {
            String truncated = content.substring(0, limitsConfig.getPruneMaxArgumentsLength())
                + "... [truncated from " + content.length() + " chars]";
            return new AssistantMessage(truncated);
        }
        return msg;
    }
}