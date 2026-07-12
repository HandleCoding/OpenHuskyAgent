package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.graph.edge.*;
import io.github.huskyagent.domain.graph.node.*;
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
import io.github.huskyagent.infra.llm.LlmClientRegistry;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.session.CheckpointStore;
import io.github.huskyagent.infra.session.CheckpointStoreFactory;
import io.github.huskyagent.infra.session.SessionScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
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

    private final LlmClientRegistry llmClientRegistry;
    private final PromptBuilder promptBuilder;
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
        return buildGraph(sessionId, workingDirectory, sessionScope, runtimePolicy, systemPromptOverride,
                hookRegistryOverride, channelIdentity, principal, false);
    }

    public CompiledGraph<ReActAgentState> buildGraph(String sessionId, Path workingDirectory,
            SessionScope sessionScope,
            RuntimePolicy runtimePolicy,
            String systemPromptOverride,
            HookRegistry hookRegistryOverride,
            ChannelIdentity channelIdentity, Principal principal,
            boolean forceMemoryCheckpoint) throws GraphStateException {

        HookRegistry effectiveHookRegistry = hookRegistryOverride != null ? hookRegistryOverride : hookRegistry;

        return buildGraphInternal(sessionId, workingDirectory, sessionScope, systemPromptOverride,
                effectiveHookRegistry, runtimePolicy, channelIdentity, principal, forceMemoryCheckpoint);
    }

    private CompiledGraph<ReActAgentState> buildGraphInternal(String sessionId,
                                                             Path workingDirectory,
                                                             SessionScope sessionScope,
                                                             String systemPromptOverride,
                                                             HookRegistry effectiveHookRegistry,
                                                             RuntimePolicy runtimePolicy,
                                                             ChannelIdentity channelIdentity, Principal principal,
                                                             boolean forceMemoryCheckpoint) throws GraphStateException {

        log.debug("Building graph: sessionId={}, agentId={}, model={}",
                sessionId,
                runtimePolicy != null ? runtimePolicy.getAgentId() : null,
                runtimePolicy != null && runtimePolicy.getModelSelection() != null
                        ? runtimePolicy.getModelSelection().fingerprint()
                        : "default");

        PromptContext promptContext = PromptContext.of(sessionId, workingDirectory)
                .runtimePolicy(runtimePolicy)
                .sessionScope(sessionScope)
                .gatewaySystemPrompt(systemPromptOverride)
                .channelIdentity(channelIdentity)
                .principal(principal)
                .agentId(runtimePolicy.getAgentId());
        String systemPrompt = promptBuilder.buildSessionStable(promptContext);

        ModelSelection modelSelection = resolveModelSelection(runtimePolicy);
        LlmTransport llmTransport = resolveLlmTransport(modelSelection);

        StateGraph<ReActAgentState> graph = buildStateGraph(new GraphBuildContext(
                llmTransport,
                modelSelection,
                effectiveHookRegistry,
                promptContext,
                systemPrompt,
                runtimePolicy));

        CheckpointStore checkpointStore = checkpointStoreFactory.forCheckpointType(runtimePolicy.effectiveCheckpointType());
        var saver = forceMemoryCheckpoint || !checkpointStore.isPersistent() ? new MemorySaver() : checkpointStore;
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(10000)
                .build());
    }

    private ModelSelection resolveModelSelection(RuntimePolicy runtimePolicy) {
        ModelSelection selection = runtimePolicy != null ? runtimePolicy.getModelSelection() : null;
        if (llmClientRegistry != null) {
            return llmClientRegistry.resolveSelection(selection);
        }
        return selection;
    }

    private LlmTransport resolveLlmTransport(ModelSelection modelSelection) {
        if (llmClientRegistry != null) {
            return llmClientRegistry.getTransport(modelSelection);
        }
        throw new IllegalStateException("LlmClientRegistry is required for model calls");
    }

    private int resolveMaxReactLoops(RuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getMaxReactLoops() != null && runtimePolicy.getMaxReactLoops() > 0) {
            return runtimePolicy.getMaxReactLoops();
        }
        return agentConfig.getGraph().getMaxReactLoops();
    }

    private StateGraph<ReActAgentState> buildStateGraph(GraphBuildContext context) throws GraphStateException {

        var serializer = new SpringAIJacksonStateSerializer<>(ReActAgentState::new);
        var graph      = new StateGraph<>(ReActAgentState.SCHEMA, serializer);
        var modelDependencies = new CallModelNode.Dependencies(
                context.llmTransport(),
                context.modelSelection(),
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
                USER_INTERRUPT_TOOL_NAMES,
                agentConfig.getTool().getMaxRetries(),
                agentConfig.getTool().getExecutionTimeoutSeconds(),
                context.effectiveHookRegistry(),
                toolExecutor);
        var executeToolDependencies = new ExecuteToolNode.Dependencies(
                agentConfig.getTool().getMaxRetries(),
                agentConfig.getTool().getExecutionTimeoutSeconds(),
                context.effectiveHookRegistry());

        graph.addNode(NODE_MODEL,             new CallModelNode(modelDependencies).build());
        graph.addNode(NODE_PARALLEL_EXECUTOR, new ParallelExecutorNode(parallelDependencies).build());
        graph.addNode(NODE_DISPATCHER,        new DispatchToolsNode(USER_INTERRUPT_TOOL_NAMES).build());
        graph.addNode(NODE_APPROVAL,          new ApprovalNode(context.effectiveHookRegistry()));
        graph.addNode(NODE_EXECUTE_TOOL,      new ExecuteToolNode(executeToolDependencies).build());
        graph.addNode("clarify", new UserInterruptNode("clarify", ReActAgentState.CLARIFY_RESULT, context.effectiveHookRegistry()));
        graph.addEdge(START, NODE_MODEL);

        // ── model → parallel_executor ─────────────────────────────────────────
        int maxReactLoops = resolveMaxReactLoops(context.runtimePolicy());
        graph.addConditionalEdges(NODE_MODEL, new ShouldContinueEdge(maxReactLoops).build(),
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

        graph.addConditionalEdges("clarify", new UserInterruptEdge(ReActAgentState.CLARIFY_RESULT).build(),
                EdgeMappings.builder()
                        .to(NODE_MODEL, UserInterruptEdge.LABEL_ANSWERED)
                        .build());
        dispatchMappingBuilder.to("clarify");

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

    private record GraphBuildContext(LlmTransport llmTransport,
                                     ModelSelection modelSelection,
                                     HookRegistry effectiveHookRegistry,
                                     PromptContext promptContext,
                                     String systemPrompt,
                                     RuntimePolicy runtimePolicy) {
    }
}
