package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.util.GraphUtils;
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
public class ApprovalEdge {

    private static final String LABEL_APPROVED = "APPROVED";
    private static final String LABEL_REJECTED = "REJECTED";

    public AsyncCommandAction<ReActAgentState> build() {
        return (state, config) -> {
            Optional<String> resultOpt = state.approvalResult();
            if (resultOpt.isEmpty()) {
                return failedFuture(new IllegalStateException(
                        "approvalAction executed with empty APPROVAL_RESULT; this should not happen"));
            }
            String result = resultOpt.get();
            log.debug("[approvalAction] approval result={}", result);
            if (LABEL_APPROVED.equals(result)) {
                return completedFuture(new Command(LABEL_APPROVED,
                        Map.of(ReActAgentState.APPROVAL_RESULT, "")));
            }

            Map<String, Object> updates = new HashMap<>();
            List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
            if (!requests.isEmpty()) {
                ToolResponseMessage rejectionMsg = GraphUtils.buildRejectionMessage(requests.get(0));
                updates.put("messages", rejectionMsg);
                updates.put(ReActAgentState.TOOL_EXECUTION_REQUESTS,
                        state.toolExecutionRequestsWithoutFirst());
            }
            updates.put(ReActAgentState.APPROVAL_RESULT, "");
            return completedFuture(new Command(LABEL_REJECTED, updates));
        };
    }
}
