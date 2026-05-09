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

/**
 * action_dispatcher 节点：从审批工具队列取下一个工具，决定下一跳节点名。
 * 仅处理审批工具队列（安全工具已由 parallel_executor 执行完毕）。
 */
@Slf4j
public class DispatchToolsNode {

    private final Set<String> interruptToolNames;

    public DispatchToolsNode(Set<String> interruptToolNames) {
        this.interruptToolNames = interruptToolNames != null ? interruptToolNames : Set.of();
    }

    public AsyncNodeActionWithConfig<ReActAgentState> build() {
        return (state, config) -> {
            List<AssistantMessage.ToolCall> existing = state.toolExecutionRequests();
            log.info("[action_dispatcher] 开始派发，队列大小={}, lastMsgType={}",
                    existing.size(),
                    state.lastMessage()
                            .map(m -> m.getMessageType() == null ? "null" : m.getMessageType().name())
                            .orElse("empty"));

            if (!existing.isEmpty()) {
                String toolName = existing.get(0).name();
                String nextNode = interruptToolNames.contains(toolName)
                        ? toolName
                        : AgentGraph.NODE_APPROVAL;
                log.info("[action_dispatcher] 当前工具={}，下一跳={}", toolName, nextNode);
                return completedFuture(Map.of(
                        ReActAgentState.TOOL_EXECUTION_REQUESTS, existing,
                        ReActAgentState.NEXT_ACTION, nextNode));
            }

            log.warn("[action_dispatcher] 审批队列为空，回 model");
            return completedFuture(Map.of(
                    ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(),
                    ReActAgentState.NEXT_ACTION, AgentGraph.NODE_MODEL));
        };
    }
}
