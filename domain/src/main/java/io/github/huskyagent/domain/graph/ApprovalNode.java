package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.infra.tool.approval.ApprovalInfo;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.InterruptableAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Slf4j
class ApprovalNode
        implements AsyncNodeActionWithConfig<ReActAgentState>, InterruptableAction<ReActAgentState> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Map<String, ToolDefinition> toolDefinitionMap;
    private final HookRegistry hookRegistry;

    ApprovalNode(Map<String, ToolDefinition> toolDefinitionMap, HookRegistry hookRegistry) {
        this.toolDefinitionMap = toolDefinitionMap;
        this.hookRegistry = hookRegistry;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(
            ReActAgentState state, RunnableConfig config) {

        // ── APPROVAL_AFTER Hook ──────────────────────────────────────────────
        String sessionId = config != null ? config.threadId().orElse(null) : null;
        String decision = state.approvalResult().orElse("");
        if (!decision.isEmpty()) {
            currentToolIfPresent(state).ifPresent(current -> hookRegistry.fireAfter(HookEvent.APPROVAL_AFTER, sessionId,
                    Map.of(HookDataKeys.TOOL_NAME, current.name(),
                           HookDataKeys.APPROVAL_DECISION, decision)));
            return completedFuture(Map.of());
        }

        CurrentTool current = currentTool(state);
        log.debug("[{}] Approval not required; allowlist or approvalChecker allowed it, injecting APPROVED", current.name());
        return completedFuture(Map.of(ReActAgentState.APPROVAL_RESULT, "APPROVED"));
    }

    @Override
    public Optional<InterruptionMetadata<ReActAgentState>> interrupt(
            String nodeId, ReActAgentState state, RunnableConfig config) {

        String sessionId = config != null ? config.threadId().orElse(null) : null;

        // ── APPROVAL_BEFORE Hook ──────────────────────────────────────────────
        CurrentTool current = currentTool(state);
        String toolArgs = current.call().arguments();

        HookResult beforeResult = hookRegistry.fireBefore(
                HookEvent.APPROVAL_BEFORE, sessionId,
                Map.of(HookDataKeys.TOOL_NAME, current.name(),
                       HookDataKeys.TOOL_ARGS, toolArgs));
        if (!beforeResult.allowed()) {
            log.info("[{}] Hook rejected approval: {}", nodeId, beforeResult.blockReason());
            return Optional.empty();
        }
        String autoDecision = beforeResult.getModification(HookDataKeys.APPROVAL_DECISION, String.class);
        if ("approved".equals(autoDecision)) {
            log.debug("[{}] Hook auto-approved", nodeId);
            return Optional.empty();
        }

        if (state.approvalResult().isPresent()) {
            log.debug("[{}] APPROVAL_RESULT={}, not suspending", nodeId, state.approvalResult().get());
            return Optional.empty();
        }

        Map<String, Object> parsedArgs = parseArgs(current.name(), toolArgs);

        ApprovalRequest approvalRequest = current.definition().checkApproval(parsedArgs);
        if (approvalRequest == null) {
            log.debug("[{}] approvalChecker determined approval is not required; skipping interrupt", nodeId);
            return Optional.empty();
        }

        log.info("[{}] Tool {} requires approval (reason: {}); suspending graph", nodeId, current.name(), approvalRequest.reason());

        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .putMetadata(ApprovalInfo.TOOL_NAME_KEY, current.name())
                .putMetadata(ApprovalInfo.TOOL_ARGS_KEY, toolArgs)
                .putMetadata(ApprovalInfo.TOOL_REASON_KEY, approvalRequest.reason())
                .build());
    }

    private CurrentTool currentTool(ReActAgentState state) {
        List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
        if (requests.isEmpty()) {
            throw new IllegalStateException("Approval node executed with an empty tool queue");
        }
        AssistantMessage.ToolCall call = requests.get(0);
        ToolDefinition definition = toolDefinitionMap.get(call.name());
        if (definition == null) {
            throw new IllegalStateException("Approval node could not find tool definition: " + call.name());
        }
        return new CurrentTool(call.name(), call, definition);
    }

    private Optional<CurrentTool> currentToolIfPresent(ReActAgentState state) {
        List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
        if (requests.isEmpty()) {
            return Optional.empty();
        }
        AssistantMessage.ToolCall call = requests.get(0);
        ToolDefinition definition = toolDefinitionMap.get(call.name());
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(new CurrentTool(call.name(), call, definition));
    }

    private Map<String, Object> parseArgs(String toolName, String argsJson) {
        try {
            return OBJECT_MAPPER.readValue(argsJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("[{}] Failed to parse tool arguments; using an empty map: {}", toolName, e.getMessage());
            return Map.of();
        }
    }

    private record CurrentTool(String name, AssistantMessage.ToolCall call, ToolDefinition definition) {
    }
}
