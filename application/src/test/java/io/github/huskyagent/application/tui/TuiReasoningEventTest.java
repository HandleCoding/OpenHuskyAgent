package io.github.huskyagent.application.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TuiReasoningEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<String> sentMessages;
    private JsonRpcDispatcher dispatcher;
    private JsonRpcEventEmitter emitter;
    private TuiSessionService sessionService;

    @BeforeEach
    void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        dispatcher = new JsonRpcDispatcher(sentMessages::add);
        emitter = new JsonRpcEventEmitter(dispatcher);
    }


    private void simulateHandleTextEvent(TextEvent event, JsonRpcEventEmitter emitter) {
        if (event.isTokenEvent()) {
            emitter.emitMessageDelta(event.token(), event.reasoning());
        }
    }


    private JsonNode parseLastEvent() throws Exception {
        assertFalse(sentMessages.isEmpty(), "should have sent events");
        String last = sentMessages.get(sentMessages.size() - 1);
        return MAPPER.readTree(last);
    }

    private List<JsonNode> parseAllEvents() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String msg : sentMessages) {
            events.add(MAPPER.readTree(msg));
        }
        return events;
    }


    @Test
    @DisplayName("reasoning token → emitMessageDelta(reasoning=true)")
    void reasoningToken_emitsDeltaWithReasoningTrue() throws Exception {
        TextEvent event = TextEvent.ofReasoning("let me think about this problem");

        simulateHandleTextEvent(event, emitter);

        JsonNode eventNode = parseLastEvent();
        assertEquals("2.0", eventNode.get("jsonrpc").asText());
        assertEquals("event", eventNode.get("method").asText());

        JsonNode params = eventNode.get("params");
        assertEquals("message.delta", params.get("type").asText());

        JsonNode payload = params.get("payload");
        assertEquals("let me think about this problem", payload.get("token").asText());
        assertTrue(payload.get("reasoning").asBoolean(), "reasoning field should be true");
    }

    @Test
    @DisplayName("text token → emitMessageDelta(reasoning=false)")
    void textToken_emitsDeltaWithReasoningFalse() throws Exception {
        TextEvent event = TextEvent.ofToken("hello world");

        simulateHandleTextEvent(event, emitter);

        JsonNode eventNode = parseLastEvent();
        JsonNode payload = eventNode.get("params").get("payload");
        assertEquals("hello world", payload.get("token").asText());
        assertFalse(payload.get("reasoning").asBoolean(), "reasoning field should be false");
    }

    @Test
    @DisplayName("intermediate message -> TuiSessionService no longer emits events")
    void intermediateMessage_emitsNothingFromSessionService() {
        TextEvent event = TextEvent.ofMessage("I need to use tools", true, List.of());

        simulateHandleTextEvent(event, emitter);

        assertTrue(sentMessages.isEmpty(), "whole message should be forwarded by TuiChannelAdapter");
    }

    @Test
    @DisplayName("full flow: reasoning -> text, full message handled by another path")
    void fullSequence_reasoningTextOnly() throws Exception {
        simulateHandleTextEvent(TextEvent.ofReasoning("analyzing user needs..."), emitter);
        simulateHandleTextEvent(TextEvent.ofReasoning("need to read file"), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("I will "), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("help you "), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("handle it"), emitter);
        simulateHandleTextEvent(TextEvent.ofMessage("I will help you handle it", true, List.of()), emitter);

        List<JsonNode> events = parseAllEvents();
        assertEquals(5, events.size(), "TuiSessionService should only emit token events");

        for (int i = 0; i < 2; i++) {
            JsonNode payload = events.get(i).get("params").get("payload");
            assertEquals("message.delta", events.get(i).get("params").get("type").asText());
            assertTrue(payload.get("reasoning").asBoolean(),
                    "event " + i + " should be reasoning=true");
        }

        for (int i = 2; i < 5; i++) {
            JsonNode payload = events.get(i).get("params").get("payload");
            assertEquals("message.delta", events.get(i).get("params").get("type").asText());
            assertFalse(payload.get("reasoning").asBoolean(),
                    "event " + i + " should be reasoning=false");
        }
    }

    @Test
    @DisplayName("reasoning token with empty token sends empty string instead of null")
    void reasoningToken_nullToken_emitsEmptyString() throws Exception {
        TextEvent event = new TextEvent(null, false, null, true, List.of());

        assertFalse(event.isTokenEvent(), "isTokenEvent() should be false when token=null");
    }

    @Test
    @DisplayName("TextEvent.ofReasoning has reasoning=true")
    void ofReasoning_hasReasoningTrue() {
        TextEvent event = TextEvent.ofReasoning("reasoning content");
        assertTrue(event.reasoning(), "ofReasoning() reasoning should be true");
        assertTrue(event.isTokenEvent(), "ofReasoning() should be a token event");
        assertEquals("reasoning content", event.token());
        assertNull(event.text(), "ofReasoning() text should be null");
    }

    @Test
    @DisplayName("TextEvent.ofToken has reasoning=false")
    void ofToken_hasReasoningFalse() {
        TextEvent event = TextEvent.ofToken("textContent");
        assertFalse(event.reasoning(), "ofToken() reasoning should be false");
        assertTrue(event.isTokenEvent(), "ofToken() should be a token event");
        assertEquals("textContent", event.token());
    }


    @Test
    @DisplayName("client parses message.delta reasoning=true -> handleToken(reasoning=true)")
    void clientParsing_reasoningDelta_routesToReasoning() throws Exception {
        emitter.emitMessageDelta("reasoning...", true);

        JsonNode eventNode = parseLastEvent();
        JsonNode params = eventNode.get("params");
        JsonNode payload = params.get("payload");

        String token = payload.has("token") ? payload.get("token").asText() : "";
        boolean reasoning = payload.has("reasoning") && payload.get("reasoning").asBoolean();

        assertEquals("reasoning...", token);
        assertTrue(reasoning, "client should parse reasoning=true");
    }

    @Test
    @DisplayName("client parses message.delta reasoning=false -> handleToken(reasoning=false)")
    void clientParsing_textDelta_routesToText() throws Exception {
        emitter.emitMessageDelta("text", false);

        JsonNode payload = parseLastEvent().get("params").get("payload");
        String token = payload.has("token") ? payload.get("token").asText() : "";
        boolean reasoning = payload.has("reasoning") && payload.get("reasoning").asBoolean();

        assertEquals("text", token);
        assertFalse(reasoning, "client should parse reasoning=false");
    }

    @Test
    @DisplayName("client parses message.intermediate -> handleIntermediate(intermediate=true)")
    void clientParsing_intermediateEvent() throws Exception {
        emitter.emitMessageIntermediate("intermediate message", true);

        JsonNode payload = parseLastEvent().get("params").get("payload");
        boolean intermediate = payload.has("intermediate") && payload.get("intermediate").asBoolean();

        assertTrue(intermediate, "client should parse intermediate=true");
    }

}
