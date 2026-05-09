package io.github.huskyagent.domain.graph;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.DefaultHookRegistry;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.approval.ApprovalInfo;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalNodeTest {

    @Test
    void interruptUsesQueueHeadToolMetadata() {
        ToolDefinition tool = approvalTool("terminal");
        ApprovalNode node = new ApprovalNode(Map.of("terminal", tool), hooks());
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("terminal", "{\"command\":\"rm -rf /tmp/a\"}"))));

        var interruption = node.interrupt(AgentGraph.NODE_APPROVAL, state, null);

        assertTrue(interruption.isPresent());
        assertEquals("terminal", interruption.get().metadata(ApprovalInfo.TOOL_NAME_KEY).orElse(null));
        assertEquals("{\"command\":\"rm -rf /tmp/a\"}", interruption.get().metadata(ApprovalInfo.TOOL_ARGS_KEY).orElse(null));
        assertEquals("dangerous command", interruption.get().metadata(ApprovalInfo.TOOL_REASON_KEY).orElse(null));
    }

    @Test
    void interruptDoesNotSuspendWhenApprovalResultExists() {
        ToolDefinition tool = approvalTool("terminal");
        ApprovalNode node = new ApprovalNode(Map.of("terminal", tool), hooks());
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.APPROVAL_RESULT, "APPROVED",
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("terminal", "{}"))));

        assertTrue(node.interrupt(AgentGraph.NODE_APPROVAL, state, null).isEmpty());
    }

    @Test
    void applyInjectsApprovedWhenCheckerSkippedInterrupt() throws Exception {
        ToolDefinition tool = ToolDefinition.withApproval(
                "terminal", "Terminal", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> null);
        ApprovalNode node = new ApprovalNode(Map.of("terminal", tool), hooks());
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("terminal", "{\"command\":\"ls\"}"))));

        assertTrue(node.interrupt(AgentGraph.NODE_APPROVAL, state, null).isEmpty());
        Map<String, Object> update = node.apply(state, null).get();

        assertEquals("APPROVED", update.get(ReActAgentState.APPROVAL_RESULT));
    }

    @Test
    void applyDoesNotFailWhenApprovalResultExistsAndQueueIsEmpty() throws Exception {
        ApprovalNode node = new ApprovalNode(Map.of(), hooks());
        ReActAgentState state = new ReActAgentState(Map.of(ReActAgentState.APPROVAL_RESULT, "APPROVED"));

        Map<String, Object> update = node.apply(state, null).get();

        assertEquals(Map.of(), update);
    }

    @Test
    void missingQueueHeadFailsClearly() {
        ApprovalNode node = new ApprovalNode(Map.of(), hooks());
        ReActAgentState state = new ReActAgentState(Map.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> node.interrupt(AgentGraph.NODE_APPROVAL, state, null));

        assertTrue(error.getMessage().contains("empty tool queue"));
    }

    @Test
    void missingToolDefinitionFailsClearly() {
        ApprovalNode node = new ApprovalNode(Map.of(), hooks());
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("missing", "{}"))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> node.interrupt(AgentGraph.NODE_APPROVAL, state, null));

        assertTrue(error.getMessage().contains("could not find tool definition"));
    }

    private ToolDefinition approvalTool(String name) {
        return ToolDefinition.withApproval(
                name, "Terminal", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> new ApprovalRequest("req-1", name, args, "dangerous command", "session-1"));
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("call-1", "function", name, args);
    }

    private DefaultHookRegistry hooks() {
        return new DefaultHookRegistry(List.of(), noopBus());
    }

    private ChannelEventBus noopBus() {
        return new ChannelEventBus() {
            @Override
            public void publish(ChannelEvent event) {
            }

            @Override
            public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {
            }

            @Override
            public void unsubscribe(String channelName) {
            }

            @Override
            public void streamToken(String sessionId, String token, boolean reasoning) {
            }

            @Override
            public void subscribeTokens(String channelName, TokenSubscriber subscriber) {
            }

            @Override
            public void unsubscribeTokens(String channelName) {
            }
        };
    }
}
