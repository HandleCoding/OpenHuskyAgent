package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.graph.edge.*;
import io.github.huskyagent.domain.graph.node.*;
import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.prompt.PromptBuilder;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.ai.LlmRetryPolicy;
import io.github.huskyagent.infra.ai.LlmUsageDetailsExtractor;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.context.TokenCounter;
import io.github.huskyagent.infra.session.CheckpointStore;
import io.github.huskyagent.infra.session.CheckpointStoreFactory;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

import static org.bsc.langgraph4j.StateGraph.START;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGraph {

    public static final String NODE_MODEL             = "model";
    public static final String NODE_DISPATCHER        = "action_dispatcher";
    public static final String NODE_PARALLEL_EXECUTOR = "parallel_executor";
    public static final String NODE_APPROVAL          = "approval";
    public static final String NODE_EXECUTE_TOOL      = "execute_tool";
    private static final Set<String> USER_INTERRUPT_TOOL_NAMES = Set.of("clarify");

    private static final String LABEL_CONTINUE = "continue";
    private static final String LABEL_END      = "end";
    private static final String LABEL_APPROVED = "APPROVED";
    private static final String LABEL_REJECTED = "REJECTED";

    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final ToolCallbackFactory toolCallbackFactory;
    private final LlmRetryPolicy llmRetryPolicy;
    private final LlmUsageDetailsExtractor usageDetailsExtractor;
    private final DynamicPromptSnapshotCache dynamicPromptSnapshotCache;
    private final CheckpointStoreFactory checkpointStoreFactory;
    private final AgentConfig agentConfig;
    private final HookRegistry hookRegistry;
    private final ChannelEventBus eventBus;
    private final TokenCounter tokenCounter;
    @Qualifier("toolExecutor")
    private final java.util.concurrent.ExecutorService toolExecutor;


    public CompiledGraph<ReActAgentState> buildGraph(String sessionId, Path workingDirectory,
            SessionScope sessionScope,
            RuntimePolicy runtimePolicy,
            String systemPromptOverride,
            HookRegistry hookRegistryOverride,
            ChannelIdentity channelIdentity, Principal principal) throws GraphStateException {

        List<ToolDefinition> toolDefinitions = runtimePolicy.getCapabilityView().getVisibleTools();
        HookRegistry effectiveHookRegistry = hookRegistryOverride != null ? hookRegistryOverride : hookRegistry;

        return buildGraphInternal(sessionId, workingDirectory, sessionScope, toolDefinitions, systemPromptOverride,
                effectiveHookRegistry, runtimePolicy, channelIdentity, principal);
    }

    private CompiledGraph<ReActAgentState> buildGraphInternal(String sessionId,
                                                             Path workingDirectory,
                                                             SessionScope sessionScope,
                                                             List<ToolDefinition> toolDefinitions,
                                                             String systemPromptOverride,
                                                             HookRegistry effectiveHookRegistry,
                                                             RuntimePolicy runtimePolicy,
                                                             ChannelIdentity channelIdentity, Principal principal) throws GraphStateException {

        List<ToolCallback> toolCallbacks     = toolCallbackFactory.build(toolDefinitions, sessionId,
                buildToolExecutionContext(sessionId, sessionScope, runtimePolicy));
        SpringAIToolService toolService      = new SpringAIToolService(toolCallbacks);

        log.debug("Building graph: sessionId={}, tools={}", sessionId, toolDefinitions.size());

        PromptContext promptContext = PromptContext.of(sessionId, workingDirectory)
                .runtimePolicy(runtimePolicy)
                .sessionScope(sessionScope)
                .gatewaySystemPrompt(systemPromptOverride)
                .channelIdentity(channelIdentity)
                .principal(principal)
                .sceneId(runtimePolicy.getSceneId());
        String systemPrompt = promptBuilder.buildSessionStable(promptContext);

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultOptions(ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .defaultSystem(systemPrompt)
                .defaultToolCallbacks(toolCallbacks)
                .build();

        Set<String> approvalToolNames = GraphUtils.collectApprovalToolNames(toolDefinitions);
        Map<String, ToolDefinition> toolDefinitionMap = new HashMap<>();
        for (ToolDefinition def : toolDefinitions) toolDefinitionMap.put(def.name(), def);

        StateGraph<ReActAgentState> graph = buildStateGraph(new GraphBuildContext(
                chatClient,
                toolService,
                toolDefinitions,
                approvalToolNames,
                toolDefinitionMap,
                effectiveHookRegistry,
                promptContext,
                systemPrompt));

        CheckpointStore checkpointStore = checkpointStoreFactory.forCheckpointType(runtimePolicy.effectiveCheckpointType());
        var saver = checkpointStore.isPersistent() ? checkpointStore : new MemorySaver();
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(10000)
                .build());
    }

    private ToolExecutionContext buildToolExecutionContext(String sessionId, SessionScope sessionScope, RuntimePolicy runtimePolicy) {
        var capabilityView = runtimePolicy.getCapabilityView();
        return new ToolExecutionContext(
                sessionId,
                sessionScope,
                capabilityView.getVisibleTools(),
                capabilityView.getVisibleToolsets(),
                capabilityView.getVisibleSkillNames(),
                capabilityView.getVisiblePromptSections());
    }


    private StateGraph<ReActAgentState> buildStateGraph(GraphBuildContext context) throws GraphStateException {

        var serializer = new SpringAIJacksonStateSerializer<>(ReActAgentState::new);
        var graph      = new StateGraph<>(ReActAgentState.SCHEMA, serializer);
        var modelDependencies = new CallModelNode.Dependencies(
                context.chatClient(),
                llmRetryPolicy,
                usageDetailsExtractor,
                dynamicPromptSnapshotCache,
                agentConfig.getLlm().getCallBlockTimeoutMinutes(),
                context.effectiveHookRegistry(),
                eventBus,
                promptBuilder,
                context.promptContext(),
                context.systemPrompt(),
                tokenCounter);
        var parallelDependencies = new ParallelExecutorNode.Dependencies(
                context.toolService(),
                context.approvalToolNames(),
                USER_INTERRUPT_TOOL_NAMES,
                context.toolDefinitionMap(),
                agentConfig.getTool().getMaxRetries(),
                agentConfig.getTool().getExecutionTimeoutSeconds(),
                context.effectiveHookRegistry(),
                toolExecutor);
        var executeToolDependencies = new ExecuteToolNode.Dependencies(
                context.toolService(),
                context.toolDefinitionMap(),
                agentConfig.getTool().getMaxRetries(),
                agentConfig.getTool().getExecutionTimeoutSeconds(),
                context.effectiveHookRegistry());

        graph.addNode(NODE_MODEL,             new CallModelNode(modelDependencies).build());
        graph.addNode(NODE_PARALLEL_EXECUTOR, new ParallelExecutorNode(parallelDependencies).build());
        graph.addNode(NODE_DISPATCHER,        new DispatchToolsNode(USER_INTERRUPT_TOOL_NAMES).build());
        graph.addNode(NODE_APPROVAL,          new ApprovalNode(context.toolDefinitionMap(), context.effectiveHookRegistry()));
        graph.addNode(NODE_EXECUTE_TOOL,      new ExecuteToolNode(executeToolDependencies).build());
        graph.addEdge(START, NODE_MODEL);

        // ── model → parallel_executor ─────────────────────────────────────────
        graph.addConditionalEdges(NODE_MODEL, new ShouldContinueEdge(agentConfig.getGraph().getMaxReactLoops()).build(),
                EdgeMappings.builder()
                        .to(NODE_PARALLEL_EXECUTOR, LABEL_CONTINUE)
                        .toEND(LABEL_END)
                        .build());

        // ── parallel_executor → action_dispatcher / model ─────────────────────
        graph.addConditionalEdges(NODE_PARALLEL_EXECUTOR, new AfterParallelEdge().build(),
                EdgeMappings.builder()
                        .to(NODE_DISPATCHER, LABEL_CONTINUE)
                        .to(NODE_MODEL,      LABEL_END)
                        .build());

        var dispatchMappingBuilder = EdgeMappings.builder()
                .to(NODE_MODEL)
                .toEND()
                .to(NODE_APPROVAL);

        if (context.toolDefinitionMap().containsKey("clarify")) {
            graph.addNode("clarify", new UserInterruptNode("clarify", ReActAgentState.CLARIFY_RESULT, context.effectiveHookRegistry()));
            graph.addConditionalEdges("clarify", new UserInterruptEdge(ReActAgentState.CLARIFY_RESULT).build(),
                    EdgeMappings.builder()
                            .to(NODE_MODEL, UserInterruptEdge.LABEL_ANSWERED)
                            .build());
            dispatchMappingBuilder.to("clarify");
        }

        graph.addConditionalEdges(NODE_EXECUTE_TOOL, new AfterToolEdge().build(),
                EdgeMappings.builder()
                        .to(NODE_DISPATCHER, LABEL_CONTINUE)
                        .to(NODE_MODEL,      LABEL_END)
                        .build());

        graph.addConditionalEdges(NODE_APPROVAL, new ApprovalEdge().build(),
                EdgeMappings.builder()
                        .to(NODE_MODEL,        LABEL_REJECTED)
                        .to(NODE_EXECUTE_TOOL, LABEL_APPROVED)
                        .build());

        graph.addConditionalEdges(NODE_DISPATCHER, new DispatchEdge().build(),
                dispatchMappingBuilder.build());

        return graph;
    }

    private record GraphBuildContext(ChatClient chatClient,
                                     SpringAIToolService toolService,
                                     List<ToolDefinition> toolDefinitions,
                                     Set<String> approvalToolNames,
                                     Map<String, ToolDefinition> toolDefinitionMap,
                                     HookRegistry effectiveHookRegistry,
                                     PromptContext promptContext,
                                     String systemPrompt) {
    }
}
