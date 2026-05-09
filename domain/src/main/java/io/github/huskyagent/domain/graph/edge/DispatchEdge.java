package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;

/**
 * dispatchAction 边：读取 NEXT_ACTION 字段路由到对应节点。
 */
@Slf4j
public class DispatchEdge {

    public AsyncCommandAction<ReActAgentState> build() {
        return AsyncCommandAction.command_async((state, config) -> {
            String next = state.<String>value(ReActAgentState.NEXT_ACTION)
                    .filter(s -> !s.isBlank())
                    .orElse(AgentGraph.NODE_MODEL);
            log.debug("[dispatchAction] → {}", next);
            return new Command(next);
        });
    }
}
