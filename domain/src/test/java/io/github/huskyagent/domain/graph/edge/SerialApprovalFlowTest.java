package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.node.DispatchToolsNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SerialApprovalFlowTest {

    @Test
    void approvedToolPopsQueueAndContinuesToNextApprovalTool() throws Exception {
        AssistantMessage.ToolCall first = toolCall("terminal", "{}");
        AssistantMessage.ToolCall second = toolCall("process", "{}");
        ReActAgentState executedState = new ReActAgentState(Map.of(
                ReActAgentState.LAST_TOOL_FAILED, false,
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(second)));

        var command = new AfterToolEdge().build().apply(executedState, null).get();
        Map<String, Object> dispatchUpdate = new DispatchToolsNode(Set.of("clarify"))
                .build()
                .apply(new ReActAgentState(Map.of(
                        "messages", List.of(new AssistantMessage("test")),
                        ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(second))), null)
                .get();

        assertEquals("continue", command.gotoNode());
        assertEquals(AgentGraph.NODE_APPROVAL, dispatchUpdate.get(ReActAgentState.NEXT_ACTION));
        ReActAgentState dispatchedState = new ReActAgentState(dispatchUpdate);
        assertEquals("process", dispatchedState.toolExecutionRequests().get(0).name());
    }

    @Test
    void rejectedToolPopsQueueAndReturnsToModel() throws Exception {
        AssistantMessage.ToolCall first = toolCall("terminal", "{}");
        AssistantMessage.ToolCall second = toolCall("process", "{}");
        ReActAgentState rejectedState = new ReActAgentState(Map.of(
                ReActAgentState.APPROVAL_RESULT, "REJECTED",
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(first, second)));

        var command = new ApprovalEdge().build().apply(rejectedState, null).get();
        Map<String, Object> update = command.update();

        assertEquals("REJECTED", command.gotoNode());
        assertEquals(List.of(second), update.get(ReActAgentState.TOOL_EXECUTION_REQUESTS));
        assertEquals("", update.get(ReActAgentState.APPROVAL_RESULT));
        assertInstanceOf(ToolResponseMessage.class, update.get("messages"));
    }

    @Test
    void mixedClarifyAndApprovalQueueRoutesByCurrentHead() throws Exception {
        DispatchToolsNode dispatcher = new DispatchToolsNode(Set.of("clarify"));
        AssistantMessage.ToolCall clarify = toolCall("clarify", "{}");
        AssistantMessage.ToolCall terminal = toolCall("terminal", "{}");

        Map<String, Object> clarifyFirst = dispatcher.build().apply(new ReActAgentState(Map.of(
                "messages", List.of(new AssistantMessage("test")),
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(clarify, terminal))), null).get();
        Map<String, Object> approvalFirst = dispatcher.build().apply(new ReActAgentState(Map.of(
                "messages", List.of(new AssistantMessage("test")),
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(terminal, clarify))), null).get();

        assertEquals("clarify", clarifyFirst.get(ReActAgentState.NEXT_ACTION));
        assertEquals(AgentGraph.NODE_APPROVAL, approvalFirst.get(ReActAgentState.NEXT_ACTION));
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("call-" + name, "function", name, args);
    }
}
