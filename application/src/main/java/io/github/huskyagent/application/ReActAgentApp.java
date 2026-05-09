package io.github.huskyagent.application;

import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.AgentRuntimeExecutor;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.GraphCacheKey;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.domain.context.ContextManager;
import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.context.TokenUsage;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.context.TokenCounter;
import io.github.huskyagent.domain.hook.DefaultHookRegistry;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.approval.ApprovalInfo;
import io.github.huskyagent.infra.tool.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReAct graph executor.
 *
 * <p>Runs a graph for a complete {@link RuntimeScope}, including graph caching,
 * interrupt/resume handling, checkpoint compaction, and conversation persistence.</p>
 *
 * <p>Runtime orchestration and {@code SessionContext} binding belong to
 * {@code RuntimeExecutionService + ScopedRuntimeContext}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReActAgentApp implements AgentRuntimeExecutor {

    private static final String DYNAMIC_PROMPT_TURN_ID_METADATA = "dynamicPromptTurnId";

    private final AgentGraph agentGraph;
    private final SessionManager sessionManager;
    private final ContextManager contextManager;
    private final ApprovalService approvalService;
    private final TokenCounter tokenCounter;
    private final HookRegistry hookRegistry;
    private final DefaultHookRegistry defaultHookRegistry;
    private final io.github.huskyagent.infra.tool.todo.TodoStore todoStore;
    private final MultimodalMessageBuilder multimodalMessageBuilder;
    private final DynamicPromptSnapshotCache dynamicPromptSnapshotCache;

    /**
     * Caches compiled graphs by runtime-policy fingerprint so scenes, principals,
     * workspaces, and sessions do not accidentally share graph instances.
     */
    private final ConcurrentHashMap<GraphCacheKey, CompiledGraph<ReActAgentState>> graphCache = new ConcurrentHashMap<>();

    /** Per-session start time tracking for observability duration calculation */
    private final ConcurrentHashMap<String, Long> sessionStartTimes = new ConcurrentHashMap<>();


    public io.github.huskyagent.infra.tool.todo.TodoStore getTodoStore() {
        return todoStore;
    }

    public void clearGraphCache() {
        graphCache.clear();
        log.info("Cleared compiled graph cache");
    }

    // ── Graph execution entry point ─────────────────────────────────────────────

    @Override
    public ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks) {
        RuntimeCallbacks executionCallbacks = callbacks != null ? callbacks : RuntimeCallbacks.NOOP;
        final String sid = scope.getSessionId();
        try {
            scope.requireCompleteForExecution();
            log.info("Starting graph execution: sessionId={}, scene={}", sid, scope.getRuntimePolicy().getSceneId());
            initSession(sid);
            CompiledGraph<ReActAgentState> graph = getOrBuildGraph(scope);
            sessionManager.saveUserMessage(sid, multimodalMessageBuilder.persistenceText(input),
                    currentCheckpointId(graph, sid));
            String turnId = UUID.randomUUID().toString();
            RunnableConfig config = buildConfig(sid, turnId, scope);
            try {
                return runWithInterruptLoop(scope, graph, config, buildInputs(input), sid,
                        executionCallbacks);
            } finally {
                dynamicPromptSnapshotCache.clearTurn(sid, turnId);
            }
        } catch (Exception e) {
            log.error("Graph execution failed: sessionId={}", sid, e);
            return ChatResult.failure(e.getMessage());
        }
    }

    /**
     * Returns the latest checkpoint id for the current graph state so the next
     * user message can be anchored to the pre-write checkpoint.
     */
    private String currentCheckpointId(CompiledGraph<ReActAgentState> graph, String sessionId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            StateSnapshot<ReActAgentState> snapshot = graph.getState(config);
            if (snapshot == null) return null;
            return snapshot.config().checkPointId().orElse(null);
        } catch (Exception e) {
            log.warn("[checkpoint] Failed to get current checkpointId for session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }


    @SuppressWarnings("unchecked")
    private ChatResult runWithInterruptLoop(
            RuntimeScope scope,
            CompiledGraph<ReActAgentState> graph,
            RunnableConfig config,
            Map<String, Object> inputs,
            String sessionId,
            RuntimeCallbacks callbacks) throws Exception {

        Map<String, Object> currentInputs = inputs;
        ReActAgentState finalState = null;
        int resumeCount = 0;
        boolean hasModelOutput = false;

        while (true) {
            var generator = graph.stream(currentInputs, config);
            for (NodeOutput<ReActAgentState> step : generator) {
                finalState = step.state();
                if (AgentGraph.NODE_MODEL.equals(step.node())) {
                    hasModelOutput = true;
                }
            }

            GraphResult graphResult = GraphResult.from((AsyncGenerator<?>) generator);
            log.debug("[loop] graphResult type={}", graphResult.type());

            if (!graphResult.isInterruptionMetadata()) break;

            if (resumeCount++ > 50) {
                log.warn("Interrupt resume loop exceeded the limit; forcing exit");
                break;
            }

            InterruptionMetadata<ReActAgentState> metadata = graphResult.asInterruptionMetadata();
            String interruptType = metadata.metadata("type")
                    .map(Object::toString)
                    .orElse("approval");
            if ("clarify".equals(interruptType)) {
                callbacks.clarify(scope, buildClarifyContext(
                        graph, config, sessionId, metadata, finalState));
            } else {
                callbacks.approval(scope, buildApprovalContext(
                        graph, config, sessionId, metadata, finalState));
            }
            currentInputs = null;
        }

        recordProviderTokenUsage(sessionId, finalState);
        compactActiveCheckpointIfNeeded(scope, graph, config, finalState);
        return handleFinalState(sessionId, finalState, hasModelOutput);
    }


    /**
     * Returns a cached compiled graph for the runtime scope, or builds one if no
     * matching graph exists yet.
     */
    public CompiledGraph<ReActAgentState> getOrBuildGraph(RuntimeScope scope) throws Exception {
        RuntimePolicy runtimePolicy = scope.getRuntimePolicy();
        GraphCacheKey key = GraphCacheKey.of(
                runtimePolicy.getSceneId(),
                scope.getWorkingDirectory(),
                runtimePolicy.fingerprint(),
                runtimePolicy.getSystemPrompt(),
                scope.getPrincipal() != null ? scope.getPrincipal().getId() : "default",
                scope.getSessionId()
        );

        CompiledGraph<ReActAgentState> cached = graphCache.get(key);
        if (cached != null) {
            return cached;
        }

        return graphCache.computeIfAbsent(key, k -> {
            try {
                log.info("Building CompiledGraph: sceneId={}, workDir={}, policy={}",
                        runtimePolicy.getSceneId(), scope.getWorkingDirectory(), runtimePolicy.fingerprint());

                ChannelIdentity channelIdentity = scope.getChannelIdentity();
                Principal principal = scope.getPrincipal();
                SessionScope sessionScope = scope.toSessionScope();
                return agentGraph.buildGraph(
                        scope.getSessionId(),
                        scope.getWorkingDirectory(),
                        sessionScope,
                        runtimePolicy,
                        null,
                        runtimePolicy.getApprovalPolicy() == SceneConfig.ApprovalPolicy.NONE ? null : defaultHookRegistry,
                        channelIdentity,
                        principal
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to build graph for scene=" + runtimePolicy.getSceneId(), e);
            }
        });
    }


    private void initSession(String sessionId) {
        if (sessionManager.loadMessages(sessionId).isEmpty()) {
            contextManager.onSessionStart(sessionId);
            sessionStartTimes.put(sessionId, System.currentTimeMillis());
            hookRegistry.fireAfter(HookEvent.SESSION_START, sessionId, Map.of());
        }
    }

    private RunnableConfig buildConfig(String sessionId, String turnId, RuntimeScope scope) {
        return RunnableConfig.builder()
                .threadId(sessionId)
                .putMetadata(DYNAMIC_PROMPT_TURN_ID_METADATA, turnId)
                .putMetadata("channelIdentity", scope.getChannelIdentity())
                .putMetadata("principal", scope.getPrincipal())
                .build();
    }

    private Map<String, Object> buildInputs(AgentInput input) {
        return Map.of(
                "messages", multimodalMessageBuilder.buildUserMessage(input),
                ReActAgentState.MODEL_CALL_COUNT, 0
        );
    }

    private void compactActiveCheckpointIfNeeded(RuntimeScope scope,
                                                 CompiledGraph<ReActAgentState> graph,
                                                 RunnableConfig config,
                                                 ReActAgentState finalState) {
        if (scope == null || finalState == null) {
            return;
        }
        List<Message> activeMessages = finalState.messages();
        if (activeMessages == null || activeMessages.isEmpty() || !finalState.toolExecutionRequests().isEmpty()) {
            return;
        }
        if (lastAssistantHasToolCalls(activeMessages)) {
            log.debug("Skipping context compaction for session={} because latest assistant message has pending tool calls",
                    scope.getSessionId());
            return;
        }

        List<Message> compacted = contextManager.prepareActiveContext(
                scope.getSessionId(),
                scope.getRuntimePolicy(),
                scope.getRuntimePolicy().getSceneId(),
                activeMessages);
        if (compacted == null || compacted.isEmpty() || compacted == activeMessages || compacted.equals(activeMessages)) {
            return;
        }

        try {
            graph.updateState(config, Map.of("messages", AppenderChannel.ReplaceAllWith.of(compacted)));
            log.info("Compacted active checkpoint context: session={}, messages {} -> {}",
                    scope.getSessionId(), activeMessages.size(), compacted.size());
        } catch (Exception e) {
            log.warn("Failed to compact active checkpoint context: session={}", scope.getSessionId(), e);
        }
    }

    private boolean lastAssistantHasToolCalls(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                return assistantMessage.hasToolCalls();
            }
        }
        return false;
    }

    private Optional<TokenUsage> providerTokenUsage(ReActAgentState finalState) {
        return finalState != null
                ? finalState.lastTokenUsage().filter(usage -> usage.promptTokens() > 0)
                : Optional.empty();
    }

    private void recordProviderTokenUsage(String sessionId, ReActAgentState finalState) {
        providerTokenUsage(finalState).ifPresent(usage -> contextManager.updateTokenUsage(sessionId, usage));
    }

    private ChatResult handleFinalState(String sessionId, ReActAgentState finalState) {
        return handleFinalState(sessionId, finalState, false);
    }

    private ChatResult handleFinalState(String sessionId, ReActAgentState finalState, boolean streamed) {
        String response = extractResponse(finalState);
        sessionManager.saveAssistantMessage(sessionId, response);
        List<Message> allMessages = finalState != null ? finalState.messages() : List.of();
        TokenUsage estimatedUsage = estimateTokenUsage(allMessages, response);
        TokenUsage tokenUsage = providerTokenUsage(finalState).orElse(estimatedUsage);
        updateTokenUsage(sessionId, tokenUsage);

        Long startTime = sessionStartTimes.getOrDefault(sessionId, 0L);
        long durationMs = startTime > 0 ? System.currentTimeMillis() - startTime : 0;
        sessionStartTimes.remove(sessionId);

        Map<String, Object> sessionEndData = new HashMap<>();
        sessionEndData.put(HookDataKeys.SESSION_INPUT_TOKENS, tokenUsage.promptTokens());
        sessionEndData.put(HookDataKeys.SESSION_OUTPUT_TOKENS, tokenUsage.completionTokens());
        sessionEndData.put(HookDataKeys.SESSION_DURATION_MS, durationMs);
        hookRegistry.fireAfter(HookEvent.SESSION_END, sessionId, sessionEndData);
        todoStore.clear(sessionId);

        log.info("Chat completed: sessionId={}", sessionId);
        return ChatResult.success(response, sessionId, streamed, tokenUsage);
    }

    private String extractResponse(ReActAgentState state) {
        if (state == null) return "Unable to get response";
        List<Message> msgs = state.messages();
        if (msgs != null && !msgs.isEmpty()) {
            String latest = msgs.get(msgs.size() - 1).getText();
            if (latest != null && !latest.isBlank()) {
                return latest;
            }
            String previousAssistantText = extractLastAssistantText(state);
            if (previousAssistantText != null && !previousAssistantText.isBlank()) {
                return previousAssistantText;
            }
        }
        return "Unable to get response";
    }

    private TokenUsage estimateTokenUsage(List<Message> messages, String response) {
        int promptTokens = contextManager.estimateTokens(messages);
        int completionTokens = response != null ? tokenCounter.countTextTokens(response) : 0;
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private void updateTokenUsage(String sessionId, TokenUsage usage) {
        contextManager.updateTokenUsage(sessionId, usage);
    }

    private ApprovalContext buildApprovalContext(
            CompiledGraph<ReActAgentState> graph,
            RunnableConfig config,
            String sessionId,
            InterruptionMetadata<ReActAgentState> meta,
            ReActAgentState state) {

        final String nodeId = meta.nodeId();

        String toolName = meta.metadata(ApprovalInfo.TOOL_NAME_KEY)
                .map(Object::toString)
                .orElseGet(() -> state != null && !state.toolExecutionRequests().isEmpty()
                        ? state.toolExecutionRequests().get(0).name() : "unknown");

        String toolArgs = meta.metadata(ApprovalInfo.TOOL_ARGS_KEY)
                .map(Object::toString)
                .orElseGet(() -> state != null && !state.toolExecutionRequests().isEmpty()
                        ? state.toolExecutionRequests().get(0).arguments() : "{}");

        String agentText = extractLastAssistantText(state);

        String reason = meta.metadata(ApprovalInfo.TOOL_REASON_KEY)
                .map(Object::toString)
                .orElse(null);

        log.info("[approval] tool={}, nodeId={}", toolName, nodeId);

        return new ApprovalContext(sessionId, toolName, toolArgs, agentText, reason,
                (approved, always) -> {
                    try {
                        if (always && approved) {
                            approvalService.addSessionAllowedTool(sessionId, toolName);
                        }
                        Map<String, Object> update = new HashMap<>();
                        update.put(ReActAgentState.APPROVAL_RESULT, approved ? "APPROVED" : "REJECTED");
                        update.put(ReActAgentState.SESSION_ALLOWED_TOOLS,
                                approvalService.getSessionAllowedTools(sessionId));
                        graph.updateState(config, update, nodeId);
                    } catch (Exception e) {
                        log.error("Failed to write approval state", e);
                    }
                });
    }

    private ClarifyContext buildClarifyContext(
            CompiledGraph<ReActAgentState> graph,
            RunnableConfig config,
            String sessionId,
            InterruptionMetadata<ReActAgentState> meta,
            ReActAgentState state) {

        final String nodeId = meta.nodeId();
        String question = meta.metadata("question")
                .map(Object::toString)
                .orElse("");

        @SuppressWarnings("unchecked")
        List<String> options = meta.metadata("options")
                .map(value -> value instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.<String>of())
                .orElse(List.of());

        String agentText = extractLastAssistantText(state);
        log.info("[clarify] question={}, nodeId={}", question, nodeId);

        return new ClarifyContext(sessionId, question, options, agentText,
                answer -> {
                    try {
                        Map<String, Object> update = new HashMap<>();
                        update.put(ReActAgentState.CLARIFY_RESULT, answer != null ? answer : "");
                        graph.updateState(config, update, nodeId);
                    } catch (Exception e) {
                        log.error("Failed to write clarification answer", e);
                    }
                });
    }

    private String extractLastAssistantText(ReActAgentState state) {
        if (state == null) return null;
        List<Message> msgs = state.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m instanceof AssistantMessage am
                    && am.getText() != null && !am.getText().isBlank()) {
                return am.getText();
            }
        }
        return null;
    }
}
