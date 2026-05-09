package io.github.huskyagent.application.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.huskyagent.application.runtime.CapabilityVisibilityResolver;
import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.domain.subagent.SubAgentMessage;
import io.github.huskyagent.domain.subagent.SubAgentMessageQueue;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DelegateTaskTool implements ToolProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<AgentGraph> agentGraphProvider;
    private final SessionManager sessionManager;
    private final HookRegistry hookRegistry;
    private final SubAgentConfig subAgentConfig;
    private final CapabilityVisibilityResolver capabilityVisibilityResolver;
    private final RuntimePolicyResolver runtimePolicyResolver;
    private final DynamicPromptSnapshotCache dynamicPromptSnapshotCache;

    public DelegateTaskTool(ObjectProvider<AgentGraph> agentGraphProvider,
                            SessionManager sessionManager,
                            HookRegistry hookRegistry,
                            SubAgentConfig subAgentConfig,
                            CapabilityVisibilityResolver capabilityVisibilityResolver,
                            RuntimePolicyResolver runtimePolicyResolver,
                            DynamicPromptSnapshotCache dynamicPromptSnapshotCache) {
        this.agentGraphProvider = agentGraphProvider;
        this.sessionManager = sessionManager;
        this.hookRegistry = hookRegistry;
        this.subAgentConfig = subAgentConfig;
        this.capabilityVisibilityResolver = capabilityVisibilityResolver;
        this.runtimePolicyResolver = runtimePolicyResolver;
        this.dynamicPromptSnapshotCache = dynamicPromptSnapshotCache;
    }

    @Override
    public List<ToolDefinition> getTools() {
        if (!subAgentConfig.isEnabled()) {
            log.info("Sub-agent delegation is disabled; delegate_task will not be registered");
            return List.of();
        }
        return List.of(createDelegateTaskDefinition());
    }

    private ToolDefinition createDelegateTaskDefinition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode goalProp = MAPPER.createObjectNode();
        goalProp.put("type", "string");
        goalProp.put("description", "Task description for a single subagent (use 'tasks' for parallel execution)");
        props.set("goal", goalProp);

        ObjectNode contextProp = MAPPER.createObjectNode();
        contextProp.put("type", "string");
        contextProp.put("description", "Additional context from the parent conversation to help the subagent");
        props.set("context", contextProp);

        ObjectNode tasksProp = MAPPER.createObjectNode();
        tasksProp.put("type", "array");
        tasksProp.put("description", "List of tasks for parallel subagent execution. Each task runs in its own isolated session.");
        ObjectNode taskItem = MAPPER.createObjectNode();
        taskItem.put("type", "object");
        ObjectNode taskItemProps = MAPPER.createObjectNode();
        ObjectNode taskGoal = MAPPER.createObjectNode();
        taskGoal.put("type", "string");
        taskGoal.put("description", "Goal for this subagent");
        taskItemProps.set("goal", taskGoal);
        ObjectNode taskContext = MAPPER.createObjectNode();
        taskContext.put("type", "string");
        taskContext.put("description", "Context for this subagent");
        taskItemProps.set("context", taskContext);
        taskItem.set("properties", taskItemProps);
        ArrayNode taskRequired = MAPPER.createArrayNode();
        taskRequired.add("goal");
        taskItem.set("required", taskRequired);
        tasksProp.set("items", taskItem);
        props.set("tasks", tasksProp);

        ObjectNode toolsetsProp = MAPPER.createObjectNode();
        toolsetsProp.put("type", "array");
        ObjectNode items = MAPPER.createObjectNode();
        items.put("type", "string");
        toolsetsProp.set("items", items);
        toolsetsProp.put("description",
                "A flat array of tool category names (strings) shared by ALL subagents. "
                + "Example: [\"CORE\", \"SEARCH\", \"WEB\"]. "
                + "Do NOT nest objects inside — each element must be a plain string. "
                + "Default: all toolsets except DELEGATE and MEMORY. "
                + "Available options: CORE, TERMINAL, SEARCH, WEB, BROWSER, MEMORY, KNOWLEDGE, "
                + "EXECUTE, DELEGATE, SKILLS, MCP, BUSINESS, VISION");
        props.set("required_toolsets", toolsetsProp);

        ObjectNode maxStepsProp = MAPPER.createObjectNode();
        maxStepsProp.put("type", "integer");
        maxStepsProp.put("description", "Max tool-calling turns per subagent (default: " + subAgentConfig.getMaxIterations() + ")");
        props.set("max_steps", maxStepsProp);

        ObjectNode timeoutProp = MAPPER.createObjectNode();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Total timeout in seconds (default: " + subAgentConfig.getChildTimeoutSeconds() + ")");
        props.set("timeout_seconds", timeoutProp);

        schema.set("properties", props);

        return ToolDefinition.contextual(
                "delegate_task",
                "Delegates task(s) to focused subagents with isolated context and restricted tools. "
                + "Single task: pass 'goal'. Parallel tasks: pass 'tasks' array (up to "
                + subAgentConfig.getMaxConcurrentChildren() + " concurrent). "
                + "Use timeout_seconds when the task is expected to need more or less time; "
                + "default is " + subAgentConfig.getChildTimeoutSeconds() + " seconds. "
                + "Each subagent runs in its own session and returns a structured result.",
                Toolset.DELEGATE, schema, this::handleDelegateTask)
                .withTimeout(this::resolveExecutionTimeout);
    }

    private Duration resolveExecutionTimeout(Map<String, Object> args) {
        long timeoutSeconds = args.get("timeout_seconds") instanceof Number n
                ? n.longValue()
                : subAgentConfig.getChildTimeoutSeconds();
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    private ToolResult handleDelegateTask(Map<String, Object> args, ToolExecutionContext executionContext) {
        List<TaskSpec> taskSpecs = parseTaskSpecs(args);
        if (taskSpecs.isEmpty()) {
            return ToolResult.failure("Either 'goal' or 'tasks' must be provided");
        }

        List<String> requestedToolsets = args.get("required_toolsets") instanceof List<?> rawList
                ? rawList.stream()
                        .filter(Objects::nonNull)
                        .map(item -> item instanceof Map<?, ?> map
                                ? Optional.ofNullable(map.get("toolsets"))
                                        .filter(v -> v instanceof List<?>)
                                        .map(v -> ((List<?>) v).stream()
                                                .filter(Objects::nonNull)
                                                .map(Object::toString)
                                                .collect(Collectors.joining(",")))
                                        .orElse(item.toString())
                                : item.toString())
                        .flatMap(s -> Arrays.stream(s.split(",")))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList())
                : List.of();

        int maxSteps = args.containsKey("max_steps") && args.get("max_steps") instanceof Number
                ? ((Number) args.get("max_steps")).intValue()
                : subAgentConfig.getMaxIterations();

        long timeoutSeconds = args.containsKey("timeout_seconds") && args.get("timeout_seconds") instanceof Number
                ? ((Number) args.get("timeout_seconds")).longValue()
                : subAgentConfig.getChildTimeoutSeconds();

        Set<Toolset> allowedToolsets = resolveAllowedToolsets(requestedToolsets);

        String parentSessionId = args.get(ToolCallbackFactory.SESSION_ID_KEY) instanceof String
                ? (String) args.get(ToolCallbackFactory.SESSION_ID_KEY)
                : "unknown";

        List<ToolDefinition> visibleTools = executionContext.visibleTools();

        Path workingDir = Path.of(System.getProperty("user.dir"));

        List<SubAgentTask> tasks = new ArrayList<>();
        for (int i = 0; i < taskSpecs.size(); i++) {
            TaskSpec spec = taskSpecs.get(i);
            tasks.add(new SubAgentTask(
                    spec.goal, spec.context, allowedToolsets, maxSteps, timeoutSeconds, workingDir, i));
        }

        // fire SUBAGENT_START hook
        hookRegistry.fireAfter(HookEvent.SUBAGENT_START, parentSessionId,
                Map.of(HookDataKeys.SUBAGENT_GOAL,
                        tasks.size() == 1 ? tasks.get(0).goal() : tasks.size() + " parallel tasks"));

        log.info("Starting sub-agents: {} tasks, tools={}, timeout={}s",
                tasks.size(), allowedToolsets, timeoutSeconds);

        long startTime = System.currentTimeMillis();

        List<SubAgentResult> results;
        if (tasks.size() == 1) {
            results = executeSingle(tasks.get(0), parentSessionId, timeoutSeconds, executionContext);
        } else {
            results = executeParallel(tasks, parentSessionId, timeoutSeconds, executionContext);
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // fire SUBAGENT_STOP hook
        Map<String, Object> hookData = new HashMap<>();
        hookData.put(HookDataKeys.SUBAGENT_DURATION_MS, totalDurationMs);
        hookData.put(HookDataKeys.SUBAGENT_STATUS, aggregateStatus(results));
        hookRegistry.fireAfter(HookEvent.SUBAGENT_STOP, parentSessionId, hookData);

        log.info("All sub-agents completed: status={}, duration={}ms", aggregateStatus(results), totalDurationMs);

        return buildToolResult(results, totalDurationMs);
    }


    private record TaskSpec(String goal, String context) {}

    private List<TaskSpec> parseTaskSpecs(Map<String, Object> args) {
        List<TaskSpec> specs = new ArrayList<>();

        Object tasksObj = args.get("tasks");
        if (tasksObj instanceof List<?> tasksList && !tasksList.isEmpty()) {
            for (Object item : tasksList) {
                if (item instanceof Map<?, ?> map) {
                    String goal = map.get("goal") instanceof String g ? g : null;
                    if (goal != null && !goal.isBlank()) {
                        String context = map.get("context") instanceof String c ? c : null;
                        specs.add(new TaskSpec(goal, context));
                    }
                }
            }
            return specs;
        }

        String goal = args.get("goal") instanceof String g ? g : null;
        if (goal != null && !goal.isBlank()) {
            String context = args.get("context") instanceof String c ? c : null;
            specs.add(new TaskSpec(goal, context));
        }

        return specs;
    }


    private List<SubAgentResult> executeSingle(SubAgentTask task, String parentSessionId, long timeoutSeconds,
                                               ToolExecutionContext executionContext) {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        SubAgentRunner runner = new SubAgentRunner(
                sessionManager, agentGraphProvider.getObject(), subAgentConfig,
                task, queue, parentSessionId, executionContext,
                capabilityVisibilityResolver, runtimePolicyResolver, dynamicPromptSnapshotCache);

        CompletableFuture<SubAgentMessage> future = CompletableFuture.supplyAsync(runner::run);
        SubAgentMessage finalMsg = pollQueueUntilDone(queue, future, parentSessionId, timeoutSeconds, task.taskIndex());

        List<SubAgentMessage> events = queue.drain();
        return List.of(new SubAgentResult(finalMsg, task.taskIndex(), events));
    }

    private List<SubAgentResult> executeParallel(List<SubAgentTask> tasks, String parentSessionId,
                                                  long timeoutSeconds, ToolExecutionContext executionContext) {
        int maxConcurrent = Math.min(subAgentConfig.getMaxConcurrentChildren(), tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrent);

        List<SubAgentRunner> runners = new ArrayList<>();
        List<SubAgentMessageQueue> queues = new ArrayList<>();
        List<CompletableFuture<SubAgentMessage>> futures = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            SubAgentMessageQueue queue = new SubAgentMessageQueue();
            queues.add(queue);
            SubAgentRunner runner = new SubAgentRunner(
                    sessionManager, agentGraphProvider.getObject(), subAgentConfig,
                    tasks.get(i), queue, parentSessionId, executionContext,
                    capabilityVisibilityResolver, runtimePolicyResolver, dynamicPromptSnapshotCache);
            runners.add(runner);
            final int taskIndex = i;
            futures.add(CompletableFuture.supplyAsync(runner::run, executor)
                    .exceptionally(ex -> {
                        log.error("Sub-agent #{} execution failed", taskIndex, ex);
                        return new SubAgentMessage.Failed(null, ex.getMessage(), List.of(), taskIndex);
                    }));
        }

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        boolean allDone = false;
        while (!allDone && System.currentTimeMillis() < deadline) {
            allDone = true;
            for (int i = 0; i < futures.size(); i++) {
                if (!futures.get(i).isDone()) allDone = false;
                drainQueueProgress(queues.get(i), parentSessionId);
            }
            if (!allDone) {
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
        }

        if (!allDone) {
            log.warn("Parallel sub-agents timed out: timeout={}s", timeoutSeconds);
            for (int i = 0; i < futures.size(); i++) {
                queues.get(i).close();
                if (!futures.get(i).isDone()) futures.get(i).cancel(true);
            }
        }

        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            drainQueueProgress(queues.get(i), parentSessionId);
            SubAgentMessage msg;
            if (futures.get(i).isDone() && !futures.get(i).isCancelled()) {
                try { msg = futures.get(i).getNow(null); }
                catch (Exception e) { msg = null; }
            } else {
                msg = null;
            }
            if (msg == null) msg = new SubAgentMessage.Timeout(null, i);
            results.add(new SubAgentResult(msg, i, queues.get(i).drain()));
        }

        executor.shutdownNow();
        results.sort(Comparator.comparingInt(SubAgentResult::taskIndex));
        return results;
    }


    private SubAgentMessage pollQueueUntilDone(SubAgentMessageQueue queue,
                                                CompletableFuture<SubAgentMessage> future,
                                                String parentSessionId,
                                                long timeoutSeconds, int taskIndex) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;

        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            drainQueueProgress(queue, parentSessionId);
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        if (!future.isDone()) {
            log.warn("Sub-agent execution timed out: taskIndex={}, timeout={}s", taskIndex, timeoutSeconds);
            queue.close();
            future.cancel(true);
            return new SubAgentMessage.Timeout(null, taskIndex);
        }

        try {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Sub-agent get timed out: taskIndex={}", taskIndex);
            queue.close();
            future.cancel(true);
            return new SubAgentMessage.Timeout(null, taskIndex);
        } catch (Exception e) {
            log.error("Sub-agent execution failed: taskIndex={}", taskIndex, e);
            return new SubAgentMessage.Failed(null, e.getMessage(), List.of(), taskIndex);
        }
    }

    private void drainQueueProgress(SubAgentMessageQueue queue, String parentSessionId) {
        List<SubAgentMessage> pending = queue.drain();
        for (SubAgentMessage msg : pending) {
            Map<String, Object> data = buildProgressData(msg);
            hookRegistry.fireAfter(HookEvent.SUBAGENT_PROGRESS, parentSessionId, data);
        }
    }

    private Map<String, Object> buildProgressData(SubAgentMessage msg) {
        Map<String, Object> data = new HashMap<>();
        if (msg instanceof SubAgentMessage.Started s) {
            data.put("progressType", "started");
            data.put(HookDataKeys.SUBAGENT_ID, s.sessionId());
            data.put(HookDataKeys.SUBAGENT_GOAL, s.goal());
            data.put(HookDataKeys.SUBAGENT_DEPTH, s.taskIndex());
        } else if (msg instanceof SubAgentMessage.ToolCallStarted t) {
            data.put("progressType", "tool_started");
            data.put(HookDataKeys.TOOL_NAME, t.toolName());
            data.put(HookDataKeys.TOOL_ARGS_PREVIEW, t.argsPreview());
            data.put(HookDataKeys.SUBAGENT_DEPTH, t.taskIndex());
        } else if (msg instanceof SubAgentMessage.ToolCallCompleted t) {
            data.put("progressType", "tool_completed");
            data.put(HookDataKeys.TOOL_NAME, t.toolName());
            data.put(HookDataKeys.TOOL_DURATION_MS, t.durationMs());
            data.put(HookDataKeys.TOOL_STATUS, t.success() ? "completed" : "failed");
            data.put(HookDataKeys.SUBAGENT_DEPTH, t.taskIndex());
        } else if (msg instanceof SubAgentMessage.Progress p) {
            data.put("progressType", "text");
            data.put("text", p.text());
            data.put(HookDataKeys.SUBAGENT_DEPTH, p.taskIndex());
        } else if (msg instanceof SubAgentMessage.Completed c) {
            data.put("progressType", "completed");
            data.put(HookDataKeys.SUBAGENT_ID, c.sessionId());
            data.put(HookDataKeys.SUBAGENT_SUMMARY, c.summary());
            data.put(HookDataKeys.SUBAGENT_DURATION_MS, c.durationMs());
            data.put(HookDataKeys.SUBAGENT_DEPTH, c.taskIndex());
        } else if (msg instanceof SubAgentMessage.Failed f) {
            data.put("progressType", "failed");
            data.put(HookDataKeys.SUBAGENT_ID, f.sessionId());
            data.put(HookDataKeys.SUBAGENT_ERROR, f.error());
            data.put(HookDataKeys.SUBAGENT_DEPTH, f.taskIndex());
        } else if (msg instanceof SubAgentMessage.Timeout t) {
            data.put("progressType", "timeout");
            data.put(HookDataKeys.SUBAGENT_DEPTH, t.taskIndex());
        }
        return data;
    }


    private Set<Toolset> resolveAllowedToolsets(List<String> requested) {
        Set<Toolset> blocked = subAgentConfig.getBlockedToolsets().stream()
                .map(name -> {
                    try { return Toolset.valueOf(name.toUpperCase()); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (requested == null || requested.isEmpty()) {
            return Arrays.stream(Toolset.values())
                    .filter(ts -> !blocked.contains(ts))
                    .collect(Collectors.toSet());
        }

        return requested.stream()
                .map(name -> {
                    try { return Toolset.valueOf(name.toUpperCase()); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .filter(Objects::nonNull)
                .filter(ts -> !blocked.contains(ts))
                .collect(Collectors.toSet());
    }


    private record SubAgentResult(SubAgentMessage finalMsg, int taskIndex, List<SubAgentMessage> events) {}

    private String aggregateStatus(List<SubAgentResult> results) {
        boolean allCompleted = true;
        boolean anyFailed = false;
        for (SubAgentResult r : results) {
            if (r.finalMsg() instanceof SubAgentMessage.Failed || r.finalMsg() instanceof SubAgentMessage.Timeout) {
                anyFailed = true;
                allCompleted = false;
            } else if (!(r.finalMsg() instanceof SubAgentMessage.Completed)) {
                allCompleted = false;
            }
        }
        if (allCompleted) return "completed";
        if (anyFailed) return "partial_failure";
        return "unknown";
    }

    private String getStatus(SubAgentMessage msg) {
        if (msg instanceof SubAgentMessage.Started) return "started";
        if (msg instanceof SubAgentMessage.Completed) return "completed";
        if (msg instanceof SubAgentMessage.Failed) return "failed";
        if (msg instanceof SubAgentMessage.Timeout) return "timeout";
        return "unknown";
    }

    private ToolResult buildToolResult(List<SubAgentResult> results, long totalDurationMs) {
        if (results.size() == 1) {
            SubAgentResult r = results.get(0);
            return buildSingleResult(r.finalMsg(), totalDurationMs);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", aggregateStatus(results));
        root.put("total_duration_seconds", totalDurationMs / 1000.0);

        ArrayNode resultsArray = MAPPER.createArrayNode();
        for (SubAgentResult r : results) {
            resultsArray.add(buildResultEntry(r.finalMsg()));
        }
        root.set("results", resultsArray);

        return ToolResult.success(root);
    }

    private ToolResult buildSingleResult(SubAgentMessage finalMsg, long durationMs) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", getStatus(finalMsg));
        result.put("duration_seconds", durationMs / 1000.0);

        if (finalMsg instanceof SubAgentMessage.Completed c) {
            result.put("summary", c.summary());
            result.put("session_id", c.sessionId());
            result.set("tool_trace", buildTraceArray(c.toolTrace()));
            result.put("input_tokens", c.inputTokens());
            result.put("output_tokens", c.outputTokens());
        } else if (finalMsg instanceof SubAgentMessage.Failed f) {
            result.put("error", f.error());
            if (f.sessionId() != null) result.put("session_id", f.sessionId());
            result.set("tool_trace", buildTraceArray(f.toolTrace()));
        } else if (finalMsg instanceof SubAgentMessage.Timeout t) {
            result.put("error", "Subagent execution timed out");
            if (t.sessionId() != null) result.put("session_id", t.sessionId());
        }

        return ToolResult.success(result);
    }

    private ObjectNode buildResultEntry(SubAgentMessage msg) {
        ObjectNode entry = MAPPER.createObjectNode();

        if (msg instanceof SubAgentMessage.Completed c) {
            entry.put("task_index", c.taskIndex());
            entry.put("status", "completed");
            entry.put("summary", c.summary());
            entry.put("session_id", c.sessionId());
            entry.put("duration_seconds", c.durationMs() / 1000.0);
            entry.set("tool_trace", buildTraceArray(c.toolTrace()));
            entry.put("input_tokens", c.inputTokens());
            entry.put("output_tokens", c.outputTokens());
        } else if (msg instanceof SubAgentMessage.Failed f) {
            entry.put("task_index", f.taskIndex());
            entry.put("status", "failed");
            entry.put("error", f.error());
            if (f.sessionId() != null) entry.put("session_id", f.sessionId());
            entry.set("tool_trace", buildTraceArray(f.toolTrace()));
        } else if (msg instanceof SubAgentMessage.Timeout t) {
            entry.put("task_index", t.taskIndex());
            entry.put("status", "timeout");
            entry.put("error", "Subagent execution timed out");
            if (t.sessionId() != null) entry.put("session_id", t.sessionId());
        }

        return entry;
    }

    private ArrayNode buildTraceArray(List<SubAgentMessage.ToolTraceEntry> trace) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var entry : trace) {
            ObjectNode t = MAPPER.createObjectNode();
            t.put("tool", entry.tool());
            t.put("status", entry.status());
            t.put("duration_ms", entry.durationMs());
            arr.add(t);
        }
        return arr;
    }
}
