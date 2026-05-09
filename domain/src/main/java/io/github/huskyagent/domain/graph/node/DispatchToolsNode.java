package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Slf4j
public class DispatchToolsNode {

    private final Set<String> interruptToolNames;

    public DispatchToolsNode(Set<String> interruptToolNames) {
        this.interruptToolNames = interruptToolNames != null ? interruptToolNames : Set.of();
    }

    public AsyncNodeActionWithConfig<ReActAgentState> build() {
        return (state, config) -> {
            List<AssistantMessage.ToolCall> existing = state.toolExecutionRequests();
            log.info("[action_dispatcher] Starting dispatch, queueSize={}, lastMsgType={}",
                    existing.size(),
                    state.lastMessage()
                            .map(m -> m.getMessageType() == null ? "null" : m.getMessageType().name())
                            .orElse("empty"));

            if (!existing.isEmpty()) {
                String toolName = existing.get(0).name();
                String nextNode = interruptToolNames.contains(toolName)
                        ? toolName
                        : AgentGraph.NODE_APPROVAL;
                log.info("[action_dispatcher] currentTool={}, next={}", toolName, nextNode);
                return completedFuture(Map.of(
                        ReActAgentState.TOOL_EXECUTION_REQUESTS, existing,
                        ReActAgentState.NEXT_ACTION, nextNode));
            }

            log.warn("[action_dispatcher] Approval queue is empty; returning to model");
            return completedFuture(Map.of(
                    ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(),
                    ReActAgentState.NEXT_ACTION, AgentGraph.NODE_MODEL));
        };
    }
}
