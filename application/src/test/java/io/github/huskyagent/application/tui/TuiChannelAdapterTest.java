package io.github.huskyagent.application.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.tool.todo.TodoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiChannelAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TuiChannelAdapter adapter;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        adapter = new TuiChannelAdapter(new NoOpChannelEventBus(), new TodoStore());
        sentMessages = new CopyOnWriteArrayList<>();
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(sentMessages::add);
        adapter.registerEmitter("conn1", "s1", new JsonRpcEventEmitter(dispatcher));
    }

    @Test
    @DisplayName("LLM_CALL_AFTER emits a single message.intermediate event")
    void llmCallAfterEmitsIntermediateEvent() throws Exception {
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.LLM_CALL_AFTER,
                Map.of(HookDataKeys.LLM_RESPONSE, new AssistantMessage("I need to call tools")),
                Instant.now()
        ));

        List<JsonNode> events = parseAllEvents();
        assertEquals(1, events.size());
        JsonNode params = events.get(0).get("params");
        assertEquals("message.intermediate", params.get("type").asText());
        JsonNode payload = params.get("payload");
        assertTrue(payload.has("text"));
        assertEquals("I need to call tools", payload.get("text").asText());
        assertFalse(payload.get("intermediate").asBoolean(), "intermediate should be false when there are no tool calls");
    }

    @Test
    void subagentProgressPreservesTaskIndex() throws Exception {
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.SUBAGENT_PROGRESS,
                Map.of(
                        "progressType", "started",
                        HookDataKeys.SUBAGENT_GOAL, "query beijing weather",
                        HookDataKeys.SUBAGENT_DEPTH, 0
                ),
                Instant.now()
        ));
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.SUBAGENT_PROGRESS,
                Map.of(
                        "progressType", "started",
                        HookDataKeys.SUBAGENT_GOAL, "query shanghai weather",
                        HookDataKeys.SUBAGENT_DEPTH, 1
                ),
                Instant.now()
        ));
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.SUBAGENT_PROGRESS,
                Map.of(
                        "progressType", "completed",
                        HookDataKeys.SUBAGENT_SUMMARY, "sunny",
                        HookDataKeys.SUBAGENT_DURATION_MS, 1200L,
                        HookDataKeys.SUBAGENT_DEPTH, 1
                ),
                Instant.now()
        ));

        List<JsonNode> events = parseAllEvents();
        assertEquals(3, events.size());

        assertEquals("subagent.started", events.get(0).get("params").get("type").asText());
        assertEquals(0, events.get(0).get("params").get("payload").get("taskIndex").asInt());

        assertEquals("subagent.started", events.get(1).get("params").get("type").asText());
        assertEquals(1, events.get(1).get("params").get("payload").get("taskIndex").asInt());

        assertEquals("subagent.completed", events.get(2).get("params").get("type").asText());
        assertEquals(1, events.get(2).get("params").get("payload").get("taskIndex").asInt());
        assertEquals("completed", events.get(2).get("params").get("payload").get("status").asText());
    }

    @Test
    void toolEventsPreserveToolCallId() throws Exception {
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_START,
                Map.of(
                        HookDataKeys.TOOL_NAME, "read_file",
                        HookDataKeys.TOOL_ARGS_PREVIEW, "parallel_demo_a.txt",
                        HookDataKeys.TOOL_ARGS, "{\"path\":\"parallel_demo_a.txt\",\"limit\":100}",
                        HookDataKeys.TOOL_CALL_ID, "call-a"
                ),
                Instant.now()
        ));
        adapter.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_AFTER,
                Map.of(
                        HookDataKeys.TOOL_NAME, "read_file",
                        HookDataKeys.TOOL_ARGS_PREVIEW, "parallel_demo_a.txt",
                        HookDataKeys.TOOL_ARGS, "{\"path\":\"parallel_demo_a.txt\",\"limit\":100}",
                        HookDataKeys.TOOL_CALL_ID, "call-a",
                        HookDataKeys.TOOL_DURATION_MS, 15L,
                        HookDataKeys.TOOL_STATUS, "completed"
                ),
                Instant.now()
        ));

        List<JsonNode> events = parseAllEvents();
        assertEquals(2, events.size());
        assertEquals("call-a", events.get(0).get("params").get("payload").get("toolCallId").asText());
        assertEquals("call-a", events.get(1).get("params").get("payload").get("toolCallId").asText());
        assertEquals("{\"path\":\"parallel_demo_a.txt\",\"limit\":100}",
                events.get(0).get("params").get("payload").get("toolArgs").asText());
        assertEquals("{\"path\":\"parallel_demo_a.txt\",\"limit\":100}",
                events.get(1).get("params").get("payload").get("toolArgs").asText());
    }

    @Test
    void connectionSessionSwitchStopsOldSessionRouting() throws Exception {
        sentMessages.clear();
        adapter.registerEmitter("conn1", "s2", new JsonRpcEventEmitter(new JsonRpcDispatcher(sentMessages::add)));

        adapter.onToken("s1", "old", false);
        adapter.onToken("s2", "new", false);

        List<JsonNode> events = parseAllEvents();
        assertEquals(1, events.size());
        assertEquals("new", events.get(0).get("params").get("payload").get("token").asText());
    }

    @Test
    void unregisterOneConnectionKeepsAnotherEmitterForSameSession() throws Exception {
        sentMessages.clear();
        List<String> secondMessages = new CopyOnWriteArrayList<>();
        adapter.registerEmitter("conn2", "s1", new JsonRpcEventEmitter(new JsonRpcDispatcher(secondMessages::add)));

        adapter.unregisterEmitter("conn2", "s1");
        adapter.onToken("s1", "still-active", false);

        List<JsonNode> events = parseMessages(sentMessages);
        assertEquals(1, events.size());
        assertEquals("still-active", events.get(0).get("params").get("payload").get("token").asText());
        assertTrue(secondMessages.isEmpty());
    }

    @Test
    void latestConnectionWinsForDuplicateSessionBindings() throws Exception {
        sentMessages.clear();
        List<String> secondMessages = new CopyOnWriteArrayList<>();
        adapter.registerEmitter("conn2", "s1", new JsonRpcEventEmitter(new JsonRpcDispatcher(secondMessages::add)));

        adapter.onToken("s1", "latest", false);

        assertTrue(sentMessages.isEmpty());
        List<JsonNode> events = parseMessages(secondMessages);
        assertEquals(1, events.size());
        assertEquals("latest", events.get(0).get("params").get("payload").get("token").asText());
    }

    @Test
    void staleUnregisterIsNoop() throws Exception {
        sentMessages.clear();

        adapter.unregisterEmitter("missing", "s1");
        adapter.onToken("s1", "ok", false);

        List<JsonNode> events = parseAllEvents();
        assertEquals(1, events.size());
        assertEquals("ok", events.get(0).get("params").get("payload").get("token").asText());
    }

    private List<JsonNode> parseAllEvents() throws Exception {
        return parseMessages(sentMessages);
    }

    private List<JsonNode> parseMessages(List<String> messages) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String msg : messages) {
            events.add(MAPPER.readTree(msg));
        }
        assertFalse(events.isEmpty());
        return events;
    }

    private static class NoOpChannelEventBus implements ChannelEventBus {
        @Override
        public void publish(ChannelEvent event) {}

        @Override
        public void streamToken(String sessionId, String token, boolean reasoning) {}

        @Override
        public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}

        @Override
        public void unsubscribe(String channelName) {}

        @Override
        public void subscribeTokens(String channelName, TokenSubscriber subscriber) {}

        @Override
        public void unsubscribeTokens(String channelName) {}
    }
}
