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

/**
 * 审批节点：根据工具队列头部的调用动态解析工具定义并决定是否挂起。
 *
 * <p>实现了 {@link InterruptableAction} 接口，框架在进入此节点前会调用
 * {@link #interrupt}：</p>
 * <ol>
 *   <li>先通过 HookRegistry 触发 APPROVAL_BEFORE Hook，若 Hook 自动审批/拒绝则直接返回</li>
 *   <li>调用工具自身的 {@code approvalChecker}（即 {@link ToolDefinition#checkApproval}）：
 *       返回 null 表示本次参数无需审批（如 {@code ls}），直接不挂起并注入 APPROVED</li>
 *   <li>若 APPROVAL_RESULT 为空（尚未审批），返回 InterruptionMetadata（图挂起，线程释放）</li>
 *   <li>若 APPROVAL_RESULT 有值（已 resume），返回 empty()（执行 approvalAction 边路由）</li>
 * </ol>
 */
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
        log.debug("[{}] 无需审批（白名单或 approvalChecker 放行），注入 APPROVED", current.name());
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
            // Hook 自动拒绝
            log.info("[{}] Hook 拒绝审批: {}", nodeId, beforeResult.blockReason());
            return Optional.empty();
        }
        String autoDecision = beforeResult.getModification(HookDataKeys.APPROVAL_DECISION, String.class);
        if ("approved".equals(autoDecision)) {
            log.debug("[{}] Hook 自动审批通过", nodeId);
            return Optional.empty();
        }

        // 1. 检查是否已有审批结果（resume 后不再挂起）
        if (state.approvalResult().isPresent()) {
            log.debug("[{}] APPROVAL_RESULT={}, 不挂起", nodeId, state.approvalResult().get());
            return Optional.empty();
        }

        // 2. 委托工具自身的 approvalChecker 判断本次调用是否真的需要审批
        Map<String, Object> parsedArgs = parseArgs(current.name(), toolArgs);

        ApprovalRequest approvalRequest = current.definition().checkApproval(parsedArgs);
        if (approvalRequest == null) {
            log.debug("[{}] approvalChecker 判定无需审批，跳过 interrupt", nodeId);
            return Optional.empty();
        }

        // 3. 需要审批，挂起图，携带工具名/参数/原因供 TUI 展示
        log.info("[{}] 工具 {} 需要审批（原因：{}），图挂起", nodeId, current.name(), approvalRequest.reason());

        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .putMetadata(ApprovalInfo.TOOL_NAME_KEY, current.name())
                .putMetadata(ApprovalInfo.TOOL_ARGS_KEY, toolArgs)
                .putMetadata(ApprovalInfo.TOOL_REASON_KEY, approvalRequest.reason())
                .build());
    }

    private CurrentTool currentTool(ReActAgentState state) {
        List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
        if (requests.isEmpty()) {
            throw new IllegalStateException("审批节点执行时工具队列为空");
        }
        AssistantMessage.ToolCall call = requests.get(0);
        ToolDefinition definition = toolDefinitionMap.get(call.name());
        if (definition == null) {
            throw new IllegalStateException("审批节点找不到工具定义: " + call.name());
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
            log.warn("[{}] 解析工具参数失败，使用空 map: {}", toolName, e.getMessage());
            return Map.of();
        }
    }

    private record CurrentTool(String name, AssistantMessage.ToolCall call, ToolDefinition definition) {
    }
}
