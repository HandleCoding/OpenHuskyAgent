package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

@Slf4j
public class AfterToolEdge {

    private static final String LABEL_CONTINUE = "continue";
    private static final String LABEL_END      = "end";

    public AsyncCommandAction<ReActAgentState> build() {
        return AsyncCommandAction.command_async((state, config) -> {
            if (state.lastToolFailed()) {
                log.info("[afterTool] Tool failed -> model reflection");
                return new Command(LABEL_END);
            }
            List<AssistantMessage.ToolCall> remaining = state.toolExecutionRequests();
            if (!remaining.isEmpty()) {
                log.debug("[afterTool] Queue has {} remaining tools -> dispatcher", remaining.size());
                return new Command(LABEL_CONTINUE);
            }
            log.debug("[afterTool] Queue empty -> model");
            return new Command(LABEL_END);
        });
    }
}
