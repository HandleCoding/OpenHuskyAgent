package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

/**
 * 审批工具执行节点：动态解析并执行队列头部的工具调用，弹出队列，追加 ToolResponseMessage。
 */
@Slf4j
public class ExecuteToolNode {

    private final SpringAIToolService toolService;
    private final Map<String, ToolDefinition> toolDefinitionMap;
    private final int maxToolRetries;
    private final int defaultToolTimeoutSeconds;
    private final HookRegistry hookRegistry;

    public ExecuteToolNode(Dependencies dependencies) {
        this.toolService = dependencies.toolService();
        this.toolDefinitionMap = dependencies.toolDefinitionMap();
        this.maxToolRetries = dependencies.maxToolRetries();
        this.defaultToolTimeoutSeconds = dependencies.defaultToolTimeoutSeconds();
        this.hookRegistry = dependencies.hookRegistry();
    }

    public record Dependencies(SpringAIToolService toolService,
                               Map<String, ToolDefinition> toolDefinitionMap,
                               int maxToolRetries,
                               int defaultToolTimeoutSeconds,
                               HookRegistry hookRegistry) {
    }

    public AsyncNodeActionWithConfig<ReActAgentState> build() {
        return (state, config) -> {
            List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
            if (requests.isEmpty()) {
                return failedFuture(new IllegalStateException("工具队列为空，无法执行"));
            }
            AssistantMessage.ToolCall toolCall = requests.get(0);
            ToolDefinition toolDefinition = toolDefinitionMap.get(toolCall.name());
            if (toolDefinition == null) {
                return failedFuture(new IllegalStateException("执行节点找不到工具定义: " + toolCall.name()));
            }
            String toolName = toolCall.name();
            String argsPreview = GraphUtils.truncate(toolCall.arguments(), 200);
            log.debug("[{}] 执行工具调用，args={}", toolName, argsPreview);

            String sessionId = config != null ? config.threadId().orElse(null) : null;

            // ── TOOL_CALL_BEFORE Hook ────────────────────────────────────────────
            Map<String, Object> beforeData = new HashMap<>();
            beforeData.put(HookDataKeys.TOOL_NAME, toolName);
            beforeData.put(HookDataKeys.TOOL_ARGS, toolCall.arguments());
            beforeData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
            beforeData.put(HookDataKeys.TOOL_CALL_ID, toolCall.id());
            HookResult beforeResult = hookRegistry.fireBefore(
                    HookEvent.TOOL_CALL_BEFORE, sessionId, beforeData);
            if (!beforeResult.allowed()) {
                log.info("[{}] Hook 阻塞: {}", toolName, beforeResult.blockReason());
                Map<String, Object> update = new HashMap<>();
                update.put("messages", GraphUtils.buildToolResponseMessage(
                        toolCall.id(), toolName,
                        "{\"error\":true,\"message\":\"" + beforeResult.blockReason() + "\"}"));
                update.put(ReActAgentState.TOOL_EXECUTION_REQUESTS,
                        state.toolExecutionRequestsWithoutFirst());
                update.put(ReActAgentState.APPROVAL_RESULT, "");
                update.put(ReActAgentState.LAST_TOOL_FAILED, true);
                Map<String, Integer> counts = new HashMap<>(state.toolRetryCounts());
                int n = counts.getOrDefault(toolName, 0) + 1;
                counts.put(toolName, n);
                update.put(ReActAgentState.TOOL_RETRY_COUNTS, counts);
                update.put(ReActAgentState.TOOL_ERROR_HISTORY,
                        toolName + "(第" + n + "次): " + beforeResult.blockReason());
                return completedFuture(update);
            }

            // ── TOOL_CALL_START Hook (notification) ─────────────────────────────
            Map<String, Object> startData = new HashMap<>();
            startData.put(HookDataKeys.TOOL_NAME, toolName);
            startData.put(HookDataKeys.TOOL_ARGS, toolCall.arguments());
            startData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
            startData.put(HookDataKeys.TOOL_CALL_ID, toolCall.id());
            hookRegistry.fireAfter(HookEvent.TOOL_CALL_START, sessionId, startData);

            long startTime = System.currentTimeMillis();
            Duration timeout = resolveToolTimeout(toolName, toolDefinition, toolCall,
                    Duration.ofSeconds(Math.max(1, defaultToolTimeoutSeconds)));

            return toolService.executeFunctions(List.of(toolCall), state.data(), "messages")
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((command, ex) -> {
                        long duration = System.currentTimeMillis() - startTime;
                        Map<String, Object> update;
                        String toolResponse;
                        if (ex != null) {
                            ToolResponseMessage message = GraphUtils.buildToolResponseMessage(
                                    toolCall.id(), toolName, exceptionResponseData(toolName, ex, timeout));
                            update = new HashMap<>();
                            update.put("messages", message);
                            toolResponse = GraphUtils.extractToolResponseContent(update);
                        } else {
                            update = new HashMap<>(command.update());
                            toolResponse = GraphUtils.extractToolResponseContent(command.update());
                        }

                        update.put(ReActAgentState.TOOL_EXECUTION_REQUESTS,
                                state.toolExecutionRequestsWithoutFirst());
                        update.put(ReActAgentState.APPROVAL_RESULT, "");

                        boolean failed = toolResponse != null && toolResponse.contains("\"error\":true");

                        Map<String, Object> afterData = new HashMap<>();
                        afterData.put(HookDataKeys.TOOL_NAME, toolName);
                        afterData.put(HookDataKeys.TOOL_ARGS, toolCall.arguments());
                        afterData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
                        afterData.put(HookDataKeys.TOOL_DURATION_MS, duration);
                        afterData.put(HookDataKeys.TOOL_CALL_ID, toolCall.id());
                        afterData.put(HookDataKeys.TOOL_STATUS, failed ? "failed" : "completed");
                        afterData.put(HookDataKeys.TOOL_ERROR, failed ? toolResponse : null);
                        afterData.put(HookDataKeys.TOOL_RESULT, toolResponse);
                        hookRegistry.fireAfter(HookEvent.TOOL_CALL_AFTER, sessionId, afterData);

                        if (failed) {
                            Map<String, Integer> counts = new HashMap<>(state.toolRetryCounts());
                            int n = counts.getOrDefault(toolName, 0) + 1;
                            counts.put(toolName, n);
                            update.put(ReActAgentState.TOOL_RETRY_COUNTS, counts);
                            update.put(ReActAgentState.TOOL_ERROR_HISTORY,
                                    toolName + "(第" + n + "次失败): " + toolResponse);
                            update.put(ReActAgentState.LAST_TOOL_FAILED, true);
                            log.info("[{}] 工具失败（第 {}/{} 次），回 model 反思", toolName, n, maxToolRetries);
                        } else {
                            update.put(ReActAgentState.LAST_TOOL_FAILED, false);
                        }
                        return update;
                    });
        };
    }
    private Duration resolveToolTimeout(String toolName, ToolDefinition toolDefinition,
                                        AssistantMessage.ToolCall call, Duration defaultTimeout) {
        try {
            return toolDefinition.resolveTimeout(GraphUtils.parseArguments(call.arguments()), defaultTimeout);
        } catch (Exception e) {
            log.warn("[{}] 工具超时配置解析失败，使用默认 {}s: {}",
                    toolName, defaultTimeout.toSeconds(), e.getMessage());
            return defaultTimeout;
        }
    }

    private String exceptionResponseData(String toolName, Throwable ex, Duration timeout) {
        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        String message;
        if (cause instanceof TimeoutException) {
            message = "Tool '" + toolName + "' timed out after " + timeout.toSeconds() + " seconds";
            log.warn("[{}] {}", toolName, message);
        } else {
            message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }
        return "{\"error\":true,\"message\":\"" + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
