package io.github.huskyagent.infra.tool.executor;

import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component("toolExecutorService")
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ExecutorService executorService;

    /** Tracks background tool tasks by task id for polling, waiting, and cancellation. */
    private final Map<String, Future<ToolResult>> backgroundTasks = new ConcurrentHashMap<>();

    public ToolExecutor(ToolRegistry registry,
                        @Qualifier("toolExecutor") ExecutorService executorService) {
        this.registry = registry;
        this.executorService = executorService;
    }

    public ToolResult execute(String name, Map<String, Object> args) {
        return execute(name, args, ToolExecutionContext.minimal(null, registry.getAll()));
    }

    public ToolResult execute(String name, Map<String, Object> args, ToolExecutionContext executionContext) {
        ToolDefinition tool = registry.get(name);
        if (tool == null) {
            log.warn("Tool '{}' not found", name);
            return ToolResult.failure("Tool not found: " + name);
        }

        if (!tool.enabled()) {
            log.warn("Tool '{}' is disabled", name);
            return ToolResult.failure("Tool disabled: " + name);
        }

        try {
            log.debug("Executing tool '{}' with args: {}", name, args);
            ToolExecutionContext effectiveContext = executionContext != null
                    ? executionContext
                    : ToolExecutionContext.minimal(null, registry.getAll());
            ToolResult result = tool.execute(args, effectiveContext);

            if (tool.maxResultSizeChars() < Integer.MAX_VALUE && result.content() != null) {
                result = result.truncate(tool.maxResultSizeChars());
            }

            log.debug("Tool '{}' completed: success={}", name, result.success());
            return result;

        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", name, e.getMessage(), e);
            return ToolResult.failure("Execution error: " + e.getMessage());
        }
    }

    public Future<ToolResult> executeAsync(String name, Map<String, Object> args, String taskId) {
        Future<ToolResult> future = executorService.submit(() -> execute(name, args));
        backgroundTasks.put(taskId, future);
        log.info("Started background task '{}' for tool '{}'", taskId, name);
        return future;
    }

    public TaskStatus getTaskStatus(String taskId) {
        Future<ToolResult> future = backgroundTasks.get(taskId);
        if (future == null) {
            return TaskStatus.NOT_FOUND;
        }
        if (future.isDone()) {
            return TaskStatus.COMPLETED;
        }
        if (future.isCancelled()) {
            return TaskStatus.CANCELLED;
        }
        return TaskStatus.RUNNING;
    }

    public ToolResult getTaskResult(String taskId) {
        return waitTask(taskId, 300);
    }

    public ToolResult waitTask(String taskId, int timeout) {
        Future<ToolResult> future = backgroundTasks.get(taskId);
        if (future == null) {
            return ToolResult.failure("Task not found: " + taskId);
        }
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ToolResult.failure("Task timeout after " + timeout + " seconds");
        } catch (InterruptedException | ExecutionException e) {
            return ToolResult.failure("Task execution failed: " + e.getMessage());
        }
    }

    public boolean cancelTask(String taskId) {
        Future<ToolResult> future = backgroundTasks.get(taskId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            backgroundTasks.remove(taskId);
            log.info("Cancelled task '{}'", taskId);
        }
        return cancelled;
    }

    public void cleanupCompletedTasks() {
        backgroundTasks.entrySet().removeIf(e -> e.getValue().isDone() || e.getValue().isCancelled());
    }

    public enum TaskStatus {
        NOT_FOUND,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}