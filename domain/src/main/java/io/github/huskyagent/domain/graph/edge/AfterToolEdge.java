package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * afterTool 边：审批工具执行节点后的路由。
 * 工具失败 → end（回 model 反思）；审批队列非空 → continue；队列为空 → end（回 model）。
 */
@Slf4j
public class AfterToolEdge {

    private static final String LABEL_CONTINUE = "continue";
    private static final String LABEL_END      = "end";

    public AsyncCommandAction<ReActAgentState> build() {
        return AsyncCommandAction.command_async((state, config) -> {
            if (state.lastToolFailed()) {
                log.info("[afterTool] 工具失败 → model 反思");
                return new Command(LABEL_END);
            }
            List<AssistantMessage.ToolCall> remaining = state.toolExecutionRequests();
            if (!remaining.isEmpty()) {
                log.debug("[afterTool] 队列剩余 {} 个工具 → dispatcher", remaining.size());
                return new Command(LABEL_CONTINUE);
            }
            log.debug("[afterTool] 队列已空 → model");
            return new Command(LABEL_END);
        });
    }
}
