package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.context.ContextConfig;
import io.github.huskyagent.infra.context.ContextEngine;
import io.github.huskyagent.infra.context.ContextStatus;
import io.github.huskyagent.infra.context.TokenCounter;
import io.github.huskyagent.infra.context.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextManager {

    private final ContextEngine engine;
    private final SessionManager sessionManager;
    private final TokenCounter tokenCounter;
    private final ContextConfig config;
    private final HookRegistry hookRegistry;
    private final ContextManagementStrategyResolver strategyResolver;

    public List<Message> loadMessagesForContext(String sessionId) {
        return loadMessagesForContext(sessionId, null, null);
    }

    public List<Message> loadMessagesForContext(String sessionId, RuntimePolicy runtimePolicy, String sceneId) {
        List<Message> history = sessionManager.loadMessages(sessionId);
        return prepareContext(sessionId, runtimePolicy, sceneId, history);
    }

    public List<Message> prepareActiveContext(String sessionId, RuntimePolicy runtimePolicy, String sceneId,
                                              List<Message> activeMessages) {
        return prepareContext(sessionId, runtimePolicy, sceneId, activeMessages);
    }

    private List<Message> prepareContext(String sessionId, RuntimePolicy runtimePolicy, String sceneId,
                                         List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            log.debug("No context messages for session: {}", sessionId);
            return messages == null ? List.of() : messages;
        }

        int estimatedTokens = tokenCounter.countTokens(messages);
        int triggerTokens = triggerTokens(sessionId, estimatedTokens);
        log.debug("Session {} has {} messages, estimated {} tokens, trigger {} tokens",
            sessionId, messages.size(), estimatedTokens, triggerTokens);

        if (runtimePolicy != null) {
            ContextPolicy policy = runtimePolicy.getContextPolicy();
            if (!policy.isEnabled()) {
                log.info("[context] budget check: session={}, scene={}, enabled=false, messages={}, estimatedTokens={}, triggerTokens={}",
                        sessionId, sceneId, messages.size(), estimatedTokens, triggerTokens);
                return messages;
            }
            log.info("[context] budget check: session={}, scene={}, strategy={}, messages={}, estimatedTokens={}, triggerTokens={}, contextLength={}, thresholdTokens={}",
                    sessionId, sceneId, policy.getStrategyId(), messages.size(), estimatedTokens, triggerTokens,
                    policy.getContextLength(), thresholdTokens(policy));
            ContextManagementRequest request = new ContextManagementRequest(
                    sessionId,
                    sceneId,
                    policy,
                    messages,
                    triggerTokens,
                    null,
                    null);
            ContextManagementResult result = strategyResolver
                    .resolve(policy.getStrategyId())
                    .prepare(request);
            log.info("[context] strategy result: session={}, scene={}, strategy={}, reason={}, changed={}, messages={} -> {}",
                    sessionId, sceneId, policy.getStrategyId(), result.reason(), result.changed(),
                    messages.size(), result.messages().size());
            if (result.changed()) {
                fireCompressionHook(sessionId, messages, result.messages(), estimatedTokens);
            }
            return result.messages();
        }

        if (engine.shouldCompress(triggerTokens)) {
            log.info("Context compression triggered for session: {}", sessionId);
            List<Message> compressed = engine.compress(messages, estimatedTokens);
            fireCompressionHook(sessionId, messages, compressed, estimatedTokens);
            return compressed;
        }

        return messages;
    }

    private void fireCompressionHook(String sessionId, List<Message> history, List<Message> compressed, int estimatedTokens) {
        int compressedTokens = tokenCounter.countTokens(compressed);
        log.info("Compression result: {} -> {} messages, {} -> {} tokens",
                history.size(), compressed.size(), estimatedTokens, compressedTokens);

        hookRegistry.fireAfter(HookEvent.CONTEXT_COMPRESS, sessionId,
                java.util.Map.of(
                        HookDataKeys.COMPRESS_ORIGINAL_COUNT, history.size(),
                        HookDataKeys.COMPRESS_RESULT_COUNT, compressed.size(),
                        HookDataKeys.COMPRESS_ORIGINAL_TOKENS, estimatedTokens));
    }

    private int triggerTokens(String sessionId, int estimatedTokens) {
        if (engine instanceof DefaultContextEngine dce) {
            return dce.triggerTokens(sessionId, estimatedTokens);
        }
        return estimatedTokens;
    }

    private int thresholdTokens(ContextPolicy policy) {
        return (int) (policy.getContextLength() * policy.getThresholdPercent());
    }

    public void updateTokenUsage(String sessionId, TokenUsage usage) {
        if (engine instanceof DefaultContextEngine dce) {
            dce.updateFromResponse(sessionId, usage);
        } else {
            engine.updateFromResponse(usage);
        }
        log.debug("Updated token usage for session {}: prompt={}",
            sessionId, usage.promptTokens());
    }

    public ContextStatus getStatus(String sessionId) {
        if (engine instanceof DefaultContextEngine dce) {
            return dce.getStatus(sessionId);
        }
        return engine.getStatus();
    }

    public ContextStatus getStatus() {
        return engine.getStatus();
    }

    public void onSessionStart(String sessionId) {
        engine.onSessionStart(sessionId);
        log.debug("Context engine initialized for session: {}", sessionId);
    }

    public void onSessionEnd(String sessionId) {
        engine.onSessionEnd(sessionId);
        log.debug("Context engine finalized for session: {}", sessionId);
    }

    public ContextConfig getConfig() {
        return config;
    }

    public int estimateTokens(List<Message> messages) {
        return tokenCounter.countTokens(messages);
    }
}