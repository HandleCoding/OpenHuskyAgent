package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * afterParallel 边：parallel_executor 节点后的路由。
 * 安全工具有失败 → end（回 model 反思）；审批队列非空 → continue（交 dispatcher）；否则 → end（回 model）。
 */
@Slf4j
public class AfterParallelEdge {

    private static final String LABEL_CONTINUE = "continue";
    private static final String LABEL_END      = "end";

    public AsyncCommandAction<ReActAgentState> build() {
        return AsyncCommandAction.command_async((state, config) -> {
            if (state.lastToolFailed()) {
                log.info("[afterParallel] 安全工具有失败 → model 反思");
                return new Command(LABEL_END);
            }
            List<AssistantMessage.ToolCall> remaining = state.toolExecutionRequests();
            if (!remaining.isEmpty()) {
                log.debug("[afterParallel] 审批队列剩余 {} 个工具 → dispatcher", remaining.size());
                return new Command(LABEL_CONTINUE);
            }
            log.debug("[afterParallel] 所有工具完成 → model");
            return new Command(LABEL_END);
        });
    }
}
