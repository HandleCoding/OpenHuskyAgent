package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.domain.context.ContextCompressionWindow;
import io.github.huskyagent.domain.context.ContextSummaryMessages;
import io.github.huskyagent.domain.context.ContextManagementRequest;
import io.github.huskyagent.domain.context.ContextManagementResult;
import io.github.huskyagent.domain.context.ContextManagementStrategy;
import io.github.huskyagent.domain.context.PruneConfig;
import io.github.huskyagent.domain.context.PruneStrategy;
import io.github.huskyagent.domain.context.SummaryConfig;
import io.github.huskyagent.domain.context.SummaryStrategy;
import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.infra.context.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContextManagementStrategy implements ContextManagementStrategy {
    private final TokenCounter tokenCounter;
    private final PruneStrategy pruneStrategy;
    private final SummaryStrategy summaryStrategy;

    @Override
    public String id() {
        return "default";
    }

    @Override
    public ContextManagementResult prepare(ContextManagementRequest request) {
        if (request == null || request.persistedMessages() == null || request.persistedMessages().isEmpty()) {
            return ContextManagementResult.unchanged(request != null ? request.persistedMessages() : List.of(), id(), "empty");
        }
        ContextPolicy policy = request.policy();
        if (policy == null || !policy.isEnabled()) {
            return ContextManagementResult.unchanged(request.persistedMessages(), id(), "disabled");
        }

        int threshold = thresholdTokens(policy);
        if (request.currentTokens() < threshold) {
            return ContextManagementResult.unchanged(request.persistedMessages(), id(), "below-threshold");
        }

        log.info("Preparing context: session={}, scene={}, strategy={}, messages={}, tokens={}",
                request.sessionId(), request.sceneId(), id(), request.persistedMessages().size(), request.currentTokens());

        List<Message> pruned = pruneStrategy.prune(request.persistedMessages(),
                PruneConfig.of(policy.getProtectFirstN(), policy.getTailTokenBudget()));

        int prunedTokens = tokenCounter.countTokens(pruned);
        if (prunedTokens < thresholdTokens(policy)) {
            return ContextManagementResult.changed(pruned, id(), "prune-sufficient",
                    Map.of("tokens", prunedTokens, "summary", false));
        }

        List<Message> compressed = compressWithSummary(pruned, policy.getProtectFirstN(),
                policy.getMaxSummaryTokens());
        return ContextManagementResult.changed(compressed, id(), "summary",
                Map.of("tokens", tokenCounter.countTokens(compressed), "summary", true));
    }

    private int thresholdTokens(ContextPolicy policy) {
        return (int) (policy.getContextLength() * policy.getThresholdPercent());
    }

    private List<Message> compressWithSummary(List<Message> messages, int protectFirstN, int maxSummaryTokens) {
        ContextCompressionWindow window = ContextCompressionWindow.of(messages, protectFirstN);

        String newSummary = summaryStrategy.generate(
                ContextSummaryMessages.summaryInput(window.head(), window.middle(), window.suffix()),
                SummaryConfig.of(maxSummaryTokens));

        List<Message> result = new ArrayList<>(ContextSummaryMessages.withoutSummaries(window.head()));
        if (newSummary != null && !newSummary.isEmpty()) {
            result.add(ContextSummaryMessages.summaryMessage(newSummary));
        }
        result.addAll(window.suffix());
        return sanitizeToolPairs(result);
    }

    private List<Message> sanitizeToolPairs(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        boolean hasPendingToolCall = false;

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistantMsg) {
                hasPendingToolCall = !assistantMsg.getToolCalls().isEmpty();
            }

            if (msg instanceof ToolResponseMessage
                    || msg.getMessageType().getValue().equals("tool_response")
                    || msg.getMessageType().getValue().equals("function")) {
                if (!hasPendingToolCall) {
                    log.debug("Dropping orphan tool response");
                    continue;
                }
                hasPendingToolCall = false;
            }

            result.add(msg);
        }
        return result;
    }
}
