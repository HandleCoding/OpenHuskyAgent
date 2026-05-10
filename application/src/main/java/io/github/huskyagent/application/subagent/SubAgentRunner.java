package io.github.huskyagent.application.subagent;

import io.github.huskyagent.application.runtime.CapabilityVisibilityResolver;
import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.RequestToolContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.domain.subagent.SubAgentMessage;
import io.github.huskyagent.domain.subagent.SubAgentMessageQueue;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
public class SubAgentRunner {

    private static final String DYNAMIC_PROMPT_TURN_ID_METADATA = "dynamicPromptTurnId";

    private final SessionManager sessionManager;
    private final AgentGraph agentGraph;
    private final SubAgentConfig config;
    private final SubAgentTask task;
    private final SubAgentMessageQueue queue;
    private final String parentSessionId;
    private final ToolExecutionContext parentExecutionContext;
    private final CapabilityVisibilityResolver capabilityVisibilityResolver;
    private final RuntimePolicyResolver runtimePolicyResolver;
    private final DynamicPromptSnapshotCache dynamicPromptSnapshotCache;
    private final ToolCallbackFactory toolCallbackFactory;

    private final List<SubAgentMessage.ToolTraceEntry> toolTrace = new ArrayList<>();

    private class QueuePushingHookRegistry implements HookRegistry {

        @Override
        public void register(io.github.huskyagent.domain.hook.AgentHook hook) {}
        @Override
        public void unregister(String hookName) {}
        @Override
        public HookResult fireBefore(HookEvent event, String sessionId, Map<String, Object> data) {
            return HookResult.allow();
        }
        @Override
        public void fireAfter(HookEvent event, String sessionId, Map<String, Object> data) {
            int idx = task.taskIndex();
            if (event == HookEvent.TOOL_CALL_START) {
                String toolName = data.get(HookDataKeys.TOOL_NAME) instanceof String s ? s : "unknown";
                String argsPreview = data.get(HookDataKeys.TOOL_ARGS_PREVIEW) instanceof String s ? s : "";
                queue.offer(new SubAgentMessage.ToolCallStarted(toolName, argsPreview, idx));
            } else if (event == HookEvent.TOOL_CALL_AFTER) {
                String toolName = data.get(HookDataKeys.TOOL_NAME) instanceof String s ? s : null;
                long durationMs = data.get(HookDataKeys.TOOL_DURATION_MS) instanceof Number n ? n.longValue() : 0;
                String status = data.get(HookDataKeys.TOOL_STATUS) instanceof String s ? s : "completed";
                boolean success = !"failed".equals(status);
                if (toolName != null) {
                    toolTrace.add(new SubAgentMessage.ToolTraceEntry(toolName, success ? "completed" : "failed", durationMs));
                    queue.offer(new SubAgentMessage.ToolCallCompleted(toolName, durationMs, success, idx));
                }
            }
        }
        @Override
        public java.util.List<io.github.huskyagent.domain.hook.AgentHook> getHooks() { return java.util.List.of(); }
        @Override
        public java.util.List<io.github.huskyagent.domain.hook.AgentHook> getHooks(HookEvent event) { return java.util.List.of(); }
    }

    public SubAgentRunner(SessionManager sessionManager, AgentGraph agentGraph,
                          SubAgentConfig config,
                          SubAgentTask task, SubAgentMessageQueue queue,
                          String parentSessionId, ToolExecutionContext parentExecutionContext,
                          CapabilityVisibilityResolver capabilityVisibilityResolver,
                          RuntimePolicyResolver runtimePolicyResolver,
                          DynamicPromptSnapshotCache dynamicPromptSnapshotCache,
                          ToolCallbackFactory toolCallbackFactory) {
        this.sessionManager = sessionManager;
        this.agentGraph = agentGraph;
        this.config = config;
        this.task = task;
        this.queue = queue;
        this.parentSessionId = parentSessionId;
        this.parentExecutionContext = parentExecutionContext;
        this.capabilityVisibilityResolver = capabilityVisibilityResolver;
        this.runtimePolicyResolver = runtimePolicyResolver;
        this.dynamicPromptSnapshotCache = dynamicPromptSnapshotCache;
        this.toolCallbackFactory = toolCallbackFactory;
    }

    public SubAgentMessage run() {
        int idx = task.taskIndex();
        String childSessionId = sessionManager.createSession();
        queue.offer(new SubAgentMessage.Started(childSessionId, task.goal(), idx));
        long startTime = System.currentTimeMillis();

        try {
            String systemPrompt = buildSystemPrompt();

            SceneConfig sceneConfig = buildSceneConfig(systemPrompt);
            CapabilityView capabilityView = capabilityVisibilityResolver.resolveSubAgent(sceneConfig, buildParentCapabilityView());
            RuntimePolicy runtimePolicy = runtimePolicyResolver.assemble(sceneConfig, capabilityView);
            RuntimeScope childScope = buildChildRuntimeScope(childSessionId, sceneConfig, runtimePolicy);
            ReActAgentState finalState = runWithLegacySessionContext(childScope, () -> {
                try {
                    CompiledGraph<ReActAgentState> graph = agentGraph.buildGraph(
                            childSessionId, task.workingDirectory(), childScope.toSessionScope(),
                            runtimePolicy, systemPrompt, new QueuePushingHookRegistry(),
                            childScope.getChannelIdentity(), childScope.getPrincipal()
                    );

                    String turnId = UUID.randomUUID().toString();
                    RunnableConfig runConfig = RunnableConfig.builder()
                            .threadId(childSessionId)
                            .putMetadata(DYNAMIC_PROMPT_TURN_ID_METADATA, turnId)
                            .putMetadata(RequestToolContext.METADATA_KEY, buildRequestToolContext(childSessionId, childScope))
                            .putMetadata("channelIdentity", childScope.getChannelIdentity())
                            .putMetadata("principal", childScope.getPrincipal())
                            .build();

                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("messages", new UserMessage(task.goal()));
                    inputs.put(ReActAgentState.MODEL_CALL_COUNT, 0);

                    ReActAgentState state = null;
                    try {
                        for (NodeOutput<ReActAgentState> step : graph.stream(inputs, runConfig)) {
                            state = step.state();
                        }
                    } finally {
                        dynamicPromptSnapshotCache.clearTurn(childSessionId, turnId);
                    }
                    return state;
                } catch (Exception e) {
                    throw new SubAgentExecutionException(e);
                }
            });

            long durationMs = System.currentTimeMillis() - startTime;
            String summary = extractResponse(finalState);

            SubAgentMessage.Completed completed = new SubAgentMessage.Completed(
                    childSessionId, summary, List.copyOf(toolTrace), durationMs, 0, 0, idx);
            queue.offer(completed);
            return completed;

        } catch (SubAgentExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Sub-agent execution failed: sessionId={}, goal={}", childSessionId, task.goal(), cause);
            SubAgentMessage.Failed failed = new SubAgentMessage.Failed(
                    childSessionId, cause.getMessage(), List.copyOf(toolTrace), idx);
            queue.offer(failed);
            return failed;
        } catch (Exception e) {
            log.error("Sub-agent execution failed: sessionId={}, goal={}", childSessionId, task.goal(), e);
            SubAgentMessage.Failed failed = new SubAgentMessage.Failed(
                    childSessionId, e.getMessage(), List.copyOf(toolTrace), idx);
            queue.offer(failed);
            return failed;
        } finally {
            queue.close();
        }
    }

    private <T> T runWithLegacySessionContext(RuntimeScope childScope, Supplier<T> action) {
        SessionScope previousScope = SessionContext.getScope();
        SessionContext.setScope(childScope.toSessionScope());
        try {
            return action.get();
        } finally {
            if (previousScope != null) {
                SessionContext.setScope(previousScope);
            } else {
                SessionContext.clear();
            }
        }
    }

    private static class SubAgentExecutionException extends RuntimeException {
        SubAgentExecutionException(Throwable cause) {
            super(cause);
        }
    }

    private RequestToolContext buildRequestToolContext(String sessionId, RuntimeScope scope) {
        var runtimePolicy = scope.getRuntimePolicy();
        var capabilityView = runtimePolicy.getCapabilityView();
        var toolDefinitions = capabilityView.getVisibleTools();
        var executionContext = new ToolExecutionContext(
                sessionId,
                scope.toSessionScope(),
                toolDefinitions,
                capabilityView.getVisibleToolsets(),
                capabilityView.getVisibleSkillNames(),
                capabilityView.getVisiblePromptSections());
        return RequestToolContext.of(toolDefinitions,
                toolCallbackFactory.build(toolDefinitions, sessionId, executionContext));
    }

    private RuntimeScope buildChildRuntimeScope(String childSessionId, SceneConfig sceneConfig, RuntimePolicy runtimePolicy) {
        Principal principal = Principal.builder()
                .id(parentExecutionContext != null && parentExecutionContext.sessionId() != null
                        ? parentExecutionContext.sessionId()
                        : parentSessionId)
                .channelType(ChannelType.TUI)
                .build();
        ChannelIdentity identity = ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .build();
        return RuntimeScope.builder()
                .sessionId(childSessionId)
                .principal(principal)
                .channelIdentity(identity)
                .runtimePolicy(runtimePolicy)
                .workingDirectory(task.workingDirectory())
                .build();
    }

    private SceneConfig buildSceneConfig(String systemPrompt) {
        SceneConfig sceneConfig = new SceneConfig();
        sceneConfig.setSceneId("subagent");
        sceneConfig.setSystemPrompt(systemPrompt);
        sceneConfig.setAllowedToolsets(task.allowedToolsets());
        sceneConfig.setApprovalPolicy(SceneConfig.ApprovalPolicy.NONE);
        sceneConfig.getMemoryPolicyConfig().setEnabled(false);
        return sceneConfig;
    }

    private CapabilityView buildParentCapabilityView() {
        if (parentExecutionContext == null) {
            return CapabilityView.builder()
                    .sceneId("parent")
                    .visibleTools(List.of())
                    .visibleToolNames(java.util.Set.of())
                    .visibleToolsets(java.util.Set.of())
                    .visibleSkills(List.of())
                    .visibleSkillNames(java.util.Set.of())
                    .visiblePromptSections(java.util.Set.of())
                    .stripApproval(false)
                    .build();
        }
        List<io.github.huskyagent.infra.tool.registry.ToolDefinition> visibleTools = parentExecutionContext.visibleTools() != null
                ? parentExecutionContext.visibleTools()
                : List.of();
        return CapabilityView.builder()
                .sceneId("parent")
                .visibleTools(visibleTools)
                .visibleToolNames(visibleTools.stream()
                        .map(io.github.huskyagent.infra.tool.registry.ToolDefinition::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .visibleToolsets(parentExecutionContext.visibleToolsets() != null ? parentExecutionContext.visibleToolsets() : java.util.Set.of())
                .visibleSkills(List.of())
                .visibleSkillNames(parentExecutionContext.visibleSkillNames() != null ? parentExecutionContext.visibleSkillNames() : java.util.Set.of())
                .visiblePromptSections(parentExecutionContext.visiblePromptSections() != null ? parentExecutionContext.visiblePromptSections() : java.util.Set.of())
                .stripApproval(false)
                .build();
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a focused subagent working on a specific delegated task.\n\n");
        sb.append("YOUR TASK:\n").append(task.goal()).append("\n\n");
        if (task.context() != null && !task.context().isBlank()) {
            sb.append("CONTEXT:\n").append(task.context()).append("\n\n");
        }
        if (task.workingDirectory() != null) {
            sb.append("WORKING DIRECTORY: ").append(task.workingDirectory()).append("\n\n");
        }
        sb.append("Complete this task using the tools available to you. ");
        sb.append("When finished, provide a clear, concise summary of:\n");
        sb.append("- What you did\n- What you found or accomplished\n");
        sb.append("- Any files you created or modified\n");
        sb.append("- Any issues encountered\n\n");
        sb.append("Be thorough but concise -- your response will be returned to ");
        sb.append("the parent agent as a summary.\n");
        return sb.toString();
    }

    private String extractResponse(ReActAgentState state) {
        if (state == null) return "Unable to get sub-agent response";
        List<Message> msgs = state.messages();
        if (msgs != null && !msgs.isEmpty()) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Message m = msgs.get(i);
                if (m instanceof AssistantMessage am
                        && am.getText() != null && !am.getText().isBlank()) {
                    return am.getText();
                }
            }
        }
        return "Sub-agent did not produce a valid response";
    }
}
