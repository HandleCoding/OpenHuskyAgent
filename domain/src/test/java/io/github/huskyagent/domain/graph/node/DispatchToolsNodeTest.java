package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.graph.AgentGraph;
import io.github.huskyagent.domain.graph.ReActAgentState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatchToolsNodeTest {

    @Test
    void approvalToolRoutesToSharedApprovalNode() throws Exception {
        DispatchToolsNode node = new DispatchToolsNode(Set.of("clarify"));
        ReActAgentState state = new ReActAgentState(Map.of(
                "messages", List.of(new AssistantMessage("test")),
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("terminal", "{}"))));

        Map<String, Object> update = node.build().apply(state, null).get();

        assertEquals(AgentGraph.NODE_APPROVAL, update.get(ReActAgentState.NEXT_ACTION));
    }

    @Test
    void interruptToolRoutesToDedicatedInterruptNode() throws Exception {
        DispatchToolsNode node = new DispatchToolsNode(Set.of("clarify"));
        ReActAgentState state = new ReActAgentState(Map.of(
                "messages", List.of(new AssistantMessage("test")),
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("clarify", "{}"))));

        Map<String, Object> update = node.build().apply(state, null).get();

        assertEquals("clarify", update.get(ReActAgentState.NEXT_ACTION));
    }

    @Test
    void emptyQueueRoutesToModel() throws Exception {
        DispatchToolsNode node = new DispatchToolsNode(Set.of("clarify"));
        ReActAgentState state = new ReActAgentState(Map.of("messages", List.of(new AssistantMessage("test"))));

        Map<String, Object> update = node.build().apply(state, null).get();

        assertEquals(AgentGraph.NODE_MODEL, update.get(ReActAgentState.NEXT_ACTION));
        assertEquals(List.of(), update.get(ReActAgentState.TOOL_EXECUTION_REQUESTS));
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("call-1", "function", name, args);
    }
}
