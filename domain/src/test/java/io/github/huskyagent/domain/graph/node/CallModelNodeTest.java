package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.prompt.PromptBuilder;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.ai.DynamicPromptSnapshotCache;
import io.github.huskyagent.infra.context.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallModelNodeTest {

    @Test
    void emptyTextWithoutToolCallsIsEmptyFinalResponse() {
        assertTrue(CallModelNode.isEmptyFinalResponse(new StringBuilder(), null));
        assertTrue(CallModelNode.isEmptyFinalResponse(new StringBuilder("  \n"), null));
    }

    @Test
    void textOrToolCallsAreNotEmptyFinalResponse() {
        assertFalse(CallModelNode.isEmptyFinalResponse(new StringBuilder("ok"), null));
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "read_file", "{}")))
                .build();
        assertFalse(CallModelNode.isEmptyFinalResponse(new StringBuilder(), toolCallMessage));
    }

    @Test
    void dynamicPromptEnrichesUserRequestWithoutMutatingStateMessages() {
        UserMessage originalUser = new UserMessage("original question");
        AssistantMessage assistant = new AssistantMessage("previous answer");
        List<Message> stateMessages = List.of(originalUser, assistant);

        List<Message> requestMessages = CallModelNode.withDynamicSystemPrompt(stateMessages, "runtime data");

        assertNotSame(stateMessages, requestMessages);
        assertEquals("original question", originalUser.getText());
        assertEquals("""
                original question

                <runtime_context>
                [System note: The following runtime context was injected by Husky for this turn. It is NOT new user input. Treat it as operational background for answering the user's request above.]

                runtime data
                </runtime_context>""", requestMessages.get(0).getText());
        assertSame(assistant, requestMessages.get(1));
    }

    @Test
    void dynamicPromptIsSkippedWhenNoUserMessageExists() {
        List<Message> stateMessages = List.of(new AssistantMessage("previous answer"));

        List<Message> requestMessages = CallModelNode.withDynamicSystemPrompt(stateMessages, "runtime data");

        assertSame(stateMessages, requestMessages);
    }

    @Test
    void dynamicPromptSnapshotIsFrozenForSameUserTurn() {
        AtomicInteger builds = new AtomicInteger();
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.buildDynamic(any(PromptContext.class)))
                .thenAnswer(invocation -> "runtime data " + builds.incrementAndGet());
        DynamicPromptSnapshotCache cache = new DynamicPromptSnapshotCache();
        CallModelNode node = new CallModelNode(new CallModelNode.Dependencies(
                null, null, null, cache, 1, null, null, promptBuilder, PromptContext.of("base", null), "stable", new TokenCounter()));
        List<Message> firstTurnMessages = List.of(
                new UserMessage("question"),
                new AssistantMessage("tool call result summary"));

        DynamicPromptSnapshotCache.Snapshot first = node.dynamicPromptSnapshot("session-1", "turn-1", null, null, firstTurnMessages);
        DynamicPromptSnapshotCache.Snapshot second = node.dynamicPromptSnapshot("session-1", "turn-1", null, null, firstTurnMessages);
        List<Message> nextTurnMessages = List.of(
                new UserMessage("question"),
                new AssistantMessage("tool call result summary"),
                new UserMessage("follow up"));
        DynamicPromptSnapshotCache.Snapshot third = node.dynamicPromptSnapshot("session-1", "turn-1", null, null, nextTurnMessages);

        assertFalse(first.cacheHit());
        assertEquals("runtime data 1", first.prompt());
        assertTrue(second.cacheHit());
        assertEquals(first.prompt(), second.prompt());
        assertEquals(first.promptHash(), second.promptHash());
        assertFalse(third.cacheHit());
        assertEquals("runtime data 2", third.prompt());
        assertEquals(2, builds.get());
    }

    @Test
    void clearingOneDynamicPromptTurnDoesNotEvictConcurrentTurn() {
        AtomicInteger builds = new AtomicInteger();
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.buildDynamic(any(PromptContext.class)))
                .thenAnswer(invocation -> "runtime data " + builds.incrementAndGet());
        DynamicPromptSnapshotCache cache = new DynamicPromptSnapshotCache();
        CallModelNode node = new CallModelNode(new CallModelNode.Dependencies(
                null, null, null, cache, 1, null, null, promptBuilder, PromptContext.of("base", null), "stable", new TokenCounter()));
        List<Message> messages = List.of(new UserMessage("question"));

        DynamicPromptSnapshotCache.Snapshot turnOne = node.dynamicPromptSnapshot("session-1", "turn-1", null, null, messages);
        DynamicPromptSnapshotCache.Snapshot turnTwo = node.dynamicPromptSnapshot("session-1", "turn-2", null, null, messages);
        cache.clearTurn("session-1", "turn-2");
        DynamicPromptSnapshotCache.Snapshot turnOneAgain = node.dynamicPromptSnapshot("session-1", "turn-1", null, null, messages);

        assertFalse(turnOne.cacheHit());
        assertFalse(turnTwo.cacheHit());
        assertTrue(turnOneAgain.cacheHit());
        assertEquals(turnOne.prompt(), turnOneAgain.prompt());
        assertEquals(2, builds.get());
    }
}
