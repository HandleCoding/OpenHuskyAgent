package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.util.GraphUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

@Slf4j
@RequiredArgsConstructor
public class UserInterruptEdge {

    public static final String LABEL_ANSWERED = "ANSWERED";

    private final String resultChannel;

    public AsyncCommandAction<ReActAgentState> build() {
        return (state, config) -> {
            Optional<String> resultOpt = state.<String>value(resultChannel).filter(s -> !s.isEmpty());
            if (resultOpt.isEmpty()) {
                return failedFuture(new IllegalStateException(
                        "userInterruptAction 执行时 " + resultChannel + " 为空（不应发生）"));
            }

            String answer = resultOpt.get();
            List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
            if (requests.isEmpty()) {
                return failedFuture(new IllegalStateException(
                        "userInterruptAction 执行时 toolExecutionRequests 为空（不应发生）"));
            }

            AssistantMessage.ToolCall request = requests.get(0);
            ToolResponseMessage response = GraphUtils.buildToolResponseMessage(
                    request.id(), request.name(), answer);

            Map<String, Object> updates = new HashMap<>();
            updates.put("messages", response);
            updates.put(ReActAgentState.TOOL_EXECUTION_REQUESTS,
                    state.toolExecutionRequestsWithoutFirst());
            updates.put(resultChannel, "");

            log.debug("[userInterruptAction] {} answered", request.name());
            return completedFuture(new Command(LABEL_ANSWERED, updates));
        };
    }
}
