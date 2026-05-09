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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * parallel_executor 节点：将当前轮所有工具调用分为安全组和审批组。
 * 安全工具用 CompletableFuture 并发执行，结果批量追加到 messages；
 * 审批工具写回 TOOL_EXECUTION_REQUESTS 队列，交由 action_dispatcher 串行审批。
 */
@Slf4j
public class ParallelExecutorNode {

    private final SpringAIToolService toolService;
    private final Set<String> approvalToolNames;
    private final Set<String> interruptToolNames;
    private final Map<String, ToolDefinition> toolDefinitionMap;
    private final int maxToolRetries;
    private final int defaultToolTimeoutSeconds;
    private final HookRegistry hookRegistry;
    private final ExecutorService toolExecutor;

    public ParallelExecutorNode(Dependencies dependencies) {
        this.toolService       = dependencies.toolService();
        this.approvalToolNames = dependencies.approvalToolNames();
        this.interruptToolNames = dependencies.interruptToolNames() != null ? dependencies.interruptToolNames() : Set.of();
        this.toolDefinitionMap = dependencies.toolDefinitionMap();
        this.maxToolRetries    = dependencies.maxToolRetries();
        this.defaultToolTimeoutSeconds = dependencies.defaultToolTimeoutSeconds();
        this.hookRegistry      = dependencies.hookRegistry();
        this.toolExecutor      = dependencies.toolExecutor();
    }

    public record Dependencies(SpringAIToolService toolService,
                               Set<String> approvalToolNames,
                               Set<String> interruptToolNames,
                               Map<String, ToolDefinition> toolDefinitionMap,
                               int maxToolRetries,
                               int defaultToolTimeoutSeconds,
                               HookRegistry hookRegistry,
                               ExecutorService toolExecutor) {
    }

    public AsyncNodeActionWithConfig<ReActAgentState> build() {
        return (state, config) -> {
            List<AssistantMessage.ToolCall> allCalls = state.toolExecutionRequests();
            if (allCalls.isEmpty()) {
                allCalls = state.loadToolCallsFromLastMessage().orElse(List.of());
            }
            if (allCalls.isEmpty()) {
                log.warn("[parallel_executor] 无工具调用，直接回 model");
                return completedFuture(Map.of(
                        ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(),
                        ReActAgentState.LAST_TOOL_FAILED, false));
            }

            String sessionId = config != null ? config.threadId().orElse(null) : null;

            List<AssistantMessage.ToolCall> safeTools     = new ArrayList<>();
            List<AssistantMessage.ToolCall> approvalTools = new ArrayList<>();
            for (AssistantMessage.ToolCall call : allCalls) {
                if (interruptToolNames.contains(call.name())
                        || GraphUtils.requiresApproval(call, approvalToolNames, toolDefinitionMap)) {
                    approvalTools.add(call);
                } else {
                    safeTools.add(call);
                }
            }
            log.info("[parallel_executor] 工具总数={}, 安全={}, 审批={}",
                    allCalls.size(), safeTools.size(), approvalTools.size());

            if (safeTools.isEmpty()) {
                return completedFuture(Map.of(
                        ReActAgentState.TOOL_EXECUTION_REQUESTS, approvalTools,
                        ReActAgentState.LAST_TOOL_FAILED, false));
            }

            Map<String, Object> stateData = state.data();
            Duration defaultTimeout = Duration.ofSeconds(Math.max(1, defaultToolTimeoutSeconds));

            CompletableFuture<ToolResponseMessage.ToolResponse>[] futures = safeTools.stream()
                    .map(call -> {
                        Duration timeout = resolveToolTimeout(call, defaultTimeout);
                        return CompletableFuture.supplyAsync(() -> executeToolCall(call, stateData, sessionId), toolExecutor)
                                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                                .exceptionally(ex -> exceptionResponse(call, ex, timeout));
                    })
                    .toArray(CompletableFuture[]::new);

            final List<AssistantMessage.ToolCall> finalApprovalTools = approvalTools;
            final List<AssistantMessage.ToolCall> finalSafeTools     = safeTools;

            return CompletableFuture.allOf(futures).handle((v, ex) -> {
                if (ex != null) {
                    log.error("[parallel_executor] 并发执行异常", ex);
                    Map<String, Object> update = new HashMap<>();
                    update.put(ReActAgentState.TOOL_EXECUTION_REQUESTS, finalApprovalTools);
                    update.put(ReActAgentState.LAST_TOOL_FAILED, true);
                    update.put(ReActAgentState.TOOL_ERROR_HISTORY, "parallel_executor 异常: " + ex.getMessage());
                    return update;
                }

                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                boolean anyFailed = false;
                Map<String, Integer> retryCounts = new HashMap<>(state.toolRetryCounts());
                List<String> errorEntries = new ArrayList<>();

                for (int i = 0; i < futures.length; i++) {
                    ToolResponseMessage.ToolResponse resp = futures[i].join();
                    responses.add(resp);
                    String responseData = resp.responseData();
                    if (responseData != null && responseData.contains("\"error\":true")) {
                        anyFailed = true;
                        String name = finalSafeTools.get(i).name();
                        int n = retryCounts.getOrDefault(name, 0) + 1;
                        retryCounts.put(name, n);
                        errorEntries.add(name + "(第" + n + "次失败): " + responseData);
                        log.info("[parallel_executor] 工具 {} 失败（第 {}/{} 次）", name, n, maxToolRetries);
                    }
                }

                ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                        .responses(responses)
                        .build();

                Map<String, Object> update = new HashMap<>();
                update.put("messages", toolResponseMessage);
                update.put(ReActAgentState.TOOL_EXECUTION_REQUESTS, finalApprovalTools);
                update.put(ReActAgentState.APPROVAL_RESULT, "");
                update.put(ReActAgentState.LAST_TOOL_FAILED, anyFailed);
                if (anyFailed) {
                    update.put(ReActAgentState.TOOL_RETRY_COUNTS, retryCounts);
                    update.put(ReActAgentState.TOOL_ERROR_HISTORY, String.join("; ", errorEntries));
                }
                return update;
            });
        };
    }

    private ToolResponseMessage.ToolResponse executeToolCall(AssistantMessage.ToolCall call,
                                                             Map<String, Object> stateData,
                                                             String sessionId) {
        String argsPreview = GraphUtils.truncate(call.arguments(), 200);

        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put(HookDataKeys.TOOL_NAME, call.name());
        beforeData.put(HookDataKeys.TOOL_ARGS, call.arguments());
        beforeData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
        beforeData.put(HookDataKeys.TOOL_CALL_ID, call.id());
        HookResult beforeResult = hookRegistry.fireBefore(HookEvent.TOOL_CALL_BEFORE, sessionId, beforeData);
        if (!beforeResult.allowed()) {
            log.info("[parallel] Hook 阻塞 {}: {}", call.name(), beforeResult.blockReason());
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    "{\"error\":true,\"message\":\"" + escapeJson(beforeResult.blockReason()) + "\"}");
        }

        Map<String, Object> startData = new HashMap<>();
        startData.put(HookDataKeys.TOOL_NAME, call.name());
        startData.put(HookDataKeys.TOOL_ARGS, call.arguments());
        startData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
        startData.put(HookDataKeys.TOOL_CALL_ID, call.id());
        hookRegistry.fireAfter(HookEvent.TOOL_CALL_START, sessionId, startData);

        long start = System.currentTimeMillis();
        ToolResponseMessage.ToolResponse resp = GraphUtils.executeSingleToolCall(toolService, call, stateData);
        long duration = System.currentTimeMillis() - start;
        boolean failed = resp.responseData() != null && resp.responseData().contains("\"error\":true");

        Map<String, Object> afterData = new HashMap<>();
        afterData.put(HookDataKeys.TOOL_NAME, call.name());
        afterData.put(HookDataKeys.TOOL_ARGS, call.arguments());
        afterData.put(HookDataKeys.TOOL_ARGS_PREVIEW, argsPreview);
        afterData.put(HookDataKeys.TOOL_CALL_ID, call.id());
        afterData.put(HookDataKeys.TOOL_DURATION_MS, duration);
        afterData.put(HookDataKeys.TOOL_STATUS, failed ? "failed" : "completed");
        afterData.put(HookDataKeys.TOOL_ERROR, failed ? resp.responseData() : null);
        afterData.put(HookDataKeys.TOOL_RESULT, resp.responseData());
        hookRegistry.fireAfter(HookEvent.TOOL_CALL_AFTER, sessionId, afterData);

        return resp;
    }

    private Duration resolveToolTimeout(AssistantMessage.ToolCall call, Duration defaultTimeout) {
        ToolDefinition definition = toolDefinitionMap.get(call.name());
        if (definition == null) {
            return defaultTimeout;
        }
        try {
            return definition.resolveTimeout(GraphUtils.parseArguments(call.arguments()), defaultTimeout);
        } catch (Exception e) {
            log.warn("[parallel_executor] 工具 {} 超时配置解析失败，使用默认 {}s: {}",
                    call.name(), defaultTimeout.toSeconds(), e.getMessage());
            return defaultTimeout;
        }
    }

    private ToolResponseMessage.ToolResponse timeoutResponse(AssistantMessage.ToolCall call, Duration timeout) {
        String message = "Tool '" + call.name() + "' timed out after " + timeout.toSeconds() + " seconds";
        log.warn("[parallel_executor] {}", message);
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                "{\"error\":true,\"message\":\"" + escapeJson(message) + "\"}");
    }

    private ToolResponseMessage.ToolResponse exceptionResponse(AssistantMessage.ToolCall call, Throwable ex, Duration timeout) {
        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof TimeoutException) {
            return timeoutResponse(call, timeout);
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                "{\"error\":true,\"message\":\"" + escapeJson(message) + "\"}");
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
