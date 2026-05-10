package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.infra.context.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContextEngine implements ContextEngine {

    private final ContextConfig config;
    private final TokenCounter tokenCounter;
    private final PruneStrategy pruneStrategy;
    private final SummaryStrategy summaryStrategy;

    private final ConcurrentHashMap<String, SessionContextState> sessionStates = new ConcurrentHashMap<>();

    private SessionContextState getState(String sessionId) {
        return sessionStates.computeIfAbsent(sessionId, k -> new SessionContextState());
    }

    @Override
    public String getName() {
        return "defaultCompressor";
    }

    @Override
    public void updateFromResponse(TokenUsage usage) {
        if (usage != null) {
            log.debug("updateFromResponse called without sessionId: prompt={}, completion={}",
                    usage.promptTokens(), usage.completionTokens());
        }
    }

    public void updateFromResponse(String sessionId, TokenUsage usage) {
        if (usage != null) {
            SessionContextState state = getState(sessionId);
            state.lastPromptTokens = usage.promptTokens();
            state.lastCompletionTokens = usage.completionTokens();
            log.debug("Updated token usage for session {}: prompt={}, completion={}",
                    sessionId, state.lastPromptTokens, state.lastCompletionTokens);
        }
    }

    public int lastPromptTokens(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        return Math.max(0, getState(sessionId).lastPromptTokens);
    }

    public int triggerTokens(String sessionId, int estimatedTokens) {
        return Math.max(estimatedTokens, lastPromptTokens(sessionId));
    }

    @Override
    public boolean shouldCompress(int promptTokens) {
        int threshold = config.getThresholdTokens();
        boolean should = promptTokens >= threshold;
        log.debug("Compression check: {} tokens, threshold {} -> {}",
                promptTokens, threshold, should);
        return should;
    }

    @Override
    public List<Message> compress(List<Message> messages, int currentTokens) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        log.info("Starting context compression: {} messages, {} tokens",
                messages.size(), currentTokens);

        List<Message> pruned = pruneStrategy.prune(messages,
                PruneConfig.of(config.getProtectFirstN(), config.getTailTokenBudget()));

        int prunedTokens = tokenCounter.countTokens(pruned);
        log.debug("After pruning: {} messages, {} tokens", pruned.size(), prunedTokens);

        if (!shouldCompress(prunedTokens)) {
            log.info("Pruning sufficient, no LLM summary needed");
            return pruned;
        }

        List<Message> compressed = compressWithSummary(pruned, config.getProtectFirstN(),
                config.getMaxSummaryTokens());

        log.info("Compression completed: {} -> {} messages",
                messages.size(), compressed.size());

        return compressed;
    }

    private int thresholdTokens(ContextPolicy policy) {
        return (int) (policy.getContextLength() * policy.getThresholdPercent());
    }

    private List<Message> compressWithSummary(List<Message> messages, int protectFirstN, int maxSummaryTokens) {

        ContextCompressionWindow window = ContextCompressionWindow.of(messages, protectFirstN);

        log.debug("Compression boundaries: head={}, middle={}, suffix={}",
                window.head().size(), window.middle().size(), window.suffix().size());

        String newSummary = summaryStrategy.generate(ContextSummaryMessages.summaryInput(window.head(), window.middle(), window.suffix()),
                SummaryConfig.of(maxSummaryTokens));

        List<Message> result = new ArrayList<>(ContextSummaryMessages.withoutSummaries(window.head()));

        if (newSummary != null && !newSummary.isEmpty()) {
            result.add(ContextSummaryMessages.summaryMessage(newSummary));
        }

        result.addAll(window.suffix());

        result = sanitizeToolPairs(result);

        return result;
    }

    private List<Message> sanitizeToolPairs(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        boolean hasPendingToolCall = false;

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistantMsg) {
                hasPendingToolCall = !assistantMsg.getToolCalls().isEmpty();
            }

            if (msg instanceof ToolResponseMessage ||
                    msg.getMessageType().getValue().equals("tool_response") ||
                    msg.getMessageType().getValue().equals("function")) {
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

    @Override
    public void onSessionStart(String sessionId) {
        log.debug("Session started: {}", sessionId);
        sessionStates.remove(sessionId);
    }

    @Override
    public void onSessionEnd(String sessionId) {
        log.debug("Session ended: {}", sessionId);
        sessionStates.remove(sessionId);
    }

    @Override
    public void updateModel(String model, int contextLength) {
        log.info("Model updated: {} with context length {}", model, contextLength);
    }

    @Override
    public ContextStatus getStatus() {
        return ContextStatus.empty(config.getContextLength(), config.getThresholdTokens());
    }

    public ContextStatus getStatus(String sessionId) {
        SessionContextState state = getState(sessionId);
        return new ContextStatus(
                state.lastPromptTokens,
                config.getThresholdTokens(),
                config.getContextLength(),
                calculateUsagePercent(state),
                state.compressionCount
        );
    }

    private double calculateUsagePercent(SessionContextState state) {
        if (config.getContextLength() == 0) {
            return 0.0;
        }
        return (double) state.lastPromptTokens / config.getContextLength() * 100;
    }

    private static class SessionContextState {
        int lastPromptTokens = 0;
        int lastCompletionTokens = 0;
        int compressionCount = 0;
    }
}