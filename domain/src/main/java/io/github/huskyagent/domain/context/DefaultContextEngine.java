package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.infra.context.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Context 压缩引擎
 * 参考 Hermes-Agent 的压缩算法：剪枝 + 摘要
 *
 * 运行时状态按 sessionId 隔离，避免多会话交叉污染。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContextEngine implements ContextEngine {

    private final ContextConfig config;
    private final TokenCounter tokenCounter;
    private final PruneStrategy pruneStrategy;
    private final SummaryStrategy summaryStrategy;

    /** 按 sessionId 隔离的运行时状态 */
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
        // updateFromResponse 不带 sessionId，由 ContextManager 路径传入
        // 保留全局默认值用于无 session 场景（如 getStatus 无 sessionId 时）
        // 但实际使用都通过带 sessionId 的 ContextManager 路径
        if (usage != null) {
            log.debug("updateFromResponse called without sessionId: prompt={}, completion={}",
                    usage.promptTokens(), usage.completionTokens());
        }
    }

    /**
     * 带 sessionId 的 token 使用更新
     */
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
                config.getTailTokenBudget(), config.getMaxSummaryTokens());

        log.info("Compression completed: {} -> {} messages",
                messages.size(), compressed.size());

        return compressed;
    }

    private int thresholdTokens(ContextPolicy policy) {
        return (int) (policy.getContextLength() * policy.getThresholdPercent());
    }

    /**
     * 使用摘要进行压缩
     */
    private List<Message> compressWithSummary(List<Message> messages, int protectFirstN, int tailTokenBudget, int maxSummaryTokens) {

        int tailBoundary = tokenCounter.findBoundaryByTokens(messages, protectFirstN, tailTokenBudget);

        List<Message> head = messages.subList(0, Math.min(protectFirstN, messages.size()));
        List<Message> middle = messages.subList(protectFirstN, tailBoundary);
        List<Message> tail = messages.subList(tailBoundary, messages.size());

        log.debug("Compression boundaries: head={}, middle={}, tail={}",
                head.size(), middle.size(), tail.size());

        String newSummary = summaryStrategy.generate(ContextSummaryMessages.summaryInput(head, middle, tail),
                SummaryConfig.of(maxSummaryTokens));

        List<Message> result = new ArrayList<>(ContextSummaryMessages.withoutSummaries(head));

        if (newSummary != null && !newSummary.isEmpty()) {
            result.add(ContextSummaryMessages.summaryMessage(newSummary));
        }

        result.addAll(ContextSummaryMessages.withoutSummaries(tail));

        result = sanitizeToolPairs(result);

        return result;
    }

    /**
     * 修复孤立的 tool_call/result 对
     */
    private List<Message> sanitizeToolPairs(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        boolean hasPendingToolCall = false;

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistantMsg) {
                hasPendingToolCall = !assistantMsg.getToolCalls().isEmpty();
            }

            if (msg.getMessageType().getValue().equals("tool_response") ||
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
        // 无 sessionId 调用时返回空状态
        return ContextStatus.empty(config.getContextLength(), config.getThresholdTokens());
    }

    /**
     * 带 sessionId 的状态查询
     */
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

    /** 按 session 隔离的运行时状态 */
    private static class SessionContextState {
        int lastPromptTokens = 0;
        int lastCompletionTokens = 0;
        int compressionCount = 0;
    }
}