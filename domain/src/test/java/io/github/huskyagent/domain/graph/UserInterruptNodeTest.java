package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.graph.edge.UserInterruptEdge;
import io.github.huskyagent.domain.hook.DefaultHookRegistry;
import io.github.huskyagent.domain.hook.HookEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserInterruptNodeTest {

    @Test
    void interruptBuildsClarifyMetadataFromToolArgs() {
        UserInterruptNode node = new UserInterruptNode("clarify", ReActAgentState.CLARIFY_RESULT,
                new DefaultHookRegistry(List.of(), noopBus()));
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("clarify", "{\"question\":\"Pick one?\",\"options\":[\"A\",\"B\"]}"))));

        var interruption = node.interrupt("clarify", state, null);

        assertTrue(interruption.isPresent());
        assertEquals("clarify", interruption.get().metadata("type").orElse(null));
        assertEquals("Pick one?", interruption.get().metadata("question").orElse(null));
        assertEquals(List.of("A", "B"), interruption.get().metadata("options").orElse(null));
    }

    @Test
    void interruptDoesNotSuspendWhenResultExists() {
        UserInterruptNode node = new UserInterruptNode("clarify", ReActAgentState.CLARIFY_RESULT,
                new DefaultHookRegistry(List.of(), noopBus()));
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.CLARIFY_RESULT, "A",
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("clarify", "{\"question\":\"Pick one?\"}"))));

        assertTrue(node.interrupt("clarify", state, null).isEmpty());
    }

    @Test
    void edgeConvertsAnswerToToolResponseAndPopsRequest() throws Exception {
        UserInterruptEdge edge = new UserInterruptEdge(ReActAgentState.CLARIFY_RESULT);
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.CLARIFY_RESULT, "A",
                ReActAgentState.TOOL_EXECUTION_REQUESTS,
                List.of(toolCall("clarify", "{\"question\":\"Pick one?\"}"))));

        var command = edge.build().apply(state, null).get();
        Map<String, Object> updates = command.update();

        assertEquals("ANSWERED", command.gotoNode());
        assertEquals("", updates.get(ReActAgentState.CLARIFY_RESULT));
        assertEquals(List.of(), updates.get(ReActAgentState.TOOL_EXECUTION_REQUESTS));
        assertInstanceOf(ToolResponseMessage.class, updates.get("messages"));
        ToolResponseMessage response = (ToolResponseMessage) updates.get("messages");
        assertEquals("A", response.getResponses().get(0).responseData());
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("call-1", "function", name, args);
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
