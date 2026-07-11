package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.RequestToolContext;
import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.domain.prompt.PromptBuilder;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.ai.LlmRetryPolicy;
import io.github.huskyagent.infra.ai.LlmUsageDetails;
import io.github.huskyagent.infra.ai.LlmUsageDetailsExtractor;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.context.TokenCounter;
import io.github.huskyagent.infra.context.TokenUsage;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.llm.SpringAiLlmMessageMapper;
import io.github.huskyagent.infra.llm.api.LlmMessage;
import io.github.huskyagent.infra.llm.api.LlmRequest;
import io.github.huskyagent.infra.llm.api.LlmResult;
import io.github.huskyagent.infra.llm.api.LlmStreamEvent;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.llm.api.LlmUsage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

@Slf4j
public class CallModelNode {

    private static final String DYNAMIC_PROMPT_TURN_ID_METADATA = "dynamicPromptTurnId";

    private final LlmTransport llmTransport;
    private final ModelSelection modelSelection;
    private final LlmRetryPolicy llmRetryPolicy;
    private final LlmUsageDetailsExtractor usageDetailsExtractor;
    private final DynamicPromptSnapshotCache dynamicPromptSnapshotCache;
    private final long callBlockTimeoutMinutes;
    private final HookRegistry hookRegistry;
    private final ChannelEventBus eventBus;
    private final PromptBuilder promptBuilder;
    private final PromptContext basePromptContext;
    private final String stableSystemPrompt;
    private final TokenCounter tokenCounter;

    public CallModelNode(Dependencies dependencies) {
        this.llmTransport = dependencies.llmTransport();
        this.modelSelection = dependencies.modelSelection();
        this.llmRetryPolicy = dependencies.llmRetryPolicy();
        this.usageDetailsExtractor = dependencies.usageDetailsExtractor();
        this.dynamicPromptSnapshotCache = dependencies.dynamicPromptSnapshotCache();
        this.callBlockTimeoutMinutes = dependencies.callBlockTimeoutMinutes();
        this.hookRegistry = dependencies.hookRegistry();
        this.eventBus = dependencies.eventBus();
        this.promptBuilder = dependencies.promptBuilder();
        this.basePromptContext = dependencies.basePromptContext();
        this.stableSystemPrompt = dependencies.stableSystemPrompt();
        this.tokenCounter = dependencies.tokenCounter();
    }

    public record Dependencies(LlmTransport llmTransport,
                               ModelSelection modelSelection,
                               LlmRetryPolicy llmRetryPolicy,
                               LlmUsageDetailsExtractor usageDetailsExtractor,
                               DynamicPromptSnapshotCache dynamicPromptSnapshotCache,
                               long callBlockTimeoutMinutes,
                               HookRegistry hookRegistry,
                               ChannelEventBus eventBus,
                               PromptBuilder promptBuilder,
                               PromptContext basePromptContext,
                               String stableSystemPrompt,
                               TokenCounter tokenCounter) {
    }

    public AsyncNodeActionWithConfig<ReActAgentState> build() {
        return (state, config) -> {
            long nodeStartNanos = System.nanoTime();
            List<Message> messages = state.messages();
            if (messages.isEmpty()) {
                return failedFuture(new IllegalArgumentException("messages is empty; cannot call model"));
            }

            String sessionId = config != null ? config.threadId().orElse(null) : null;
            ChannelIdentity identity = config != null
                    ? (ChannelIdentity) config.metadata("channelIdentity").orElse(null) : null;
            Principal principalVal = config != null
                    ? (Principal) config.metadata("principal").orElse(null) : null;
            long promptStartNanos = System.nanoTime();
            DynamicPromptSnapshotCache.Snapshot dynamicPromptSnapshot = dynamicPromptSnapshot(
                    sessionId, config != null ? config.metadata(DYNAMIC_PROMPT_TURN_ID_METADATA).map(Object::toString).orElse(null) : null,
                    identity, principalVal, messages);
            String dynamicSystemPrompt = dynamicPromptSnapshot.prompt();
            String systemPrompt = stableSystemPrompt == null ? "" : stableSystemPrompt;
            RequestToolContext requestToolContext = RequestToolContext.from(config);
            List<Message> requestMessages = withDynamicSystemPrompt(messages, dynamicSystemPrompt);
            int estimatedHistoryTokens = estimateMessages(messages);
            int estimatedRequestTokens = estimateMessages(requestMessages);
            int estimatedStablePromptTokens = estimateText(systemPrompt);
            int estimatedDynamicPromptTokens = estimateText(dynamicSystemPrompt);
            int estimatedPromptTokens = estimatedStablePromptTokens + estimatedRequestTokens;
            long promptDurationMs = elapsedMs(promptStartNanos);

            // ── LLM_CALL_BEFORE Hook ────────────────────────────────────────────
            long beforeHookStartNanos = System.nanoTime();
            HookResult beforeResult = hookRegistry.fireBefore(
                    HookEvent.LLM_CALL_BEFORE, sessionId,
                    Map.of(HookDataKeys.LLM_MESSAGES, requestMessages));
            long beforeHookDurationMs = elapsedMs(beforeHookStartNanos);
            if (!beforeResult.allowed()) {
                return failedFuture(new RuntimeException("LLM call blocked: " + beforeResult.blockReason()));
            }

            String injectedContext = beforeResult.getModification(HookDataKeys.LLM_CONTEXT_INJECT, String.class);
            if (injectedContext != null && !injectedContext.isBlank()) {
                log.debug("[model] Hook injected context: {} chars", injectedContext.length());
            }

            long debugDumpStartNanos = System.nanoTime();
            GraphUtils.logLlmRequest(systemPrompt, requestMessages);
            long debugDumpDurationMs = elapsedMs(debugDumpStartNanos);


            long startTime = System.currentTimeMillis();
            long llmStartNanos = System.nanoTime();

            try {
                int maxRetries = llmRetryPolicy.getMaxRetries();
                long initialBackoffMs = llmRetryPolicy.getInitialBackoffMs();
                Exception lastException = null;

                LlmResult llmResult = null;
                AtomicReference<Long> firstChunkLatencyMs = new AtomicReference<>();
                AtomicReference<Long> firstTokenLatencyMs = new AtomicReference<>();

                List<LlmMessage> llmMessages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    llmMessages.add(LlmMessage.system(systemPrompt));
                }
                llmMessages.addAll(SpringAiLlmMessageMapper.toLlmMessages(requestMessages));
                LlmRequest llmRequest = LlmRequest.builder()
                        .model(modelSelection != null ? modelSelection.getModelName() : null)
                        .messages(llmMessages)
                        .tools(SpringAiLlmMessageMapper.toLlmTools(requestToolContext.toolDefinitions()))
                        .temperature(modelSelection != null ? modelSelection.getTemperature() : null)
                        .maxTokens(modelSelection != null ? modelSelection.getMaxTokens() : null)
                        .stream(true)
                        .build();

                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    if (attempt > 0) {
                        long backoff = initialBackoffMs * (1L << (attempt - 1));
                        log.warn("[model] Retry attempt {}; waiting {}ms...", attempt, backoff);
                        Thread.sleep(backoff);
                    }

                    firstChunkLatencyMs.set(null);
                    firstTokenLatencyMs.set(null);

                    try {
                        llmResult = llmTransport.stream(llmRequest, event -> {
                            firstChunkLatencyMs.compareAndSet(null, elapsedMs(llmStartNanos));
                            if (event instanceof LlmStreamEvent.TextDelta td) {
                                firstTokenLatencyMs.compareAndSet(null, elapsedMs(llmStartNanos));
                                eventBus.streamToken(sessionId, td.text(), false);
                            } else if (event instanceof LlmStreamEvent.ReasoningDelta rd) {
                                firstTokenLatencyMs.compareAndSet(null, elapsedMs(llmStartNanos));
                                eventBus.streamToken(sessionId, rd.text(), true);
                            }
                        });

                        LlmUsage usage = llmResult.usage();
                        LlmUsageDetails usageDetails = usageDetailsFrom(usage);
                        log.info("[model] LLM timing breakdown: session={}, attempt={}, promptBuild={}ms, beforeHook={}ms, debugDump={}ms, firstChunk={}ms, firstToken={}ms, stream={}ms, total={}ms, requestMessages={}, dynamicPromptChars={}, dynamicPromptHash={}, dynamicPromptCacheHit={}, estimatedHistoryTokens={}, estimatedStablePromptTokens={}, estimatedDynamicPromptTokens={}, estimatedRequestTokens={}, estimatedPromptTokens={}, promptTokens={}, completionTokens={}, totalTokens={}, cachedPromptTokens={}, uncachedPromptTokens={}, nativeUsageType={}, hasPromptTokenDetails={}",
                                sessionId,
                                attempt + 1,
                                promptDurationMs,
                                beforeHookDurationMs,
                                debugDumpDurationMs,
                                firstChunkLatencyMs.get(),
                                firstTokenLatencyMs.get(),
                                elapsedMs(llmStartNanos),
                                elapsedMs(nodeStartNanos),
                                requestMessages.size(),
                                dynamicSystemPrompt.length(),
                                dynamicPromptSnapshot.promptHash(),
                                dynamicPromptSnapshot.cacheHit(),
                                estimatedHistoryTokens,
                                estimatedStablePromptTokens,
                                estimatedDynamicPromptTokens,
                                estimatedRequestTokens,
                                estimatedPromptTokens,
                                usageDetails.promptTokens(),
                                usageDetails.completionTokens(),
                                usageDetails.totalTokens(),
                                usageDetails.cachedPromptTokens(),
                                usageDetails.uncachedPromptTokens(),
                                usageDetails.nativeUsageType(),
                                usageDetails.hasPromptTokenDetails());

                        if (isEmptyFinalResponse(llmResult)) {
                            throw new EmptyLlmResponseException();
                        }

                        lastException = null;
                        break;
                    } catch (Exception e) {
                        lastException = e;
                        if (e instanceof EmptyLlmResponseException) {
                            if (attempt < maxRetries) {
                                log.warn("[model] LLM returned an empty response (attempt {}/{}); retrying", attempt + 1, maxRetries);
                            } else {
                                log.error("[model] LLM returned an empty response after {} retries", maxRetries);
                            }
                            continue;
                        }
                        if (!llmRetryPolicy.isRetryable(e)) {
                            if (isCancellation(e)) {
                                log.info("[model] LLM call interrupted: session={}", sessionId);
                            } else {
                                log.error("[model] Non-retryable error: {}", e.getMessage());
                            }
                            break;
                        }
                        if (attempt < maxRetries) {
                            log.warn("[model] Retryable error (attempt {}/{}): {}", attempt + 1, maxRetries, e.getMessage());
                        } else {
                            log.error("[model] Still failed after {} retries", maxRetries);
                        }
                    }
                }

                if (lastException != null) {
                    throw lastException;
                }
                if (llmResult == null) {
                    throw new EmptyLlmResponseException();
                }

                AssistantMessage output = SpringAiLlmMessageMapper.toAssistantMessage(llmResult);
                String finishReason = llmResult.finishReason() != null ? llmResult.finishReason() : "unknown";
                GraphUtils.logLlmResponse(output, finishReason);
                int newCount = state.modelCallCount() + 1;

                // ── LLM_CALL_AFTER Hook ────────────────────────────────────────
                hookRegistry.fireAfter(HookEvent.LLM_CALL_AFTER, sessionId,
                        Map.of(HookDataKeys.LLM_RESPONSE, output,
                               HookDataKeys.LLM_FINISH_REASON, finishReason,
                               HookDataKeys.LLM_DURATION_MS, System.currentTimeMillis() - startTime,
                               HookDataKeys.LLM_MODEL_CALL_COUNT, newCount,
                               HookDataKeys.LLM_HAS_TOOL_CALLS, output.hasToolCalls()));

                Map<String, Object> result = new java.util.HashMap<>();
                result.put("messages", output);
                result.put(ReActAgentState.MODEL_CALL_COUNT, newCount);
                LlmUsage u = llmResult.usage();
                if (u != null && (u.promptTokens() != null || u.completionTokens() != null || u.totalTokens() != null)) {
                    result.put(ReActAgentState.LAST_TOKEN_USAGE, new TokenUsage(
                            u.promptTokens() != null ? u.promptTokens() : 0,
                            u.completionTokens() != null ? u.completionTokens() : 0,
                            u.totalTokens() != null ? u.totalTokens() : 0));
                }
                return completedFuture(result);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (isCancellation(e)) {
                    log.info("[model] LLM call cancelled: session={}", sessionId);
                } else if (cause instanceof io.github.huskyagent.infra.llm.transport.LlmHttpException httpEx) {
                    log.error("[model] API {} error: body={}", httpEx.statusCode(), httpEx.body());
                } else {
                    log.error("[model] LLM call failed: {}", cause.getMessage());
                }
                return failedFuture(e);
            }
        };
    }

    private LlmUsageDetails usageDetailsFrom(LlmUsage usage) {
        if (usage == null) {
            return usageDetailsExtractor.extract(null);
        }
        // Reuse extractor for null-safe zeros via a lightweight adapter is overkill; build details manually
        return new LlmUsageDetails(
                usage.promptTokens() != null ? usage.promptTokens() : 0,
                usage.completionTokens() != null ? usage.completionTokens() : 0,
                usage.totalTokens() != null ? usage.totalTokens() : 0,
                usage.cachedPromptTokens(),
                usage.cachedPromptTokens() != null && usage.promptTokens() != null
                        ? Math.max(0, usage.promptTokens() - usage.cachedPromptTokens())
                        : null,
                "llm-transport",
                usage.cachedPromptTokens() != null);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static boolean isCancellation(Throwable error) {
        return Thread.currentThread().isInterrupted()
                || hasCause(error, InterruptedException.class)
                || hasCause(error, java.util.concurrent.CancellationException.class);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private int estimateMessages(List<Message> messages) {
        return tokenCounter != null ? tokenCounter.countTokens(messages) : 0;
    }

    private int estimateText(String text) {
        return tokenCounter != null ? tokenCounter.countTextTokens(text) : 0;
    }

    DynamicPromptSnapshotCache.Snapshot dynamicPromptSnapshot(String sessionId, String turnId, ChannelIdentity identity, Principal principal, List<Message> messages) {
        return dynamicPromptSnapshotCache.getOrCreate(
                sessionId,
                turnId,
                dynamicPromptUserTurnKey(messages),
                () -> buildDynamicPrompt(sessionId, identity, principal));
    }

    private String buildDynamicPrompt(String sessionId, ChannelIdentity identity, Principal principal) {
        return promptBuilder.buildDynamic(basePromptContext.withChannelContext(sessionId, identity, principal));
    }

    private static String dynamicPromptUserTurnKey(List<Message> messages) {
        int index = lastUserMessageIndex(messages);
        if (index < 0) {
            return null;
        }
        Message message = messages.get(index);
        String text = message.getText() == null ? "" : message.getText();
        return index + ":" + Integer.toHexString(text.hashCode());
    }

    static boolean isEmptyFinalResponse(StringBuilder fullText, AssistantMessage lastWithToolCalls) {
        return lastWithToolCalls == null && (fullText == null || fullText.toString().isBlank());
    }

    static boolean isEmptyFinalResponse(LlmResult result) {
        if (result == null) {
            return true;
        }
        if (result.hasToolCalls()) {
            return false;
        }
        boolean noText = result.text() == null || result.text().isBlank();
        // Reasoning-only is still a non-empty model turn for thinking models (e.g. DeepSeek v4).
        boolean noReasoning = result.reasoning() == null || result.reasoning().isBlank();
        return noText && noReasoning;
    }

    private static final class EmptyLlmResponseException extends RuntimeException {
        private EmptyLlmResponseException() {
            super("LLM returned empty response without tool calls");
        }
    }

    static List<Message> withDynamicSystemPrompt(List<Message> messages, String dynamicPrompt) {
        if (dynamicPrompt == null || dynamicPrompt.isBlank()) {
            return messages;
        }

        int index = lastUserMessageIndex(messages);
        if (index < 0) {
            return messages;
        }

        List<Message> requestMessages = new ArrayList<>(messages);
        UserMessage userMessage = (UserMessage) requestMessages.get(index);
        requestMessages.set(index, userMessage.mutate()
                .text(enrichUserText(userMessage.getText(), dynamicPrompt))
                .build());
        return requestMessages;
    }

    private static int lastUserMessageIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    private static String enrichUserText(String userText, String dynamicPrompt) {
        String text = userText == null ? "" : userText;
        String runtimeContext = "<runtime_context>\n"
                + "[System note: The following runtime context was injected by Husky for this turn. "
                + "It is NOT new user input. Treat it as operational background for answering the user's request above.]\n\n"
                + dynamicPrompt.trim()
                + "\n</runtime_context>";
        return text.isBlank() ? runtimeContext : text + "\n\n" + runtimeContext;
    }
}
