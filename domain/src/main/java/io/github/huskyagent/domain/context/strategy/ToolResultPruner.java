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

            if (i < config.protectFirstN()) {
                result.add(msg);
                continue;
            }

            if (msg instanceof ToolResponseMessage toolMsg) {
                Message pruned = pruneToolResponse(toolMsg, seenToolResults);
                if (pruned != null) {
                    result.add(pruned);
                }
                continue;
            }

            if (msg instanceof AssistantMessage assistantMsg) {
                Message pruned = pruneAssistantMessage(assistantMsg);
                result.add(pruned);
                continue;
            }

            result.add(msg);
        }

        log.debug("Tool result pruning completed: {} -> {} messages", messages.size(), result.size());
        return result;
    }

    private Message pruneToolResponse(ToolResponseMessage msg, Set<String> seenToolResults) {
        String content = msg.getText();

        if (content != null && !content.isEmpty()) {
            String key = content.hashCode() + "_" + content.length();
            if (seenToolResults.contains(key)) {
                log.debug("Dropping duplicate tool result");
                return null;
            }
            seenToolResults.add(key);
        }

        if (content != null && content.length() > limitsConfig.getPruneMaxToolResultLength()) {
            log.debug("Tool result is too long ({}) chars, will be handled by summary", content.length());
        }

        return msg;
    }

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